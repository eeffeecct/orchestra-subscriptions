package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.model.MagicLink;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.MagicLinkRepository;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.MagicLinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Покрывает GET /auth, GET /api/account/me, POST /auth/logout — поведение web-ЛК.
 * Spring контекст полный, Postgres через Testcontainers, signed cookie генерится
 * настоящим AccountSessionService (а не моком), чтобы проверять реальную
 * подпись end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CustomerRepository userRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    MagicLinkRepository magicLinkRepository;

    @Autowired
    MagicLinkService magicLinkService;

    @Autowired
    AccountSessionService sessionService;

    @Autowired
    MagicLinkRateLimiter magicLinkRateLimiter;

    @MockitoBean
    com.orchestra.subscriptions.access.AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;


    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;


    // Mock'аем async-sender чтобы тесты не ждали реальной фоновой отправки
    // и могли инспектировать факт вызова через verify(...).
    @MockitoBean
    AccountMagicLinkAsyncSender asyncSender;

    @Autowired
    RequestRateLimiter requestRateLimiter;

    private static final long TELEGRAM_ID = 100200300L;

    @BeforeEach
    void setUp() {
        magicLinkRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();
        magicLinkRateLimiter.resetForTesting();
        // Счётчики общего лимитера живут в памяти бина и переживают тест: без сброса
        // порядок выполнения начинает влиять на результат.
        requestRateLimiter.resetForTesting();
    }

    private Customer createUserWithEmail() {
        return userRepository.save(Customer.builder()
                .telegramId(TELEGRAM_ID)
                .providerUsername("sub_" + TELEGRAM_ID)
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .email("kabinet@example.com")
                .subscriptionLink("https://example.com/sub/test-token")
                .build());
    }

    // =====================================================================
    //  GET /auth?token=...
    // =====================================================================

    @Test
    void auth_withValidToken_redirectsToAccountAndSetsCookie() throws Exception {
        Customer user = createUserWithEmail();
        // Ссылку запрашивает браузер с nonce, он же её открывает — сессия выдаётся сразу.
        // Ветка «открыли в другом браузере» (экран подтверждения) живёт в
        // AccountLoginConfirmIntegrationTest, здесь проверяется happy path.
        String nonce = "test-browser-nonce";
        String token = magicLinkService.create(user, nonce);

        MvcResult result = mockMvc.perform(get("/auth")
                        .param("token", token)
                        .cookie(new jakarta.servlet.http.Cookie(AccountController.NONCE_COOKIE, nonce)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/"))
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        // Set-Cookie теперь несколько (сессия + гашение nonce), поэтому ищем нужную,
        // а не берём первую попавшуюся.
        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(AccountSessionService.COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("сессионная cookie не выдана"));
        assertThat(setCookie)
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");

        // Токен помечен использованным
        MagicLink consumed = magicLinkRepository.findByToken(token).orElseThrow();
        assertThat(consumed.getUsedAt()).isNotNull();

        // Email подтверждён
        Customer reloaded = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(reloaded.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void auth_withInvalidToken_redirectsToExpired() throws Exception {
        mockMvc.perform(get("/auth").param("token", "nonexistent-token"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/expired"))
                // Cookie не выдаётся при неудачной авторизации
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void auth_withExpiredToken_redirectsToExpired() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user);
        // Сдвигаем expires в прошлое
        MagicLink link = magicLinkRepository.findByToken(token).orElseThrow();
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        magicLinkRepository.save(link);

        mockMvc.perform(get("/auth").param("token", token))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/expired"));
    }

    @Test
    void auth_withAlreadyUsedToken_redirectsToExpired() throws Exception {
        Customer user = createUserWithEmail();
        // С nonce — иначе первый GET показал бы экран подтверждения и токен остался бы
        // неиспользованным, то есть до проверяемого сценария тест бы не дошёл.
        String nonce = "test-browser-nonce";
        String token = magicLinkService.create(user, nonce);
        jakarta.servlet.http.Cookie nonceCookie =
                new jakarta.servlet.http.Cookie(AccountController.NONCE_COOKIE, nonce);
        // Первый раз — успех
        mockMvc.perform(get("/auth").param("token", token).cookie(nonceCookie))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/"));
        // Второй — на expired
        mockMvc.perform(get("/auth").param("token", token).cookie(nonceCookie))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/expired"));
    }

    @Test
    void auth_withoutToken_redirectsToExpired() throws Exception {
        mockMvc.perform(get("/auth"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/expired"));
    }

    // =====================================================================
    //  GET /api/account/me
    // =====================================================================

    @Test
    void accountMe_withValidCookie_returnsData() throws Exception {
        createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(get("/api/account/me")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("kabinet@example.com"))
                .andExpect(jsonPath("$.hasSubscription").value(true))
                .andExpect(jsonPath("$.subscriptionLink").value("https://example.com/sub/test-token"))
                .andExpect(jsonPath("$.isTrial").value(true)); // нет SUCCEEDED платежей
    }

    @Test
    void accountMe_withoutCookie_returns401() throws Exception {
        createUserWithEmail();

        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void accountMe_withTamperedCookie_returns401() throws Exception {
        createUserWithEmail();
        String valid = sessionService.issue(TELEGRAM_ID, 0);
        // Меняем последний символ → подпись не сходится
        String tampered = valid.substring(0, valid.length() - 1)
                + (valid.endsWith("0") ? "1" : "0");

        mockMvc.perform(get("/api/account/me")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountMe_whenUserDeletedAfterCookieIssued_returns401() throws Exception {
        Customer user = createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);
        userRepository.delete(user);

        mockMvc.perform(get("/api/account/me")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    //  POST /auth/logout
    // =====================================================================

    @Test
    void logout_clearsCookie() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(jsonPath("$.ok").value(true));

        MvcResult result = mockMvc.perform(post("/auth/logout")).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .contains(AccountSessionService.COOKIE_NAME + "=;")
                .contains("Max-Age=0");
    }

    // =====================================================================
    //  POST /auth/request — публичная форма
    // =====================================================================

    @Test
    void authRequest_knownEmail_schedulesAsyncSendAndReturnsGenericMessage() throws Exception {
        Customer user = createUserWithEmail();

        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "kabinet@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("привязан")));

        // Async-sender вызван — реальное создание токена и SMTP делаются в фоне
        // и тестируются отдельно (см. AccountMagicLinkAsyncSender unit-тесты).
        // Здесь важно, что контроллер не ждёт этой работы.
        org.mockito.ArgumentCaptor<Customer> captor =
                org.mockito.ArgumentCaptor.forClass(Customer.class);
        // Второй аргумент — nonce браузера, попросившего ссылку: по нему GET /auth отличает
        // «открыли в том же браузере» от «открыли в другом» и во втором случае показывает
        // экран подтверждения. Пустой nonce обессмыслил бы всю защиту, поэтому проверяем.
        org.mockito.ArgumentCaptor<String> nonceCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(asyncSender)
                .sendMagicLink(captor.capture(), nonceCaptor.capture());
        assertThat(captor.getValue().getTelegramId()).isEqualTo(user.getTelegramId());
        assertThat(nonceCaptor.getValue()).isNotBlank();
    }

    @Test
    void authRequest_unknownEmail_returnsSameGenericMessage_noAsyncSend() throws Exception {
        // Юзера специально не создаём — email никому не принадлежит
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "nobody@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("привязан")));

        // Async-sender НЕ вызван — защита от спама и leak'а через timing
        org.mockito.Mockito.verify(asyncSender, org.mockito.Mockito.never())
                .sendMagicLink(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authRequest_invalidEmailFormat_returns400() throws Exception {
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_format"));
    }

    @Test
    void authRequest_emptyEmail_returns400() throws Exception {
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("email", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_format"));
    }

    @Test
    void authRequest_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_format"));
    }

    @Test
    void authRequest_rateLimitByIp_returns429() throws Exception {
        createUserWithEmail();

        // Первый запрос — успех
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.55")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "kabinet@example.com"))))
                .andExpect(status().isOk());

        // Второй с того же IP — cooldown
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.55")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "kabinet@example.com"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"));

        // С другого IP — снова успех (rate-limit per-IP)
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.99")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "kabinet@example.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void authRequest_extractsIpFromXForwardedFor_rightmost() throws Exception {
        createUserWithEmail();

        // Caddy дописывает реальный peer-IP справа → клиент = ПОСЛЕДНИЙ элемент. Левые
        // элементы клиент может подделать, доверять им для rate-limit нельзя.
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "1.1.1.1, 192.168.1.1, 203.0.113.7")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "kabinet@example.com"))))
                .andExpect(status().isOk());

        // Другой ЛЕВЫЙ префикс, но тот же rightmost (203.0.113.7) — лимит по нему и должен сработать.
        mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "9.9.9.9, 203.0.113.7")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "kabinet@example.com"))))
                .andExpect(status().isTooManyRequests());
    }

    // =====================================================================
    //  GET /api/account/tariffs
    // =====================================================================

    @Test
    void tariffs_withCookie_returnsList() throws Exception {
        createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(get("/api/account/tariffs")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].title").exists())
                .andExpect(jsonPath("$[0].priceKopeks").exists());
    }

    @Test
    void tariffs_withoutCookie_returns401() throws Exception {
        mockMvc.perform(get("/api/account/tariffs"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    //  POST /api/account/payments/yookassa
    // =====================================================================

    @Test
    void accountPayYk_withCookieAndPlan_createsPendingPaymentAndReturnsConfirmation() throws Exception {
        createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        // Готовим mock YooKassa: возвращаем confirmationUrl
        com.orchestra.subscriptions.client.YooKassaClient.PaymentResponse fakeResp =
                new com.orchestra.subscriptions.client.YooKassaClient.PaymentResponse();
        fakeResp.setId("yk-test-12345");
        com.orchestra.subscriptions.client.YooKassaClient.Confirmation conf =
                new com.orchestra.subscriptions.client.YooKassaClient.Confirmation();
        conf.setConfirmationUrl("https://yoomoney.test/checkout/xyz");
        fakeResp.setConfirmation(conf);
        org.mockito.Mockito.when(yooKassaClient.createPayment(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()
        )).thenReturn(fakeResp);

        mockMvc.perform(post("/api/account/payments/yookassa")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("planId", "month_1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("yk-test-12345"))
                .andExpect(jsonPath("$.confirmationUrl").value("https://yoomoney.test/checkout/xyz"));

        // В БД создан PENDING Payment с YOOKASSA provider и привязанным yookassaPaymentId
        assertThat(paymentRepository.findAll())
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.getStatus()).isEqualTo(com.orchestra.subscriptions.model.Payment.Status.PENDING);
                    assertThat(p.getProvider()).isEqualTo(com.orchestra.subscriptions.model.Payment.Provider.YOOKASSA);
                    assertThat(p.getYookassaPaymentId()).isEqualTo("yk-test-12345");
                    assertThat(p.getTelegramId()).isEqualTo(TELEGRAM_ID);
                    assertThat(p.getPlanId()).isEqualTo("month_1");
                });
    }

    @Test
    void accountPayYk_withoutCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/account/payments/yookassa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("planId", "month_1"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountPayYk_unknownPlan_returns400() throws Exception {
        createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(post("/api/account/payments/yookassa")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("planId", "no_such_plan"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown plan"));
    }

    @Test
    void accountPayYk_missingPlanId_returns400() throws Exception {
        createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(post("/api/account/payments/yookassa")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing planId"));
    }

    // =====================================================================
    //  GET /api/account/payments/{id}/status — honest banner после возврата с YK
    // =====================================================================

    private com.orchestra.subscriptions.model.Payment createPayment(long telegramId,
                                                       com.orchestra.subscriptions.model.Payment.Status status,
                                                       String yookassaId) {
        return paymentRepository.save(com.orchestra.subscriptions.model.Payment.builder()
                .telegramId(telegramId)
                .planId("month_1")
                .amountKopeks(15_000L)
                .status(status)
                .provider(com.orchestra.subscriptions.model.Payment.Provider.YOOKASSA)
                .yookassaPaymentId(yookassaId)
                .paidAt(status == com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED ? LocalDateTime.now() : null)
                .build());
    }

    @Test
    void paymentStatus_succeededOwn_returnsFullDetails() throws Exception {
        createUserWithEmail();
        com.orchestra.subscriptions.model.Payment p = createPayment(TELEGRAM_ID,
                com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED, "yk-1");
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(get("/api/account/payments/" + p.getId() + "/status")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amountKopeks").value(15_000))
                .andExpect(jsonPath("$.planName").exists())
                .andExpect(jsonPath("$.paidAtEpoch").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void paymentStatus_pendingOwn_returnsPendingWithoutPaidAt() throws Exception {
        createUserWithEmail();
        com.orchestra.subscriptions.model.Payment p = createPayment(TELEGRAM_ID,
                com.orchestra.subscriptions.model.Payment.Status.PENDING, "yk-2");
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(get("/api/account/payments/" + p.getId() + "/status")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paidAtEpoch").value(0));
    }

    @Test
    void paymentStatus_canceledOwn_returnsCanceled() throws Exception {
        createUserWithEmail();
        com.orchestra.subscriptions.model.Payment p = createPayment(TELEGRAM_ID,
                com.orchestra.subscriptions.model.Payment.Status.CANCELED, "yk-3");
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(get("/api/account/payments/" + p.getId() + "/status")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void paymentStatus_othersPayment_returns404_noIdorLeak() throws Exception {
        // Cookie на TELEGRAM_ID, а Payment принадлежит другому
        createUserWithEmail();
        com.orchestra.subscriptions.model.Payment otherPayment = createPayment(999L,
                com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED, "yk-other");
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(get("/api/account/payments/" + otherPayment.getId() + "/status")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void paymentStatus_unknownId_returns404() throws Exception {
        createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);

        mockMvc.perform(get("/api/account/payments/99999999/status")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isNotFound());
    }

    @Test
    void paymentStatus_withoutCookie_returns401() throws Exception {
        createUserWithEmail();
        com.orchestra.subscriptions.model.Payment p = createPayment(TELEGRAM_ID,
                com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED, "yk-x");

        mockMvc.perform(get("/api/account/payments/" + p.getId() + "/status"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    //  POST /api/account/promos/validate — лимит попыток
    // =====================================================================

    /**
     * Тот же лимит, что у ручки Mini App: у кабинета своя аутентификация, но оракул один и
     * тот же — ответ содержит сумму скидки. Прикрыть один вход и оставить второй значит не
     * прикрыть ничего.
     */
    @Test
    void accountPromoValidate_isRateLimited() throws Exception {
        createUserWithEmail();
        String cookieValue = sessionService.issue(TELEGRAM_ID, 0);
        String body = "{\"code\":\"NOSUCHCODE\",\"planId\":\"month_1\"}";

        for (int i = 0; i < PromoResponses.VALIDATE_MAX_PER_HOUR; i++) {
            mockMvc.perform(post("/api/account/promos/validate")
                            .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false));
        }

        mockMvc.perform(post("/api/account/promos/validate")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookieValue))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"));
    }

    /** Без cookie ручка и до лимитера не доходит — 401 остаётся 401. */
    @Test
    void accountPromoValidate_withoutCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/account/promos/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"planId\":\"month_1\"}"))
                .andExpect(status().isUnauthorized());
    }
}
