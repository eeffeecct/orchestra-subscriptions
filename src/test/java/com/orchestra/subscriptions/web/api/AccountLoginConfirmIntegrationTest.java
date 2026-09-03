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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Привязка magic-link к браузеру: быстрая ветка, страница подтверждения и
 * {@code POST /auth/confirm}.
 * <p>
 * Главный инвариант, который защищают эти тесты: сессионная cookie не появляется от
 * одного лишь GET-перехода по ссылке из письма, если ссылку открыл не тот браузер,
 * который её просил. Именно этим атакующий заводил жертву в свой кабинет — и жертва
 * могла оплатить чужую подписку.
 * <p>
 * Второй инвариант: показ страницы подтверждения НЕ гасит токен. Письмо проходит через
 * почтовые сканеры и предзагрузку ссылок, и токен, сгоревший на автоматическом открытии
 * URL, оставил бы человека без входа.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountLoginConfirmIntegrationTest {

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
    MagicLinkRateLimiter magicLinkRateLimiter;

    @MockitoBean
    com.orchestra.subscriptions.access.AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;


    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;

    /** Реальная отправка письма тут не нужна — проверяем только cookie в ответе. */
    @MockitoBean
    AccountMagicLinkAsyncSender asyncSender;

    private static final long TELEGRAM_ID = 714478250L;
    private static final String EMAIL = "kabinet@example.com";
    private static final String MASKED_EMAIL = "k***@example.com";

    @BeforeEach
    void setUp() {
        magicLinkRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();
        magicLinkRateLimiter.resetForTesting();
    }

    private Customer createUserWithEmail() {
        return userRepository.save(Customer.builder()
                .telegramId(TELEGRAM_ID)
                .providerUsername("sub_" + TELEGRAM_ID)
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .email(EMAIL)
                .subscriptionLink("https://example.com/sub/test-token")
                .build());
    }

    // =====================================================================
    //  GET /auth — быстрая ветка и страница подтверждения
    // =====================================================================

    @Test
    void auth_fromSameBrowser_issuesSessionImmediately() throws Exception {
        Customer user = createUserWithEmail();
        String nonce = "nonce-of-the-browser-that-asked";
        String token = magicLinkService.create(user, nonce);

        MvcResult result = mockMvc.perform(get("/auth")
                        .param("token", token)
                        .cookie(new Cookie(AccountController.NONCE_COOKIE, nonce)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/"))
                .andReturn();

        assertThat(sessionCookie(result)).isNotNull();
        assertThat(usedAtOf(token)).isNotNull();
    }

    @Test
    void auth_fromAnotherBrowser_showsConfirmPageWithoutSessionAndKeepsToken() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user, "nonce-of-the-browser-that-asked");

        MvcResult result = mockMvc.perform(get("/auth").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Подтвердите вход")))
                // Адрес получателя показываем замаскированным — страницу видит и тот,
                // кому ссылка не предназначалась.
                .andExpect(content().string(containsString(MASKED_EMAIL)))
                .andExpect(content().string(not(containsString(EMAIL))))
                .andReturn();

        assertThat(sessionCookie(result)).isNull();
        assertThat(setCookieValue(result, AccountController.CONFIRM_COOKIE)).isNotBlank();
        // Токен цел: подтверждение ещё не нажимали.
        assertThat(usedAtOf(token)).isNull();
    }

    @Test
    void auth_withForeignNonce_showsConfirmPage() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user, "nonce-of-the-browser-that-asked");

        MvcResult result = mockMvc.perform(get("/auth")
                        .param("token", token)
                        .cookie(new Cookie(AccountController.NONCE_COOKIE, "some-other-browser-nonce")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sessionCookie(result)).isNull();
        assertThat(usedAtOf(token)).isNull();
    }

    @Test
    void auth_whenLinkIsPrefetched_doesNotBurnTokenEvenWithMatchingNonce() throws Exception {
        Customer user = createUserWithEmail();
        String nonce = "nonce-of-the-browser-that-asked";
        String token = magicLinkService.create(user, nonce);

        MvcResult result = mockMvc.perform(get("/auth")
                        .param("token", token)
                        .cookie(new Cookie(AccountController.NONCE_COOKIE, nonce))
                        .header("Sec-Purpose", "prefetch;prerender"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sessionCookie(result)).isNull();
        assertThat(usedAtOf(token)).isNull();
    }

    @Test
    void auth_withInvalidToken_stillRedirectsToExpired() throws Exception {
        mockMvc.perform(get("/auth").param("token", "nonexistent-token"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/expired"));
    }

    // =====================================================================
    //  POST /auth/confirm
    // =====================================================================

    @Test
    void confirm_withCookieFromConfirmPage_completesLogin() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user);

        MvcResult page = mockMvc.perform(get("/auth").param("token", token))
                .andExpect(status().isOk())
                .andReturn();
        String csrf = setCookieValue(page, AccountController.CONFIRM_COOKIE);

        MvcResult confirmed = mockMvc.perform(post("/auth/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", token)
                        .param("csrf", csrf)
                        .cookie(new Cookie(AccountController.CONFIRM_COOKIE, csrf))
                        .header("Sec-Fetch-Site", "same-origin"))
                // 303, а не 302: после POST браузер должен пойти в кабинет методом GET.
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/account/"))
                .andReturn();

        assertThat(sessionCookie(confirmed)).isNotNull();
        assertThat(usedAtOf(token)).isNotNull();
    }

    @Test
    void confirm_withoutCookie_doesNotIssueSessionAndKeepsToken() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user);

        // Ровно то, что может собрать чужой сайт: значение поля есть, cookie нет
        // (SameSite=Lax её в межсайтовый POST не отдаёт).
        MvcResult result = mockMvc.perform(post("/auth/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", token)
                        .param("csrf", "guessed-value"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sessionCookie(result)).isNull();
        assertThat(usedAtOf(token)).isNull();
    }

    @Test
    void confirm_withMismatchedCsrf_doesNotIssueSession() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user);

        MvcResult result = mockMvc.perform(post("/auth/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", token)
                        .param("csrf", "value-from-the-attacker-page")
                        .cookie(new Cookie(AccountController.CONFIRM_COOKIE, "value-of-this-browser")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sessionCookie(result)).isNull();
        assertThat(usedAtOf(token)).isNull();
    }

    @Test
    void confirm_fromCrossSitePost_doesNotIssueSession() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user);
        String csrf = "same-value-in-cookie-and-field";

        MvcResult result = mockMvc.perform(post("/auth/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", token)
                        .param("csrf", csrf)
                        .cookie(new Cookie(AccountController.CONFIRM_COOKIE, csrf))
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(sessionCookie(result)).isNull();
        assertThat(usedAtOf(token)).isNull();
    }

    @Test
    void confirm_withUsedToken_redirectsToExpired() throws Exception {
        Customer user = createUserWithEmail();
        String token = magicLinkService.create(user);
        magicLinkService.consume(token);

        mockMvc.perform(post("/auth/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", token)
                        .param("csrf", "whatever"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/account/expired"));
    }

    // =====================================================================
    //  POST /auth/request — nonce-cookie не должна выдавать наличие аккаунта
    // =====================================================================

    @Test
    void authRequest_setsNonceCookie_forKnownAndUnknownEmailAlike() throws Exception {
        createUserWithEmail();

        MvcResult known = mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.10")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("email", EMAIL))))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult unknown = mockMvc.perform(post("/auth/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        // Другой IP — иначе сработает cooldown лимитера, а не проверка cookie.
                        .header("X-Forwarded-For", "203.0.113.11")
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("email", "nobody@example.com"))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(setCookieValue(known, AccountController.NONCE_COOKIE)).isNotBlank();
        assertThat(setCookieValue(unknown, AccountController.NONCE_COOKIE)).isNotBlank();
        // Значения разные — иначе одна утёкшая cookie подходила бы к чужим ссылкам.
        assertThat(setCookieValue(known, AccountController.NONCE_COOKIE))
                .isNotEqualTo(setCookieValue(unknown, AccountController.NONCE_COOKIE));

        // Атрибуты: без HttpOnly её прочитал бы XSS, без Lax она уехала бы на чужой сайт.
        String raw = rawSetCookie(known, AccountController.NONCE_COOKIE);
        assertThat(raw).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
    }

    // =====================================================================
    //  helpers
    // =====================================================================

    private LocalDateTime usedAtOf(String token) {
        MagicLink link = magicLinkRepository.findByToken(token).orElseThrow();
        return link.getUsedAt();
    }

    private static List<String> setCookies(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    }

    /** Первый Set-Cookie с этим именем целиком (с атрибутами) либо {@code null}. */
    private static String rawSetCookie(MvcResult result, String name) {
        return setCookies(result).stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .orElse(null);
    }

    /** Значение Set-Cookie с этим именем либо {@code null}, если такой cookie в ответе нет. */
    private static String setCookieValue(MvcResult result, String name) {
        String raw = rawSetCookie(result, name);
        if (raw == null) {
            return null;
        }
        String withoutName = raw.substring((name + "=").length());
        int semicolon = withoutName.indexOf(';');
        return semicolon < 0 ? withoutName : withoutName.substring(0, semicolon);
    }

    /** Значение сессионной cookie либо {@code null} — «сессию не выдали». */
    private static String sessionCookie(MvcResult result) {
        String value = setCookieValue(result, AccountSessionService.COOKIE_NAME);
        return value == null || value.isEmpty() ? null : value;
    }
}
