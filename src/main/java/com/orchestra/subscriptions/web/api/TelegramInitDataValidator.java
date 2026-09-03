package com.orchestra.subscriptions.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestra.subscriptions.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Валидация подписанной строки {@code Telegram.WebApp.initData}.
 * <p>
 * Формат initData (URL-encoded query string):
 * <pre>
 * auth_date=1714025520&user=%7B%22id%22%3A100200300%2C...%7D&hash=abc123...
 * </pre>
 * <p>
 * Алгоритм проверки (по <a href="https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app">
 * официальной доке</a>):
 * <ol>
 *   <li>Разобрать строку на пары ключ=значение</li>
 *   <li>Выделить {@code hash}; остальное отсортировать по ключу и склеить в
 *       data-check-string: {@code "auth_date=...\nquery_id=...\nuser=..."}</li>
 *   <li>Секрет = HMAC-SHA256({@code "WebAppData"}, botToken) — то есть
 *       токен подписывается константой-строкой "WebAppData"</li>
 *   <li>Ожидаемый hash = HMAC-SHA256(secret, data-check-string) в hex</li>
 *   <li>Сравнить с hash из initData (константное время, чтобы не утекло timing-ом)</li>
 * </ol>
 * <p>
 * Подделать initData снаружи нельзя — только Telegram знает botToken. Если подпись сошлась,
 * {@code user.id} внутри initData — это действительно тот Telegram ID, от имени которого
 * пришёл запрос.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramInitDataValidator {

    /** Максимальный возраст initData. Telegram рекомендует 24 часа; урезаем до 1 часа —
     *  у нас сессия короткая, нет смысла принимать старые подписи. */
    private static final Duration MAX_AGE = Duration.ofHours(1);

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    /**
     * Проверяет подпись и возвращает telegramId пользователя.
     *
     * @throws InvalidInitDataException если подпись невалидна, просрочена или поля отсутствуют
     */
    public long validateAndExtractUserId(String initData) {
        return validateAndExtractUser(initData).id();
    }

    /**
     * То же что {@link #validateAndExtractUserId}, но возвращает полную запись о юзере
     * (id + username + first_name). Полезно когда нужен username, чтобы записать
     * в Payment.telegramUsername.
     */
    public TelegramUser validateAndExtractUser(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new InvalidInitDataException("empty initData");
        }

        // Разбираем query string в отсортированную Map (TreeMap сортирует по ключу автоматически)
        Map<String, String> params = parseQueryString(initData);

        String receivedHash = params.remove("hash");
        if (receivedHash == null) {
            throw new InvalidInitDataException("missing hash");
        }

        // Собираем data-check-string: "key1=value1\nkey2=value2\n..."
        String dataCheckString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow(() -> new InvalidInitDataException("no fields to check"));

        // secret_key = HMAC-SHA256("WebAppData", botToken)
        byte[] secretKey = hmacSha256("WebAppData".getBytes(StandardCharsets.UTF_8),
                appConfig.getTelegram().getBotToken().getBytes(StandardCharsets.UTF_8));

        // expected_hash = HMAC-SHA256(secret_key, data_check_string)
        byte[] expectedHashBytes = hmacSha256(secretKey,
                dataCheckString.getBytes(StandardCharsets.UTF_8));
        String expectedHash = HexFormat.of().formatHex(expectedHashBytes);

        // Константное сравнение: защита от timing attack.
        if (!constantTimeEquals(expectedHash, receivedHash)) {
            throw new InvalidInitDataException("hash mismatch");
        }

        // Проверяем срок годности
        String authDateStr = params.get("auth_date");
        if (authDateStr == null) {
            throw new InvalidInitDataException("missing auth_date");
        }
        Instant authDate = Instant.ofEpochSecond(Long.parseLong(authDateStr));
        if (Instant.now().isAfter(authDate.plus(MAX_AGE))) {
            throw new InvalidInitDataException("initData expired");
        }

        // Вытаскиваем user (он — JSON-строка внутри query string)
        String userJson = params.get("user");
        if (userJson == null) {
            throw new InvalidInitDataException("missing user");
        }
        try {
            JsonNode user = objectMapper.readTree(userJson);
            JsonNode idNode = user.get("id");
            if (idNode == null || !idNode.canConvertToLong()) {
                throw new InvalidInitDataException("missing user.id");
            }
            String username = user.has("username") ? user.get("username").asText(null) : null;
            return new TelegramUser(idNode.asLong(), username);
        } catch (InvalidInitDataException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidInitDataException("malformed user JSON");
        }
    }

    /** Подписанный юзер из initData. */
    public record TelegramUser(long id, String username) {}

    /**
     * Разбирает строку "a=1&b=2&c=3" в TreeMap (отсортированную по ключу).
     * URL-декодирует значения — они приходят в percent-encoded виде.
     */
    private Map<String, String> parseQueryString(String qs) {
        TreeMap<String, String> result = new TreeMap<>();
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    /** HMAC-SHA256. Возвращает сырые 32 байта. */
    private byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** Константное время сравнения: не ветвится на первом несовпадении. Защита от timing attack. */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public static class InvalidInitDataException extends RuntimeException {
        public InvalidInitDataException(String message) { super(message); }
    }
}
