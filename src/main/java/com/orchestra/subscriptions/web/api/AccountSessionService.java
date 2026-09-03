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
 * Управляет stateless сессией web-ЛК через подписанный cookie. Без хранения
 * session в БД: payload содержит {@code telegram_id}, {@code exp}, {@code sv}
 * (session_version), HMAC-SHA256 подписан секретом, выведенным из bot token.
 * Любая попытка подделать или протухший срок — {@link InvalidSessionException}.
 * <p>
 * Формат cookie value: {@code base64url(payload).hex(sig)}
 * Payload: {@code "tid=<long>;exp=<epochSeconds>;sv=<int>"} — простой key=value,
 * чтобы не тащить JSON-парсер сюда (минимизируем surface).
 * <p>
 * <b>TTL 7 дней.</b> Magic-link клик == «вход» на 7 дней. После — нужен новый
 * magic-link. Это компромисс между безопасностью и UX: 24 часа было бы строже,
 * но юзеры заходят раз в месяц поправить email/проверить срок, и регулярно
 * перевыпускать ссылку утомительно.
 * <p>
 * <b>Per-user revocation через session_version:</b> {@code sv} включается в payload
 * на момент {@link #issue}. Caller получает значение через {@link #parse} в виде
 * {@link Session}, и сверяет с актуальным {@code user.session_version}. Если кто-то
 * меняет email или удаляет (см. {@code WebAppController.updateEmail/deleteEmail}) —
 * sv в БД инкрементится, и старый cookie перестаёт быть валидным. Решает кейс
 * «утёк ноутбук с активной сессией — хочу разлогинить только этого юзера, не всех».
 * <p>
 * <b>Backward compatibility:</b> старые cookies без {@code sv} в payload парсятся
 * как {@code sv=0} — у всех существующих юзеров session_version=0 после миграции 020,
 * совпадение → деплой не разлогинивает существующих юзеров.
 */
@Slf4j
@Component
public class AccountSessionService {

    public static final String COOKIE_NAME = "account_session";
    public static final Duration TTL = Duration.ofDays(7);

    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] secretKey;

    public AccountSessionService(AppConfig appConfig) {
        // Деривируем session-secret из bot-token той же конструкцией, что TelegramInitDataValidator
        // ("WebAppData" + token) — но с другой константой, чтобы случайно не подходили оба:
        // если кто-то умудрится подделать одну подпись, не должен сразу получить и вторую.
        // Падаем на старте если bot-token не задан: без секрета HMAC использовал бы
        // публично известный нулевой ключ, и любой смог бы подделать session cookie
        // на произвольный telegram_id. Лучше сразу crash, чем тихая ditto уязвимость.
        String botToken = appConfig.getTelegram() == null ? null : appConfig.getTelegram().getBotToken();
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException(
                    "AccountSessionService: bot-token is not configured. " +
                    "Set BOT_TOKEN env variable — без него HMAC использует нулевой ключ " +
                    "и cookie-сессии становятся подделываемыми.");
        }
        this.secretKey = hmac("AccountSession".getBytes(StandardCharsets.UTF_8),
                botToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Распакованный payload signed cookie. {@code telegramId} и {@code sessionVersion}
     * нужно сверять с актуальным Customer.session_version у caller'а — если они не
     * совпадают, сессия отозвана (юзер сменил/удалил email после issue).
     */
    public record Session(long telegramId, int sessionVersion) {}

    /**
     * Собирает подписанный cookie для юзера с действительной 7-дневной сессией.
     *
     * @param telegramId юзер из {@link com.orchestra.subscriptions.model.Customer#getTelegramId()}
     * @param sessionVersion актуальное {@link com.orchestra.subscriptions.model.Customer#getSessionVersion()}
     *                       на момент выдачи. При смене email/удалении это значение
     *                       инкрементится в БД, и старый cookie с предыдущим sv перестаёт
     *                       быть валидным.
     */
    public String issue(long telegramId, int sessionVersion) {
        long expEpoch = Instant.now().plus(TTL).getEpochSecond();
        String payload = "tid=" + telegramId + ";exp=" + expEpoch + ";sv=" + sessionVersion;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sigHex = HexFormat.of().formatHex(hmac(payload.getBytes(StandardCharsets.UTF_8), secretKey));
        return payloadB64 + "." + sigHex;
    }

    /**
     * Проверяет cookie и возвращает {@link Session}. Backward-compat: если в payload нет
     * {@code sv} (старые cookies pre-020-migration) — возвращается {@code sessionVersion=0},
     * что соответствует default'у в БД, разлогина не происходит.
     *
     * @throws InvalidSessionException при формате/подписи/сроке. Caller возвращает 401 — детали не раскрываем.
     */
    public Session parse(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            throw new InvalidSessionException("empty cookie");
        }
        int dot = cookieValue.indexOf('.');
        if (dot < 0 || dot == cookieValue.length() - 1) {
            throw new InvalidSessionException("bad format");
        }
        String payloadB64 = cookieValue.substring(0, dot);
        String sigHex = cookieValue.substring(dot + 1);

        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
        } catch (IllegalArgumentException e) {
            throw new InvalidSessionException("payload not base64url");
        }

        // Константное сравнение подписи — без него можно byte-by-byte подобрать
        byte[] expectedSig = hmac(payloadBytes, secretKey);
        byte[] receivedSig;
        try {
            receivedSig = HexFormat.of().parseHex(sigHex);
        } catch (IllegalArgumentException e) {
            throw new InvalidSessionException("sig not hex");
        }
        if (!constantTimeEquals(expectedSig, receivedSig)) {
            throw new InvalidSessionException("sig mismatch");
        }

        // Парсим payload — простая key=value пара. sv опционален (старые cookies pre-020).
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        Long tid = null;
        Long exp = null;
        int sv = 0; // backward-compat default: совпадает с БД default'ом
        for (String pair : payload.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq);
            String v = pair.substring(eq + 1);
            try {
                if ("tid".equals(k)) tid = Long.parseLong(v);
                else if ("exp".equals(k)) exp = Long.parseLong(v);
                else if ("sv".equals(k)) sv = Integer.parseInt(v);
            } catch (NumberFormatException ignored) { /* остальные поля игнорим */ }
        }
        if (tid == null || exp == null) {
            throw new InvalidSessionException("missing fields");
        }
        if (Instant.now().getEpochSecond() > exp) {
            throw new InvalidSessionException("expired");
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

    public static class InvalidSessionException extends RuntimeException {
        public InvalidSessionException(String reason) { super(reason); }
    }
}
