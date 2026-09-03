package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Сессия админ-панели: подписанный cookie, как у web-ЛК, но со своим ключом и своим сроком.
 * <p>
 * <b>Почему не переиспользован {@link AccountSessionService}.</b> Кабинет и панель — разные
 * уровни доступа: в кабинете человек видит себя, в панели владелец видит всех. Одна cookie на
 * оба означала бы, что «вошёл в кабинет» автоматически равно «вошёл в панель», и любая ошибка
 * в выдаче кабинетной сессии сразу становилась бы дырой в панель. Ключи выведены из bot-token
 * с разными константами, поэтому подпись одной не подходит к другой.
 * <p>
 * Формат значения совпадает с кабинетным: {@code base64url(payload).hex(sig)},
 * payload {@code "tid=<long>;exp=<epochSeconds>;sv=<int>"}.
 * <p>
 * <b>Срок 12 часов</b> — рабочий день. Панель показывает персональные данные всех клиентов,
 * поэтому семидневная сессия кабинета здесь избыточна: ссылка на вход запрашивается в боте
 * одной командой, продлить дешево.
 * <p>
 * <b>Отзыв.</b> В payload лежит {@code session_version} владельца из {@code customers}. Этого
 * же поля касается «Выйти на всех устройствах» в кабинете и смена e-mail. Значит, отзыв
 * переживает рестарт приложения (в отличие от счётчика в памяти) и не требует новой таблицы:
 * достаточно инкрементить владельцу {@code session_version}, и выданные ранее cookie панели
 * перестают валидироваться.
 */
@Slf4j
@Component
public class AdminSessionService {

    public static final String COOKIE_NAME = "admin_session";
    public static final Duration TTL = Duration.ofHours(12);

    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] secretKey;

    public AdminSessionService(AppConfig appConfig) {
        String botToken = appConfig.getTelegram() == null ? null : appConfig.getTelegram().getBotToken();
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException(
                    "AdminSessionService: bot-token is not configured. "
                    + "Без секрета HMAC считался бы нулевым ключом, и cookie админ-панели "
                    + "подделал бы кто угодно.");
        }
        this.secretKey = hmac("AdminSession".getBytes(StandardCharsets.UTF_8),
                botToken.getBytes(StandardCharsets.UTF_8));
    }

    /** Распакованный payload. {@code sessionVersion} сверяется вызывающим с актуальным в БД. */
    public record Session(long telegramId, int sessionVersion) {}

    public String issue(long telegramId, int sessionVersion) {
        long expEpoch = Instant.now().plus(TTL).getEpochSecond();
        String payload = "tid=" + telegramId + ";exp=" + expEpoch + ";sv=" + sessionVersion;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sigHex = HexFormat.of().formatHex(hmac(payload.getBytes(StandardCharsets.UTF_8), secretKey));
        return payloadB64 + "." + sigHex;
    }

    /**
     * Проверяет формат, подпись и срок. Отсутствие {@code sv} в payload — не «ноль по
     * умолчанию», а отказ: у панели нет legacy-cookie, которые нужно было бы пережить, а
     * молчаливый ноль совпал бы с {@code session_version=0} у владельца и обесценил отзыв.
     *
     * @throws AccountSessionService.InvalidSessionException при любой из проверок
     */
    public Session parse(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            throw new AccountSessionService.InvalidSessionException("empty cookie");
        }
        int dot = cookieValue.indexOf('.');
        if (dot < 0 || dot == cookieValue.length() - 1) {
            throw new AccountSessionService.InvalidSessionException("bad format");
        }
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(cookieValue.substring(0, dot));
        } catch (IllegalArgumentException e) {
            throw new AccountSessionService.InvalidSessionException("payload not base64url");
        }
        byte[] receivedSig;
        try {
            receivedSig = HexFormat.of().parseHex(cookieValue.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new AccountSessionService.InvalidSessionException("sig not hex");
        }
        if (!constantTimeEquals(hmac(payloadBytes, secretKey), receivedSig)) {
            throw new AccountSessionService.InvalidSessionException("sig mismatch");
        }

        Long tid = null;
        Long exp = null;
        Integer sv = null;
        for (String pair : new String(payloadBytes, StandardCharsets.UTF_8).split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq);
            String v = pair.substring(eq + 1);
            try {
                if ("tid".equals(k)) tid = Long.parseLong(v);
                else if ("exp".equals(k)) exp = Long.parseLong(v);
                else if ("sv".equals(k)) sv = Integer.parseInt(v);
            } catch (NumberFormatException ignored) { /* прочие поля не наши */ }
        }
        if (tid == null || exp == null || sv == null) {
            throw new AccountSessionService.InvalidSessionException("missing fields");
        }
        if (Instant.now().getEpochSecond() > exp) {
            throw new AccountSessionService.InvalidSessionException("expired");
        }
        return new Session(tid, sv);
    }

    private static byte[] hmac(byte[] data, byte[] key) {
        if (key.length == 0) {
            throw new IllegalStateException("HMAC key is empty — refusing to compute");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
