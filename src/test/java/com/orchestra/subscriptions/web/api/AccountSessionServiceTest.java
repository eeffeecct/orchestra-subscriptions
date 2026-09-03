package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест на signed cookie session. Без Spring контекста — проверяем
 * матобработку: round-trip, tampering, expiry.
 */
class AccountSessionServiceTest {

    private AccountSessionService service;

    @BeforeEach
    void setUp() {
        AppConfig cfg = mock(AppConfig.class);
        AppConfig.TelegramConfig telegram = mock(AppConfig.TelegramConfig.class);
        when(cfg.getTelegram()).thenReturn(telegram);
        when(telegram.getBotToken()).thenReturn("123456:test-bot-token-secret");
        service = new AccountSessionService(cfg);
    }

    @Test
    void construction_failsWithoutBotToken() {
        AppConfig cfg = mock(AppConfig.class);
        AppConfig.TelegramConfig telegram = mock(AppConfig.TelegramConfig.class);
        when(cfg.getTelegram()).thenReturn(telegram);
        when(telegram.getBotToken()).thenReturn(null);

        assertThatThrownBy(() -> new AccountSessionService(cfg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot-token");

        when(telegram.getBotToken()).thenReturn("   ");
        assertThatThrownBy(() -> new AccountSessionService(cfg))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issueAndParse_roundTrip() {
        String cookie = service.issue(100200300L, 0);
        AccountSessionService.Session session = service.parse(cookie);
        assertThat(session.telegramId()).isEqualTo(100200300L);
        assertThat(session.sessionVersion()).isEqualTo(0);
    }

    @Test
    void roundTrip_preservesSessionVersion() {
        String cookie = service.issue(100200300L, 42);
        AccountSessionService.Session session = service.parse(cookie);
        assertThat(session.telegramId()).isEqualTo(100200300L);
        assertThat(session.sessionVersion()).isEqualTo(42);
    }

    @Test
    void parse_throwsOnNullAndBlank() {
        assertThatThrownBy(() -> service.parse(null))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
        assertThatThrownBy(() -> service.parse(""))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
        assertThatThrownBy(() -> service.parse("   "))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
    }

    @Test
    void parse_throwsWhenSignatureTampered() {
        String cookie = service.issue(1L, 0);
        // Меняем последний hex-символ подписи — подпись больше не сойдётся
        String tampered = cookie.substring(0, cookie.length() - 1)
                + (cookie.endsWith("0") ? "1" : "0");
        assertThatThrownBy(() -> service.parse(tampered))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class)
                .hasMessageContaining("sig mismatch");
    }

    @Test
    void parse_throwsWhenPayloadTampered() {
        String cookie = service.issue(1L, 0);
        // Меняем первый символ payload'а — подпись не сойдётся для нового payload
        String tampered = (cookie.charAt(0) == 'a' ? 'b' : 'a') + cookie.substring(1);
        assertThatThrownBy(() -> service.parse(tampered))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
    }

    @Test
    void parse_throwsOnBadFormat() {
        // Нет точки-разделителя
        assertThatThrownBy(() -> service.parse("nodot"))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class)
                .hasMessageContaining("bad format");
        // Точка в конце
        assertThatThrownBy(() -> service.parse("payload."))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
    }

    @Test
    void differentTokens_produceDifferentSecrets() {
        String cookie1 = service.issue(42L, 0);
        AccountSessionService.Session parsed1 = service.parse(cookie1);

        // Создаём второй сервис с другим bot-token — подпись от первого не должна валидироваться
        AppConfig cfg2 = mock(AppConfig.class);
        AppConfig.TelegramConfig telegram2 = mock(AppConfig.TelegramConfig.class);
        when(cfg2.getTelegram()).thenReturn(telegram2);
        when(telegram2.getBotToken()).thenReturn("999999:different-secret");
        AccountSessionService service2 = new AccountSessionService(cfg2);

        assertThat(parsed1.telegramId()).isEqualTo(42L);
        assertThatThrownBy(() -> service2.parse(cookie1))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
    }

    /**
     * Backward-compat: legacy-cookie без поля {@code sv=} в payload должен парситься
     * как sessionVersion=0. Без этого деплой PR'а разлогинил бы всех существующих
     * юзеров (миграция 020 выставит session_version=0 в БД, а в их старых cookies
     * sv будет отсутствовать → mismatch на любом endpoint'е).
     * <p>
     * Тест собирает legacy-cookie вручную в формате {@code "tid=N;exp=M"} (без sv).
     */
    @Test
    void parse_legacyCookieWithoutSv_defaultsToZero() throws Exception {
        long telegramId = 12345L;
        long exp = java.time.Instant.now().plus(AccountSessionService.TTL).getEpochSecond();
        String legacyPayload = "tid=" + telegramId + ";exp=" + exp;
        String payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(legacyPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Legacy-cookie нельзя выписать через сам сервис (он всегда пишет sv), поэтому
        // подписываем payload вручную тем же ключом, что и сервис. Ключ достаём рефлексией:
        // это единственная деталь, ради которой сюда лезем.
        AccountSessionService.Session parsed = service.parse(legacySignedCookie(payloadB64, legacyPayload));

        assertThat(parsed.telegramId()).isEqualTo(telegramId);
        assertThat(parsed.sessionVersion()).isZero();  // default для legacy cookie
    }

    /** Cookie в старом формате {@code payload.signature}, подписанная ключом самого сервиса. */
    private String legacySignedCookie(String payloadB64, String payload) throws Exception {
        java.lang.reflect.Field field = AccountSessionService.class.getDeclaredField("secretKey");
        field.setAccessible(true);
        byte[] secret = (byte[]) field.get(service);

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        byte[] sigBytes = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return payloadB64 + "." + java.util.HexFormat.of().formatHex(sigBytes);
    }
}
