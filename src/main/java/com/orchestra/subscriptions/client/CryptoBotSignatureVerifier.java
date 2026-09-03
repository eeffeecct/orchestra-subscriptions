package com.orchestra.subscriptions.client;

import com.orchestra.subscriptions.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Проверка HMAC-подписи webhook-уведомлений от Crypto Pay API.
 * <p>
 * По документации @CryptoBot:
 * <pre>
 *   key = SHA-256(api_token)            // raw 32 bytes, НЕ hex
 *   signature = HMAC-SHA256(key, body)  // hex lowercase
 * </pre>
 * Заголовок: {@code crypto-pay-api-signature}.
 * <p>
 * Критично: HMAC считается от сырых байт тела запроса. Поэтому контроллер принимает тело
 * как {@code String} (см. {@link com.orchestra.subscriptions.web.WebhookController#cryptobot}) — попытка
 * десериализовать в {@code Map} и сериализовать обратно поменяет порядок/пробелы и сломает HMAC.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoBotSignatureVerifier {

    private final AppConfig appConfig;

    /** Кешированный HMAC-ключ = SHA-256(token). Инициализируется в @PostConstruct, один раз. */
    private volatile byte[] key;

    @PostConstruct
    public void init() {
        String token = appConfig.getCryptobot() == null ? null : appConfig.getCryptobot().getToken();
        if (token == null || token.isBlank()) {
            log.warn("CryptoBot token not configured — webhook signature verification will always fail");
            this.key = null;
            return;
        }
        this.key = sha256(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param rawBody          сырая строка тела POST-запроса (как пришла, без ре-сериализации)
     * @param headerSignature  значение заголовка {@code crypto-pay-api-signature}
     * @return {@code true} если подпись корректная
     */
    public boolean verify(String rawBody, String headerSignature) {
        if (key == null) {
            log.warn("CryptoBot signature verifier not initialized — rejecting");
            return false;
        }
        if (rawBody == null || headerSignature == null || headerSignature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expectedHex = toHex(expected);
            // Constant-time compare — подпись в заголовке приходит от клиента, не тайминг-утекаем.
            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    headerSignature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("HMAC verification failed", e);
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
