package com.orchestra.subscriptions.client;

import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты верификатора HMAC-подписей CryptoBot. Алгоритм:
 * <pre>
 *   key = SHA-256(token)
 *   sig = HMAC-SHA256(key, body) в hex lowercase
 * </pre>
 * Эталонную подпись вычисляем тем же JDK API — если кто-то сломает логику в верификаторе,
 * тест сразу покажет расхождение (см. {@link #wrongSignature_returnsFalse()}).
 */
class CryptoBotSignatureVerifierTest {

    private static final String TOKEN = "12345:AAABBBCCCDDDEEEFFF";

    private CryptoBotSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        AppConfig appConfig = new AppConfig();
        AppConfig.CryptoBotConfig cfg = new AppConfig.CryptoBotConfig();
        cfg.setToken(TOKEN);
        appConfig.setCryptobot(cfg);

        verifier = new CryptoBotSignatureVerifier(appConfig);
        verifier.init();
    }

    @Test
    void validSignature_returnsTrue() {
        String body = "{\"update_type\":\"invoice_paid\",\"payload\":{\"invoice_id\":528890,\"status\":\"paid\"}}";
        String sig = sign(TOKEN, body);

        assertThat(verifier.verify(body, sig)).isTrue();
    }

    @Test
    void validSignatureUppercaseHex_returnsTrue() {
        // Клиенты/proxy могут случайно аплкейснуть hex — сравнение регистр-нечувствительно
        // у нас в коде (headerSignature.toLowerCase()), поэтому проверяем это явно.
        String body = "{\"update_type\":\"invoice_paid\"}";
        String sig = sign(TOKEN, body).toUpperCase();

        assertThat(verifier.verify(body, sig)).isTrue();
    }

    @Test
    void tamperedBody_returnsFalse() {
        String body = "{\"update_type\":\"invoice_paid\",\"payload\":{\"invoice_id\":528890}}";
        String sig = sign(TOKEN, body);

        // Меняем один байт — invoice_id 528890 → 528891. Подпись теперь не сходится.
        String tampered = body.replace("528890", "528891");
        assertThat(verifier.verify(tampered, sig)).isFalse();
    }

    @Test
    void wrongSignature_returnsFalse() {
        String body = "{\"update_type\":\"invoice_paid\"}";
        String wrong = "0000000000000000000000000000000000000000000000000000000000000000";

        assertThat(verifier.verify(body, wrong)).isFalse();
    }

    @Test
    void signatureWithDifferentToken_returnsFalse() {
        String body = "{\"update_type\":\"invoice_paid\"}";
        String sigFromOtherToken = sign("other-token", body);

        assertThat(verifier.verify(body, sigFromOtherToken)).isFalse();
    }

    @Test
    void nullSignature_returnsFalse() {
        assertThat(verifier.verify("{\"x\":1}", null)).isFalse();
    }

    @Test
    void blankSignature_returnsFalse() {
        assertThat(verifier.verify("{\"x\":1}", "")).isFalse();
        assertThat(verifier.verify("{\"x\":1}", "   ")).isFalse();
    }

    @Test
    void nullBody_returnsFalse() {
        assertThat(verifier.verify(null, "abc")).isFalse();
    }

    @Test
    void verifierWithoutToken_alwaysReturnsFalse() {
        AppConfig emptyConfig = new AppConfig();
        emptyConfig.setCryptobot(new AppConfig.CryptoBotConfig());
        CryptoBotSignatureVerifier noKey = new CryptoBotSignatureVerifier(emptyConfig);
        noKey.init();

        // Любой валидный HMAC, посчитанный любым ключом — всё равно false, потому что ключа нет.
        String body = "{\"x\":1}";
        String sigForSomeToken = sign("whatever", body);
        assertThat(noKey.verify(body, sigForSomeToken)).isFalse();
    }

    // Независимая реализация того же алгоритма для генерации эталонной подписи в тестах.
    private static String sign(String token, String body) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
