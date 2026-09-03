package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cookie админ-панели: подпись, отзыв через session_version и разделение ключей с кабинетом.
 * Без Spring — конструктору нужен только bot-token.
 */
class AdminSessionServiceTest {

    private static AppConfig config(String botToken) {
        AppConfig config = new AppConfig();
        AppConfig.TelegramConfig telegram = new AppConfig.TelegramConfig();
        telegram.setBotToken(botToken);
        config.setTelegram(telegram);
        return config;
    }

    private static final AppConfig CONFIG = config("123456:test-bot-token");

    @Test
    void issuedCookieParsesBackToSameOwnerAndVersion() {
        AdminSessionService service = new AdminSessionService(CONFIG);

        AdminSessionService.Session session = service.parse(service.issue(100200300L, 7));

        assertThat(session.telegramId()).isEqualTo(100200300L);
        assertThat(session.sessionVersion()).isEqualTo(7);
    }

    @Test
    void tamperedSignatureIsRejected() {
        AdminSessionService service = new AdminSessionService(CONFIG);
        String cookie = service.issue(1L, 0);
        String tampered = cookie.substring(0, cookie.length() - 1)
                + (cookie.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> service.parse(tampered))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
    }

    @Test
    void payloadEditedToAnotherOwnerIsRejected() {
        AdminSessionService service = new AdminSessionService(CONFIG);
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("tid=1;exp=" + (System.currentTimeMillis() / 1000 + 3600) + ";sv=0")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + "00".repeat(32);

        assertThatThrownBy(() -> service.parse(forged))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
    }

    @Test
    void cookieOfAccountCabinetDoesNotOpenThePanel() {
        // Ключи выведены из одного bot-token, но с разными константами: подпись кабинета
        // не должна подходить к панели, иначе «вошёл в ЛК» = «вошёл в админку».
        AdminSessionService admin = new AdminSessionService(CONFIG);
        AccountSessionService account = new AccountSessionService(CONFIG);

        String accountCookie = account.issue(100200300L, 0);

        assertThatThrownBy(() -> admin.parse(accountCookie))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class);
    }

    @Test
    void expiredCookieIsRejected() {
        AdminSessionService service = new AdminSessionService(CONFIG);
        // Собираем payload с истёкшим exp той же подписью, что и живой — проверяется именно срок.
        String expired = signed(service, "tid=1;exp=" + (System.currentTimeMillis() / 1000 - 10) + ";sv=0");

        assertThatThrownBy(() -> service.parse(expired))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void payloadWithoutSessionVersionIsRejected() {
        // У панели нет legacy-cookie, которые надо пережить: молчаливый sv=0 совпал бы с
        // реальным значением у владельца и обесценил отзыв сессии.
        AdminSessionService service = new AdminSessionService(CONFIG);
        String noSv = signed(service, "tid=1;exp=" + (System.currentTimeMillis() / 1000 + 3600));

        assertThatThrownBy(() -> service.parse(noSv))
                .isInstanceOf(AccountSessionService.InvalidSessionException.class)
                .hasMessageContaining("missing fields");
    }

    @Test
    void missingBotTokenFailsFastInsteadOfSigningWithEmptyKey() {
        assertThatThrownBy(() -> new AdminSessionService(config("")))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Подписывает произвольный payload тем же ключом, что и сервис — через его же issue. */
    private static String signed(AdminSessionService service, String payload) {
        try {
            java.lang.reflect.Field field = AdminSessionService.class.getDeclaredField("secretKey");
            field.setAccessible(true);
            byte[] key = (byte[]) field.get(service);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                    + "." + java.util.HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
