package com.orchestra.subscriptions.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.MagicLinkRepository;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Покрывает POST /api/me/email и DELETE /api/me/email — capture email через Mini App.
 * <p>
 * TelegramInitDataValidator мокаем, чтобы не генерить реальный HMAC-подписанный initData
 * в каждом тесте. Остальное — настоящий Spring-контекст с Postgres через Testcontainers
 * и реальный CustomerRepository; схему в тестах поднимает Hibernate
 * ({@code ddl-auto: create-drop} в src/test/resources/application.yml), не Liquibase.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WebAppEmailApiIntegrationTest {

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
    MagicLinkRateLimiter magicLinkRateLimiter;

    @Autowired
    RequestRateLimiter requestRateLimiter;

    @MockitoBean
    TelegramInitDataValidator validator;

    // Внешние клиенты — стандартный обвес для интеграционных тестов, см. PendingGrantServiceIntegrationTest
    @MockitoBean
    com.orchestra.subscriptions.access.AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;


    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;


    private static final long TELEGRAM_ID = 100200300L;
    private static final String FAKE_INIT_DATA = "auth_date=1&hash=stub";

    @BeforeEach
    void setUp() throws Exception {
        magicLinkRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();
        magicLinkRateLimiter.resetForTesting();
        requestRateLimiter.resetForTesting();

        // Валидатор для нашего «авторизованного» initData возвращает фиксированного юзера.
        // Для невалидных кейсов вызовы перебиваются в самих тестах.
        when(validator.validateAndExtractUserId(FAKE_INIT_DATA)).thenReturn(TELEGRAM_ID);
        when(validator.validateAndExtractUser(FAKE_INIT_DATA))
                .thenReturn(new TelegramInitDataValidator.TelegramUser(TELEGRAM_ID, "tester"));
    }

    private Customer createUser(Long telegramId) {
        return userRepository.save(Customer.builder()
                .telegramId(telegramId)
                .telegramUsername("tester")
                .providerUsername("sub_" + telegramId)
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build());
    }

    /** Запрос с явным согласием — нужен в большинстве success-кейсов. */
    private Map<String, Object> withConsent(String email) {
        return Map.of("email", email, "consent", true);
    }

    // =====================================================================
    //  POST /api/me/email
    // =====================================================================

    @Test
    void post_savesEmail_lowercased_andResetsVerified_setsConsentAt() throws Exception {
        Customer user = createUser(TELEGRAM_ID);
        // Был verified, потом меняем email → должен сброситься
        user.setEmail("old@example.com");
        user.setEmailVerifiedAt(LocalDateTime.now().minusDays(1));
        userRepository.save(user);
        LocalDateTime beforeSave = LocalDateTime.now().minusSeconds(1);

        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withConsent("  New@Example.COM  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false));

        Customer reloaded = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("new@example.com");
        assertThat(reloaded.getEmailVerifiedAt()).isNull();
        // 152-ФЗ audit-trail: при смене email согласие фиксируется заново
        assertThat(reloaded.getEmailConsentAt()).isNotNull().isAfter(beforeSave);
    }

    @Test
    void post_withoutConsent_returns400_consentRequired() throws Exception {
        createUser(TELEGRAM_ID);

        // consent отсутствует в теле
        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "x@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("consent_required"));

        // consent=false тоже не катит
        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "x@example.com", "consent", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("consent_required"));

        Customer reloaded = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(reloaded.getEmail()).isNull();
        assertThat(reloaded.getEmailConsentAt()).isNull();
    }

    @Test
    void post_invalidFormat_returns400() throws Exception {
        createUser(TELEGRAM_ID);

        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_format"));

        assertThat(userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow().getEmail()).isNull();
    }

    @Test
    void post_emptyBody_returns400() throws Exception {
        createUser(TELEGRAM_ID);

        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_format"));
    }

    @Test
    void post_emailTakenByAnother_returns409() throws Exception {
        createUser(TELEGRAM_ID);
        // Второй юзер с уже занятым email
        Customer other = Customer.builder()
                .telegramId(999L)
                .providerUsername("sub_999")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .email("taken@example.com")
                .build();
        userRepository.save(other);

        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withConsent("taken@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("email_taken"));

        assertThat(userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow().getEmail()).isNull();
    }

    @Test
    void post_sameEmailAsCurrent_isNoop_keepsVerification() throws Exception {
        Customer user = createUser(TELEGRAM_ID);
        LocalDateTime verifiedAt = LocalDateTime.now().minusHours(2).withNano(0);
        user.setEmail("kept@example.com");
        user.setEmailVerifiedAt(verifiedAt);
        userRepository.save(user);

        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "KEPT@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("kept@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(true));

        Customer reloaded = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        // verifiedAt не должен быть сброшен на no-op'е
        assertThat(reloaded.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void post_noCustomer_returns404() throws Exception {
        // Customer намеренно не создаём
        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "x@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user_not_found"));
    }

    @Test
    void post_invalidInitData_returns401() throws Exception {
        when(validator.validateAndExtractUserId(any()))
                .thenThrow(new TelegramInitDataValidator.InvalidInitDataException("bad sig"));

        mockMvc.perform(post("/api/me/email")
                        .header("X-Telegram-Init-Data", "garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "x@example.com"))))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    //  DELETE /api/me/email
    // =====================================================================

    @Test
    void delete_clearsEmailVerificationAndConsent() throws Exception {
        Customer user = createUser(TELEGRAM_ID);
        user.setEmail("bye@example.com");
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setEmailConsentAt(LocalDateTime.now());
        userRepository.save(user);

        mockMvc.perform(delete("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(""))
                .andExpect(jsonPath("$.emailVerified").value(false));

        Customer reloaded = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(reloaded.getEmail()).isNull();
        assertThat(reloaded.getEmailVerifiedAt()).isNull();
        // 152-ФЗ: отзыв согласия зануляет и аудит-метку
        assertThat(reloaded.getEmailConsentAt()).isNull();
    }

    @Test
    void delete_whenNoEmail_isIdempotent() throws Exception {
        createUser(TELEGRAM_ID);

        mockMvc.perform(delete("/api/me/email")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(""));
    }

    @Test
    void delete_invalidInitData_returns401() throws Exception {
        when(validator.validateAndExtractUserId(any()))
                .thenThrow(new TelegramInitDataValidator.InvalidInitDataException("bad sig"));

        mockMvc.perform(delete("/api/me/email")
                        .header("X-Telegram-Init-Data", "garbage"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    //  POST /api/me/email/send-link
    // =====================================================================

    @Test
    void sendLink_withSavedEmail_createsMagicLink() throws Exception {
        Customer user = createUser(TELEGRAM_ID);
        user.setEmail("ml@example.com");
        user.setEmailConsentAt(LocalDateTime.now());
        userRepository.save(user);

        mockMvc.perform(post("/api/me/email/send-link")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ml@example.com"))
                .andExpect(jsonPath("$.message").exists());

        assertThat(magicLinkRepository.count()).isEqualTo(1L);
    }

    @Test
    void sendLink_withoutEmail_returns400_noEmail() throws Exception {
        createUser(TELEGRAM_ID); // email = null

        mockMvc.perform(post("/api/me/email/send-link")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("no_email"));

        assertThat(magicLinkRepository.count()).isZero();
    }

    @Test
    void sendLink_consecutive_hitsCooldown_returns429() throws Exception {
        Customer user = createUser(TELEGRAM_ID);
        user.setEmail("cooldown@example.com");
        user.setEmailConsentAt(LocalDateTime.now());
        userRepository.save(user);

        // Первый — успех
        mockMvc.perform(post("/api/me/email/send-link")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA))
                .andExpect(status().isOk());

        // Второй сразу — cooldown
        mockMvc.perform(post("/api/me/email/send-link")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.reason").value("cooldown"));

        // Magic-link создан только один
        assertThat(magicLinkRepository.count()).isEqualTo(1L);
    }

    @Test
    void sendLink_noCustomer_returns404() throws Exception {
        mockMvc.perform(post("/api/me/email/send-link")
                        .header("X-Telegram-Init-Data", FAKE_INIT_DATA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user_not_found"));
    }

    @Test
    void sendLink_invalidInitData_returns401() throws Exception {
        when(validator.validateAndExtractUserId(any()))
                .thenThrow(new TelegramInitDataValidator.InvalidInitDataException("bad sig"));

        mockMvc.perform(post("/api/me/email/send-link")
                        .header("X-Telegram-Init-Data", "garbage"))
                .andExpect(status().isUnauthorized());
    }
}
