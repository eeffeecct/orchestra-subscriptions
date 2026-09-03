package com.orchestra.subscriptions.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestra.subscriptions.bot.PaymentHandler;
import com.orchestra.subscriptions.bot.TelegramSender;
import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.AutoRenewService;
import com.orchestra.subscriptions.service.NotificationService;
import com.orchestra.subscriptions.service.PaymentProcessingService;
import com.orchestra.subscriptions.service.SubscriptionRegistryService;
import com.orchestra.subscriptions.web.api.AccountSessionService;
import com.orchestra.subscriptions.web.api.TelegramInitDataValidator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * У каждого платежа с рождения написано, в какую подписку он идёт — по одному кейсу на каждый
 * из десяти путей создания счёта (три способа оплаты в боте + докупка слота, четыре в Mini App,
 * web-ЛК, автосписание).
 * <p>
 * Проверка именно сквозная, а не на уровне фабрики: цель теряется не в фабрике, а по дороге к
 * ней — в канале, который завёл строку сам. Тест-сторож {@code PaymentFactoryGuardTest}
 * запрещает такой обход структурно, этот — показывает, что каждый живой путь действительно
 * доносит цель до БД.
 * <p>
 * Свой набор тарифов и ступеней: базовый тестовый конфиг не описывает ни ступеней, ни докупки
 * слота, ни цены в Stars — половина путей на нём просто отказалась бы создавать счёт, и тест
 * зеленел бы вхолостую.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.plans[0].id=month_1",
        "app.plans[0].name=1 месяц",
        "app.plans[0].days=30",
        "app.plans[0].price-kopeks=15000",
        "app.plans[0].price-stars=150",
        "app.plans[0].description=Orchestra — 1 месяц",
        "app.device-tiers[0].id=d2",
        "app.device-tiers[0].name=2 устройства",
        "app.device-tiers[0].devices=2",
        "app.device-tiers[0].price-multiplier=1.0",
        "app.device-addon.price-kopeks-per-month=3900",
        "app.device-addon.price-stars-per-month=39",
        "app.device-addon.max-extra-devices=5",
        "app.cryptobot.accepted-assets=USDT,TON",
})
class PaymentTargetingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    PaymentHandler paymentHandler;
    @Autowired
    AutoRenewService autoRenewService;
    @Autowired
    PaymentProcessingService paymentProcessingService;
    @Autowired
    PaymentFactory paymentFactory;
    @Autowired
    SubscriptionRegistryService subscriptionRegistry;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    AccountSessionService sessionService;

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    YooKassaClient yooKassaClient;
    @MockitoBean
    CryptoBotClient cryptoBotClient;
    @MockitoBean
    NotificationService notificationService;
    @MockitoBean
    TelegramSender telegramSender;
    @MockitoBean
    TelegramInitDataValidator validator;

    private static final long TG_ID = 606060606L;
    private static final long CHAT_ID = 606060606L;
    private static final String INIT_DATA = "auth_date=1&hash=stub";
    private static final String YK_ID = "yk-target-test";

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mUser = new AccessProvider.AccessAccount();
        mUser.setSubscriptionUrl("https://test.example.com/sub/target");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mUser);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any())).thenReturn(mUser);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());

        when(yooKassaClient.createPayment(anyLong(), anyString(), anyString(), anyMap(),
                anyString(), anyBoolean())).thenReturn(ykResponse());
        when(cryptoBotClient.createInvoice(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(cryptoResponse());

        when(validator.validateAndExtractUserId(INIT_DATA)).thenReturn(TG_ID);
        when(validator.validateAndExtractUser(INIT_DATA))
                .thenReturn(new TelegramInitDataValidator.TelegramUser(TG_ID, "targettest"));
    }

    // =====================================================================
    //  Бот: три способа оплаты + докупка слота
    // =====================================================================

    @Test
    @DisplayName("Бот · ЮKassa: PENDING нацелен на текущую подписку")
    void botYookassa() throws Exception {
        Subscription current = existingSubscriber();
        paymentHandler.handleYooKassaPayment(CHAT_ID, botUser(), "month_1", "d2", false);
        assertTargets(onlyPayment(), current);
    }

    @Test
    @DisplayName("Бот · CryptoBot: PENDING нацелен на текущую подписку")
    void botCrypto() throws Exception {
        Subscription current = existingSubscriber();
        paymentHandler.handleCryptoPayment(CHAT_ID, botUser(), "month_1", "d2");
        assertTargets(onlyPayment(), current);
    }

    @Test
    @DisplayName("Бот · Stars: PENDING нацелен на текущую подписку")
    void botStars() throws Exception {
        Subscription current = existingSubscriber();
        paymentHandler.handleStarsPayment(CHAT_ID, botUser(), "month_1", "d2");
        assertTargets(onlyPayment(), current);
    }

    @Test
    @DisplayName("Бот · докупка слота: PENDING нацелен на текущую подписку")
    void botAddon() throws Exception {
        Subscription current = existingSubscriber();
        paymentHandler.handleAddonPayment(CHAT_ID, botUser(), "yookassa");
        Payment payment = onlyPayment();
        assertThat(payment.isDeviceAddon()).isTrue();
        assertTargets(payment, current);
    }

    // =====================================================================
    //  Mini App: три способа оплаты + докупка слота
    // =====================================================================

    @Test
    @DisplayName("Mini App · ЮKassa: PENDING нацелен на текущую подписку")
    void miniAppYookassa() throws Exception {
        Subscription current = existingSubscriber();
        postJson("/api/payments/yookassa", Map.of("planId", "month_1", "deviceTierId", "d2"));
        assertTargets(onlyPayment(), current);
    }

    @Test
    @DisplayName("Mini App · CryptoBot: PENDING нацелен на текущую подписку")
    void miniAppCrypto() throws Exception {
        Subscription current = existingSubscriber();
        postJson("/api/payments/cryptobot", Map.of("planId", "month_1", "deviceTierId", "d2"));
        assertTargets(onlyPayment(), current);
    }

    @Test
    @DisplayName("Mini App · Stars: PENDING нацелен на текущую подписку")
    void miniAppStars() throws Exception {
        Subscription current = existingSubscriber();
        postJson("/api/payments/stars", Map.of("planId", "month_1", "deviceTierId", "d2"));
        assertTargets(onlyPayment(), current);
    }

    @Test
    @DisplayName("Mini App · докупка слота: PENDING нацелен на текущую подписку")
    void miniAppAddon() throws Exception {
        Subscription current = existingSubscriber();
        postJson("/api/devices/addon", Map.of("provider", "yookassa"));
        Payment payment = onlyPayment();
        assertThat(payment.isDeviceAddon()).isTrue();
        assertTargets(payment, current);
    }

    // =====================================================================
    //  web-ЛК и автосписание
    // =====================================================================

    @Test
    @DisplayName("web-ЛК · ЮKassa: PENDING нацелен на текущую подписку")
    void accountYookassa() throws Exception {
        Subscription current = existingSubscriber();
        mockMvc.perform(post("/api/account/payments/yookassa")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, sessionService.issue(TG_ID, 0)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("planId", "month_1"))))
                .andExpect(status().isOk());
        assertTargets(onlyPayment(), current);
    }

    @Test
    @DisplayName("Автосписание: платёж нацелен на подписку, работавшую в момент списания")
    void autorenewCharge() {
        Subscription current = existingSubscriber();
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setAutorenewEnabled(true);
        user.setAutorenewMethodId("pm_test");
        user.setAutorenewPlanId("month_1");
        user.setAutorenewDeviceTierId("d2");
        user = userRepository.save(user);
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(ykResponse("succeeded"));

        autoRenewService.chargeAndActivate(user);

        Payment payment = onlyPayment();
        assertTargets(payment, current);
        // Заодно доказывает, что сверка цели в activateInto не оставляет платёж в PENDING.
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
    }

    // =====================================================================
    //  Активация: цель разошлась с текущей подпиской
    // =====================================================================

    /**
     * Цель существует, но заморожена — деньги обязаны лечь именно в неё. Это и есть смысл
     * колонки: человек платил за конкретную подписку, а не за ту, что случайно работает в
     * момент прихода вебхука.
     */
    @Test
    @DisplayName("Цель заморожена: деньги идут в неё, работающая подписка не тронута")
    void targetIsParked_depositsIntoParked() {
        Subscription current = existingSubscriber();
        LocalDateTime currentExpiry = current.getExpiresAt();
        Subscription parked = subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID)
                .seq(2)
                .current(false)
                .state(Customer.Status.ACTIVE)
                .balanceSeconds(0L)
                .providerAttempts(0)
                .createdAt(LocalDateTime.now())
                .build());

        Payment payment = targetedPayment(PaymentTarget.extend(parked));
        paymentProcessingService.handleSucceeded(YK_ID);

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(subscriptionRepository.findById(parked.getId()).orElseThrow().getBalanceSeconds())
                .isEqualTo(30L * 86_400L);
        assertThat(subscriptionRepository.findById(current.getId()).orElseThrow().getExpiresAt())
                .as("работающая подписка чужих дней не получила").isEqualTo(currentExpiry);
    }

    /**
     * А вот подписки с таким id уже нет (удалили руками) — тогда деньги идут в текущую: она
     * единственная, которую человек видит. Платёж при этом НЕ остаётся PENDING.
     */
    @Test
    @DisplayName("Цель — висячий id: деньги идут в текущую, платёж не виснет, владельцу алерт")
    void targetDangling_activatesIntoCurrentAndAlerts() {
        Subscription current = existingSubscriber();
        Subscription ghost = subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID)
                .seq(2)
                .current(false)
                .state(Customer.Status.ACTIVE)
                .balanceSeconds(0L)
                .providerAttempts(0)
                .createdAt(LocalDateTime.now())
                .build());

        Payment payment = targetedPayment(PaymentTarget.extend(ghost));
        subscriptionRepository.deleteById(ghost.getId());

        paymentProcessingService.handleSucceeded(YK_ID);

        Payment processed = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        verify(notificationService).notifyOwnerPaymentTargetAnomaly(
                eq(TG_ID), eq(processed.getId()), anyString());
        // Дни легли в ту подписку, которой человек пользуется.
        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getExpiresAt())
                .isAfter(LocalDateTime.now().plusDays(29));
        assertThat(subscriptionRepository.findById(current.getId()).orElseThrow().isCurrent()).isTrue();
    }

    /** PENDING с явно заданной целью, готовый к приходу вебхука. */
    private Payment targetedPayment(PaymentTarget target) {
        Payment payment = paymentFactory.create(PaymentDraft.builder()
                .telegramId(TG_ID)
                .telegramUsername("targettest")
                .planId("month_1")
                .deviceTierId("d2")
                .amountKopeks(15000L)
                .provider(Payment.Provider.YOOKASSA)
                .build(), target);
        payment.setYookassaPaymentId(YK_ID);
        paymentRepository.save(payment);
        when(yooKassaClient.getPayment(YK_ID)).thenReturn(ykResponse("succeeded"));
        return payment;
    }

    // ===== helpers =====

    /**
     * Человек с активной подпиской и зеркальной строкой — состояние подавляющего большинства
     * плательщиков в проде на момент нажатия «Оплатить».
     */
    private Subscription existingSubscriber() {
        userRepository.save(Customer.builder()
                .telegramId(TG_ID)
                .telegramUsername("targettest")
                .providerUsername("tg_targettest_" + TG_ID)
                .subscriptionLink("https://test.example.com/sub/target")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(20))
                .deviceLimit(2)
                .deviceTierId("d2")
                .email("target@example.com")
                .build());
        subscriptionRegistry.mirrorAfterCommit(TG_ID);
        return subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
    }

    private void assertTargets(Payment payment, Subscription expected) {
        assertThat(payment.getSubscriptionId())
                .as("платёж обязан знать, в какую подписку зачислять")
                .isEqualTo(expected.getId());
        assertThat(payment.getTargetKind()).isEqualTo("EXTEND");
    }

    private Payment onlyPayment() {
        List<Payment> payments = paymentRepository.findAll();
        assertThat(payments).as("этот путь обязан был создать ровно одну строку платежа").hasSize(1);
        return payments.get(0);
    }

    private void postJson(String url, Map<String, ?> body) throws Exception {
        mockMvc.perform(post(url)
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private static User botUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(TG_ID);
        when(user.getUserName()).thenReturn("targettest");
        return user;
    }

    private static YooKassaClient.PaymentResponse ykResponse() {
        return ykResponse("pending");
    }

    private static YooKassaClient.PaymentResponse ykResponse(String status) {
        YooKassaClient.PaymentResponse resp = new YooKassaClient.PaymentResponse();
        resp.setId(YK_ID);
        resp.setStatus(status);
        YooKassaClient.Confirmation confirmation = new YooKassaClient.Confirmation();
        confirmation.setConfirmationUrl("https://yookassa.test/pay");
        resp.setConfirmation(confirmation);
        return resp;
    }

    private static CryptoBotClient.InvoiceResponse cryptoResponse() {
        CryptoBotClient.InvoiceResponse resp = new CryptoBotClient.InvoiceResponse();
        resp.setInvoiceId(777_001L);
        resp.setMiniAppInvoiceUrl("https://t.me/CryptoBot?start=inv");
        return resp;
    }
}
