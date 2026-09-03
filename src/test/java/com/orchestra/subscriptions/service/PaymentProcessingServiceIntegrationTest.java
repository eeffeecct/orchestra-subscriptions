package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Referral;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.ReferralRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Интеграционные тесты webhook-пути: ЮKassa webhook → PaymentProcessingService →
 * активация подписки + чек + уведомление.
 * <p>
 * Это САМЫЙ критичный путь в сервисе — здесь деньги превращаются в подписку. Тесты должны покрывать:
 * happy path, идемпотентность (двойная доставка webhook), защиту от поддельного webhook (ре-верификация),
 * неизвестный платёж и апгрейд с пробного периода на платный.
 */
@SpringBootTest
@Testcontainers
class PaymentProcessingServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PaymentProcessingService paymentProcessingService;

    @Autowired
    AccessService accessService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    ReferralRepository referralRepository;

    @MockitoBean
    AccessProvider accessProvider;

    @MockitoBean
    YooKassaClient yooKassaClient;

    @MockitoBean
    CryptoBotClient cryptoBotClient;


    // Мокаем NotificationService, чтобы не тащить реальный SubscriptionBot и TelegramLongPollingBot
    @MockitoBean
    NotificationService notificationService;

    private static final Long TELEGRAM_ID = 111222333L;
    private static final String TELEGRAM_USERNAME = "paytest";
    private static final String YK_PAYMENT_ID = "2f0c7a5e-000f-5000-8000-1234567890ab";
    private static final String CRYPTO_INVOICE_ID = "528890";
    private static final String PLAN_ID = "month_1";
    private static final long PRICE_KOPEKS = 15000L;

    @BeforeEach
    void setUp() {
        referralRepository.deleteAll();
        paymentRepository.deleteAll();
        customerRepository.deleteAll();

        AccessProvider.AccessAccount mockAccessAccount = new AccessProvider.AccessAccount();
        mockAccessAccount.setSubscriptionUrl("https://test.example.com/sub/abc123");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mockAccessAccount);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());

    }

    private Payment insertPendingPayment() {
        return paymentRepository.save(Payment.builder()
                .yookassaPaymentId(YK_PAYMENT_ID)
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID)
                .amountKopeks(PRICE_KOPEKS)
                .status(Payment.Status.PENDING)
                .build());
    }

    private YooKassaClient.PaymentResponse apiSaid(String status) {
        YooKassaClient.PaymentResponse r = new YooKassaClient.PaymentResponse();
        r.setId(YK_PAYMENT_ID);
        r.setStatus(status);
        return r;
    }

    /**
     * Матчер: AccessProvider получил РОВНО тот момент истечения, что лежит в {@code expires_at}.
     * Раньше туда уходило целое число суток «от сейчас» — округление вниз убивало подписку
     * на стороне панели раньше, чем истекала наша (юзер видел «осталось 5 часов» при уже
     * мёртвом доступе). Допуск 1с — БД режет доли секунды.
     */
    private static java.time.Instant sameMomentAs(LocalDateTime dbExpiry) {
        long expected = dbExpiry.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        return argThat(actual -> actual != null
                && Math.abs(actual.getEpochSecond() - expected) <= 1);
    }

    // ========================
    // Докупка «+1 устройство» (planId = device_addon)
    // ========================

    @Test
    void handleSucceeded_deviceAddon_incrementsLimitWithoutExtendingSubscription() {
        // Юзер с активной подпиской и ступенью d2 (лимит 2)
        Customer user = customerRepository.save(Customer.builder()
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .providerUsername("tg_paytest_111222333")
                .subscriptionLink("https://test.example.com/sub/abc123")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(20))
                .deviceLimit(2)
                .deviceTierId("d2")
                .build());
        LocalDateTime expiryBefore = user.getExpiresAt();

        paymentRepository.save(Payment.builder()
                .yookassaPaymentId(YK_PAYMENT_ID)
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId(Payment.DEVICE_ADDON_PLAN_ID)
                .amountKopeks(2600L) // 39₽/мес pro-rata на 20 дней
                .status(Payment.Status.PENDING)
                .build());
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Payment payment = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);

        Customer updated = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        // Лимит вырос, срок подписки НЕ изменился (сравнение с допуском: Postgres хранит
        // микросекунды, in-memory LocalDateTime — наносекунды)
        assertThat(updated.getDeviceLimit()).isEqualTo(3);
        assertThat(updated.getExpiresAt()).isCloseTo(expiryBefore,
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
        // AccessProvider ip_limit пересинкан per-user overload'ом (devices+1 = 4), срок не тронут
        verify(accessProvider).enableUserUntil(
                eq("tg_paytest_111222333"), sameMomentAs(updated.getExpiresAt()), eq(4));
        // Чек на фактическую сумму, DM юзеру
        verify(notificationService).sendPlainText(eq(TELEGRAM_ID), contains("Слот устройства добавлен"));
    }

    @Test
    void handleSucceeded_deviceAddon_idempotentOnDuplicateWebhook() {
        customerRepository.save(Customer.builder()
                .telegramId(TELEGRAM_ID)
                .providerUsername("tg_paytest_111222333")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(20))
                .deviceLimit(2)
                .deviceTierId("d2")
                .build());
        paymentRepository.save(Payment.builder()
                .yookassaPaymentId(YK_PAYMENT_ID)
                .telegramId(TELEGRAM_ID)
                .planId(Payment.DEVICE_ADDON_PLAN_ID)
                .amountKopeks(2600L)
                .status(Payment.Status.PENDING)
                .build());
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID); // дубль доставки

        Customer updated = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(updated.getDeviceLimit()).isEqualTo(3); // +1, не +2
    }

    // ========================
    // Happy path
    // ========================

    @Test
    void handleSucceeded_shouldActivateNewUserAndNotify() {
        insertPendingPayment();
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Payment payment = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(payment.getPaidAt()).isNotNull();

        Customer customer = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(customer.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(customer.getProviderUsername()).isEqualTo("tg_paytest_111222333");
        assertThat(customer.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29), LocalDateTime.now().plusDays(31));

        verify(accessProvider).createUser(eq("tg_paytest_111222333"), eq(30), eq(0L));
        verify(notificationService).sendPaymentSuccess(
                eq(TELEGRAM_ID), any(Customer.class), any());
    }

    /**
     * Регрессия: при оплате с промокодом чек МойНалог должен выписываться на ФАКТИЧЕСКИ
     * полученную сумму (payment.amountKopeks), а не на исходную цену плана (plan.priceKopeks).
     * <p>
     * Регрессия: код брал plan.priceKopeks, и при скидке 50% в отчётность уходила полная цена
     * тарифа — сумма не сходилась ни с фактическим списанием, ни с выпиской эквайринга.
     */
    @Test
    void handleSucceeded_shouldRecordPaidAmount_whenPromoApplied() {
        long paidAfterPromoKopeks = PRICE_KOPEKS / 2; // 50% промо: 15000 → 7500
        paymentRepository.save(Payment.builder()
                .yookassaPaymentId(YK_PAYMENT_ID)
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID)
                .amountKopeks(paidAfterPromoKopeks)
                .status(Payment.Status.PENDING)
                .build());
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // Главное: чек на ту сумму, которую клиент реально заплатил, не на полную цену тарифа.
    }

    // ========================
    // Идемпотентность (двойная доставка webhook)
    // ========================

    @Test
    void handleSucceeded_shouldBeIdempotent_secondCallIsNoOp() {
        insertPendingPayment();
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // Сброс счётчиков ПОСЛЕ первого (легитимного) вызова
        reset(accessProvider, notificationService);

        // Вторая доставка того же webhook — должна ничего не делать:
        // ни вторично не активировать AccessProvider, ни дублировать чек, ни слать повторное уведомление.
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);

        // В БД остаётся ровно один пользователь
        assertThat(customerRepository.findAll()).hasSize(1);
    }

    // ========================
    // Автопродление следует последней покупке
    // ========================

    /**
     * Регрессия на реальный вопрос владельца: юзер купил «1 месяц» с галочкой автопродления,
     * потом «3 месяца» БЕЗ галочки (в чате её нет вовсе, в Mini App выключена по умолчанию).
     * Раньше autorenew_plan_id оставался month_1 → через 3 месяца карта списывала месяц
     * вместо трёх, а вместе со ступенью откатывался и лимит устройств.
     */
    @Test
    void handleSucceeded_withoutSavedCard_updatesAutorenewSelectionToLastPurchase() {
        customerRepository.save(Customer.builder()
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .providerUsername("tg_paytest_111222333")
                .subscriptionLink("https://test.example.com/sub/abc123")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(5))
                // Прошлая покупка с галочкой: месяц, 2 устройства, карта привязана
                .autorenewMethodId("pm_saved_card")
                .autorenewEnabled(true)
                .autorenewPlanId("month_1")
                .autorenewDeviceTierId("d2")
                .deviceLimit(2)
                .deviceTierId("d2")
                .build());

        // Новая покупка: 3 месяца, семейная ×5 — БЕЗ сохранения карты
        paymentRepository.save(Payment.builder()
                .yookassaPaymentId(YK_PAYMENT_ID)
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId("month_3")
                .deviceTierId("f5")
                .amountKopeks(86500L)
                .status(Payment.Status.PENDING)
                .build());
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Customer updated = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        // Автопродление теперь продлит ПОСЛЕДНЮЮ покупку — 3 месяца × семейная
        assertThat(updated.getAutorenewPlanId()).isEqualTo("month_3");
        assertThat(updated.getAutorenewDeviceTierId()).isEqualTo("f5");
        // Карта и пользовательский toggle не тронуты
        assertThat(updated.getAutorenewMethodId()).isEqualTo("pm_saved_card");
        assertThat(updated.isAutorenewEnabled()).isTrue();
    }

    @Test
    void handleSucceeded_withoutCard_doesNotCreateAutorenewOutOfThinAir() {
        // Нет привязанной карты → оплата не должна «включать» автопродление
        insertPendingPayment();
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Customer user = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(user.getAutorenewMethodId()).isNull();
        assertThat(user.isAutorenewEnabled()).isFalse();
        assertThat(user.getAutorenewPlanId()).isNull();
    }

    // ========================
    // Защита от поддельного webhook (ре-верификация)
    // ========================

    @Test
    void handleSucceeded_shouldRejectIfApiStatusDoesNotMatch() {
        insertPendingPayment();
        // Webhook говорит succeeded, но API ЮKassa говорит canceled —
        // сценарий поддельного webhook или рассинхрона. Активации быть не должно.
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("canceled"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Payment payment = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PENDING);
        assertThat(customerRepository.findByTelegramId(TELEGRAM_ID)).isEmpty();
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleSucceeded_shouldRejectIfApiReturnsNull() {
        insertPendingPayment();
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(null);

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Payment payment = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PENDING);
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    // ========================
    // Edge cases
    // ========================

    @Test
    void handleSucceeded_shouldIgnoreUnknownPayment() {
        when(yooKassaClient.getPayment(anyString())).thenReturn(apiSaid("succeeded"));

        // Неизвестный webhook id — не должен падать, просто лог warn
        paymentProcessingService.handleSucceeded("unknown-payment-id");

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    // ========================
    // Trial → paid upgrade через webhook
    // ========================

    @Test
    void handleSucceeded_shouldUpgradeTrialToPaid() {
        // Юзер на триале
        accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME);
        insertPendingPayment();
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));

        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Customer user = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        // Имя в AccessProvider остаётся trial_*, но expiry заменяется на now + 30 дней
        // (оставшиеся дни триала «растворяются», не стекаются)
        assertThat(user.getProviderUsername()).isEqualTo("trial_paytest_111222333");
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29), LocalDateTime.now().plusDays(31));
        verify(accessProvider).enableUserUntil(
                eq("trial_paytest_111222333"), sameMomentAs(user.getExpiresAt()), isNull());
        verify(notificationService).sendPaymentSuccess(
                eq(TELEGRAM_ID), any(Customer.class), any());
    }

    // ========================
    // handleCanceled
    // ========================

    @Test
    void handleCanceled_shouldMarkPendingAsCanceled() {
        insertPendingPayment();
        // handleCanceled теперь ре-верифицирует статус через API (webhook не аутентифицирован).
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("canceled"));

        paymentProcessingService.handleCanceled(YK_PAYMENT_ID);

        Payment payment = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.CANCELED);
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleCanceled_shouldNotCancelWhenApiStillPending() {
        insertPendingPayment();
        // Спуфнутый/преждевременный webhook: API говорит платёж ещё не отменён → не трогаем,
        // оставляем PENDING для реконсилера (иначе атакующий выбил бы платёж из recovery-набора).
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("pending"));

        paymentProcessingService.handleCanceled(YK_PAYMENT_ID);

        Payment payment = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PENDING);
    }

    @Test
    void handleCanceled_shouldNotDowngradeSucceeded() {
        // Сначала успешно обработали
        insertPendingPayment();
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(apiSaid("succeeded"));
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // Потом по какой-то причине прилетел canceled (гонка, баг на стороне YooKassa и т.д.)
        paymentProcessingService.handleCanceled(YK_PAYMENT_ID);

        // Статус остаётся SUCCEEDED — юзер не должен потерять уже выданную подписку
        Payment payment = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
    }

    @Test
    void handleCanceled_shouldIgnoreUnknownPayment() {
        // Не должен падать
        paymentProcessingService.handleCanceled("totally-unknown-id");

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    // ========================================================================
    // CryptoBot (Crypto Pay API)
    // Ключевое отличие от YooKassa-кейсов: чек в "Мой налог" НЕ создаётся.
    // ========================================================================

    private Payment insertPendingCryptoPayment() {
        return paymentRepository.save(Payment.builder()
                .cryptoInvoiceId(CRYPTO_INVOICE_ID)
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID)
                .amountKopeks(PRICE_KOPEKS)
                .status(Payment.Status.PENDING)
                .provider(Payment.Provider.CRYPTOBOT)
                .build());
    }

    private CryptoBotClient.InvoiceResponse cryptoApiSaid(String status) {
        CryptoBotClient.InvoiceResponse r = new CryptoBotClient.InvoiceResponse();
        r.setInvoiceId(Long.valueOf(CRYPTO_INVOICE_ID));
        r.setStatus(status);
        r.setPaidAsset("USDT");
        r.setPaidAmount("1.58");
        return r;
    }

    @Test
    void handleCryptoSucceeded_shouldActivateNewUserAndNotify_withoutReceipt() {
        insertPendingCryptoPayment();
        when(cryptoBotClient.getInvoice(CRYPTO_INVOICE_ID)).thenReturn(cryptoApiSaid("paid"));

        paymentProcessingService.handleCryptoSucceeded(CRYPTO_INVOICE_ID);

        Payment payment = paymentRepository.findByCryptoInvoiceId(CRYPTO_INVOICE_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getProvider()).isEqualTo(Payment.Provider.CRYPTOBOT);

        Customer customer = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(customer.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(customer.getProviderUsername()).isEqualTo("tg_paytest_111222333");
        assertThat(customer.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29), LocalDateTime.now().plusDays(31));

        verify(accessProvider).createUser(eq("tg_paytest_111222333"), eq(30), eq(0L));
        // Главный инвариант крипто-пути: чек НЕ создаётся.
        // receiptUrl = null, но уведомление всё равно уходит
        verify(notificationService).sendPaymentSuccess(
                eq(TELEGRAM_ID), any(Customer.class), any());
    }

    @Test
    void handleCryptoSucceeded_shouldBeIdempotent_secondCallIsNoOp() {
        insertPendingCryptoPayment();
        when(cryptoBotClient.getInvoice(CRYPTO_INVOICE_ID)).thenReturn(cryptoApiSaid("paid"));

        paymentProcessingService.handleCryptoSucceeded(CRYPTO_INVOICE_ID);

        reset(accessProvider, notificationService);

        paymentProcessingService.handleCryptoSucceeded(CRYPTO_INVOICE_ID);

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);

        assertThat(customerRepository.findAll()).hasSize(1);
    }

    @Test
    void handleCryptoSucceeded_shouldRejectIfApiStatusNotPaid() {
        insertPendingCryptoPayment();
        // Webhook "invoice_paid", но API говорит active/expired — активации быть не должно.
        when(cryptoBotClient.getInvoice(CRYPTO_INVOICE_ID)).thenReturn(cryptoApiSaid("active"));

        paymentProcessingService.handleCryptoSucceeded(CRYPTO_INVOICE_ID);

        Payment payment = paymentRepository.findByCryptoInvoiceId(CRYPTO_INVOICE_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PENDING);
        assertThat(customerRepository.findByTelegramId(TELEGRAM_ID)).isEmpty();
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleCryptoSucceeded_shouldRejectIfApiReturnsNull() {
        insertPendingCryptoPayment();
        when(cryptoBotClient.getInvoice(CRYPTO_INVOICE_ID)).thenReturn(null);

        paymentProcessingService.handleCryptoSucceeded(CRYPTO_INVOICE_ID);

        Payment payment = paymentRepository.findByCryptoInvoiceId(CRYPTO_INVOICE_ID).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PENDING);
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleCryptoSucceeded_shouldIgnoreUnknownInvoice() {
        when(cryptoBotClient.getInvoice(anyString())).thenReturn(cryptoApiSaid("paid"));

        paymentProcessingService.handleCryptoSucceeded("unknown-invoice");

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleCryptoSucceeded_shouldUpgradeTrialToPaid_withoutReceipt() {
        accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME);
        insertPendingCryptoPayment();
        when(cryptoBotClient.getInvoice(CRYPTO_INVOICE_ID)).thenReturn(cryptoApiSaid("paid"));

        paymentProcessingService.handleCryptoSucceeded(CRYPTO_INVOICE_ID);

        Customer user = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(user.getProviderUsername()).isEqualTo("trial_paytest_111222333");
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29), LocalDateTime.now().plusDays(31));
        verify(accessProvider).enableUserUntil(
                eq("trial_paytest_111222333"), sameMomentAs(user.getExpiresAt()), isNull());
        // Чек не создаётся даже при апгрейде триала через крипту.
        verify(notificationService).sendPaymentSuccess(
                eq(TELEGRAM_ID), any(Customer.class), any());
    }

    // ========================================================================
    // Telegram Stars
    // Как и для крипты — чек "Мой налог" НЕ создаётся (Stars идут через Fragment/TON).
    // Ищем платёж по id (payload из sendInvoice), а не по telegramChargeId — chargeId
    // это аудит-поле для возможных рефандов.
    // ========================================================================

    private static final String STARS_CHARGE_ID = "tg_ch_stars_abc123";
    private static final long STARS_AMOUNT = 180L;

    private Payment insertPendingStarsPayment() {
        return paymentRepository.save(Payment.builder()
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID)
                .amountKopeks(PRICE_KOPEKS)
                .status(Payment.Status.PENDING)
                .provider(Payment.Provider.STARS)
                .build());
    }

    @Test
    void handleStarsSucceeded_shouldActivateNewUserAndNotify_withoutReceipt() {
        Payment p = insertPendingStarsPayment();

        paymentProcessingService.handleStarsSucceeded(p.getId().toString(), STARS_CHARGE_ID, STARS_AMOUNT);

        Payment payment = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getProvider()).isEqualTo(Payment.Provider.STARS);
        assertThat(payment.getTelegramChargeId()).isEqualTo(STARS_CHARGE_ID);

        Customer customer = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(customer.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(customer.getProviderUsername()).isEqualTo("tg_paytest_111222333");

        verify(accessProvider).createUser(eq("tg_paytest_111222333"), eq(30), eq(0L));
        verify(notificationService).sendPaymentSuccess(
                eq(TELEGRAM_ID), any(Customer.class), any());
    }

    @Test
    void handleStarsSucceeded_shouldBeIdempotent_secondCallIsNoOp() {
        Payment p = insertPendingStarsPayment();

        paymentProcessingService.handleStarsSucceeded(p.getId().toString(), STARS_CHARGE_ID, STARS_AMOUNT);
        reset(accessProvider, notificationService);

        paymentProcessingService.handleStarsSucceeded(p.getId().toString(), STARS_CHARGE_ID, STARS_AMOUNT);

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
        assertThat(customerRepository.findAll()).hasSize(1);
    }

    @Test
    void handleStarsSucceeded_shouldIgnoreNonStarsProvider() {
        // successful_payment с payload, указывающим на YooKassa-платёж — странная ситуация,
        // но мы должны защититься: Stars-код не должен активировать другой тип платежа.
        Payment yk = insertPendingPayment();

        paymentProcessingService.handleStarsSucceeded(yk.getId().toString(), STARS_CHARGE_ID, STARS_AMOUNT);

        Payment reloaded = paymentRepository.findById(yk.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Payment.Status.PENDING);
        assertThat(reloaded.getTelegramChargeId()).isNull();
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleStarsSucceeded_shouldIgnoreNonNumericPayload() {
        paymentProcessingService.handleStarsSucceeded("not-a-number", STARS_CHARGE_ID, STARS_AMOUNT);

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleStarsSucceeded_shouldIgnoreUnknownPaymentId() {
        paymentProcessingService.handleStarsSucceeded("999999999", STARS_CHARGE_ID, STARS_AMOUNT);

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleStarsSucceeded_shouldUpgradeTrialToPaid_withoutReceipt() {
        accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME);
        Payment p = insertPendingStarsPayment();

        paymentProcessingService.handleStarsSucceeded(p.getId().toString(), STARS_CHARGE_ID, STARS_AMOUNT);

        Customer user = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(user.getProviderUsername()).isEqualTo("trial_paytest_111222333");
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29), LocalDateTime.now().plusDays(31));
        verify(accessProvider).enableUserUntil(
                eq("trial_paytest_111222333"), sameMomentAs(user.getExpiresAt()), isNull());
        verify(notificationService).sendPaymentSuccess(
                eq(TELEGRAM_ID), any(Customer.class), any());
    }

    // ========================
    // handleRefundSucceeded
    // ========================

    @Test
    void handleRefundSucceeded_shouldDisableWhenNoDaysLeft() {
        // Юзер купил 1 месяц, сразу делает возврат — дней не осталось → отключить.
        insertPendingPayment(); // PENDING
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        reset(notificationService); // сбрасываем "платёж успешен"
        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_abc");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_abc")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_abc", YK_PAYMENT_ID, "150.00"));

        Customer user = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(Customer.Status.DISABLED);
        // Регрессия: expires_at должен быть обнулён при полном refund'е, иначе /status показывает
        // «❌ Неактивна, Действует до: 26 мая» — visual inconsistency. До фикса оставалось
        // старое значение (paid_at + 30 дней), потому что disableAccess не трогает expires_at.
        assertThat(user.getExpiresAt()).isNull();
        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(p.getRefundedAt()).isNotNull();
        assertThat(p.getRefundAmountKopeks()).isEqualTo(15000L);
        verify(accessProvider).disableUser("tg_paytest_111222333");
        verify(notificationService).sendRefundDisabled(eq(TELEGRAM_ID), any(), anyLong());
    }

    @Test
    void handleRefundSucceeded_shouldReduceExpiryForStackedPayments() {
        // Юзер купил 1 мес, стекнул 3 мес, возвращает 1 мес — должна остаться активной (~90 дней).
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // Второй платёж (3 мес), тоже SUCCEEDED.
        paymentRepository.save(Payment.builder()
                .telegramId(TELEGRAM_ID).telegramUsername(TELEGRAM_USERNAME)
                .provider(Payment.Provider.YOOKASSA)
                .yookassaPaymentId("yk_pay_second")
                .planId("month_3").amountKopeks(40000L)
                .status(Payment.Status.PENDING)
                .build());
        YooKassaClient.PaymentResponse secondResp = new YooKassaClient.PaymentResponse();
        secondResp.setId("yk_pay_second");
        secondResp.setStatus("succeeded");
        when(yooKassaClient.getPayment("yk_pay_second")).thenReturn(secondResp);
        paymentProcessingService.handleSucceeded("yk_pay_second");

        // Возврат первого платежа (30 дней).
        reset(notificationService);
        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_abc");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_abc")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_abc", YK_PAYMENT_ID, "150.00"));

        Customer user = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        // Было ~120 дней (30+90), возврат -30 → ~90 дней осталось.
        assertThat(user.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(89), LocalDateTime.now().plusDays(91));
        verify(notificationService).sendRefundReduced(eq(TELEGRAM_ID), any(), any(), anyLong());
    }

    @Test
    void handleRefundSucceeded_shouldBeIdempotent() {
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);
        reset(notificationService);

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_abc");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_abc")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_abc", YK_PAYMENT_ID, "150.00"));
        // Повторная доставка webhook — не должна ничего сделать.
        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_abc", YK_PAYMENT_ID, "150.00"));

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        // notif отправлен только один раз
        verify(notificationService).sendRefundDisabled(eq(TELEGRAM_ID), any(), anyLong());
    }

    @Test
    void handleRefundSucceeded_shouldRejectIfApiStatusMismatch() {
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);
        reset(notificationService);

        YooKassaClient.RefundResponse pendingRefund = new YooKassaClient.RefundResponse();
        pendingRefund.setId("ref_abc");
        pendingRefund.setStatus("pending");
        pendingRefund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_abc")).thenReturn(pendingRefund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_abc", YK_PAYMENT_ID, "150.00"));

        // Платёж не помечен как REFUNDED, юзер не отключён.
        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.ACTIVE);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleRefundSucceeded_shouldIgnoreUnknownPayment() {
        paymentProcessingService.handleRefundSucceeded(
                refundObjectMap("ref_xyz", "yk_unknown_payment", "150.00"));

        // Ничего не поменялось
        assertThat(paymentRepository.count()).isZero();
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleRefundSucceeded_shouldRejectWebhookWithoutId() {
        // Атакующий, знающий payment_id, мог бы залить webhook без поля id (id —
        // обязательный атрибут refund-объекта YK, но если эндпоинт публичный, кто-то
        // может попробовать). Без id мы НЕ можем re-verify через getRefund, поэтому
        // отказываем — иначе атакующий отключал бы чужие подписки.
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);
        reset(notificationService, yooKassaClient);

        // Refund-объект БЕЗ поля "id"
        java.util.Map<String, Object> forged = refundObjectMap(null, YK_PAYMENT_ID, "150.00");
        forged.remove("id");
        paymentProcessingService.handleRefundSucceeded(forged);

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.ACTIVE);
        // getRefund вообще не должен был быть вызван — раннний reject
        verifyNoInteractions(yooKassaClient);
        verifyNoInteractions(notificationService);
    }

    /**
     * Регрессия на ПОРЯДОК шагов возврата: ступень устройств должна откатиться ДО записи в
     * AccessProvider, иначе в панель уезжает ещё не откаченный ip_limit и там остаётся навсегда —
     * {@code rollbackDeviceTierAfterRefund} правит только БД и повторно AccessProvider не синкает.
     * Здесь возвращается единственный платёж со ступенью, поэтому лимит откатывается «в никуда»
     * (device_limit → null), и в AccessProvider обязан уехать глобальный лимит (null), а не 5+1.
     */
    @Test
    void handleRefundSucceeded_shouldRollBackDeviceTierBeforeSyncingProvider() {
        customerRepository.save(Customer.builder()
                .telegramId(TELEGRAM_ID).telegramUsername(TELEGRAM_USERNAME)
                .providerUsername("tg_paytest_111222333")
                .subscriptionLink("https://example.com/sub/tok")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(200))
                .deviceLimit(5).deviceTierId("f5")
                .build());
        paymentRepository.save(Payment.builder()
                .yookassaPaymentId(YK_PAYMENT_ID)
                .telegramId(TELEGRAM_ID).telegramUsername(TELEGRAM_USERNAME)
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").deviceTierId("f5").amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED).paidAt(LocalDateTime.now())
                .build());
        clearInvocations(accessProvider);

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_tier");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_tier")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(
                refundObjectMap("ref_tier", YK_PAYMENT_ID, "150.00"));

        // Срок урезан, но дни остались — значит шли через setExpiry (а не disableAccess).
        Customer after = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(after.getDeviceLimit()).isNull();
        // Ключевое: в AccessProvider ушёл уже откаченный лимит, а не 6 (=5+1).
        verify(accessProvider).enableUserUntil(anyString(), any(), isNull());
        verify(accessProvider, never()).enableUserUntil(anyString(), any(), eq(6));
    }

    @Test
    void handleRefundSucceeded_shouldRejectRefundBelongingToAnotherPayment() {
        // Re-verify раньше подтверждала только «такой возврат существует и он succeeded», но не
        // то, что он относится к УКАЗАННОМУ платежу. Атакующий с любым своим валидным refund_id
        // мог подставить в payment_id чужой платёж и выключить чужую подписку (+ отвязать карту,
        // откатить реф-бонусы). Теперь связка проверяется явно.
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);
        reset(notificationService);

        // Возврат настоящий и succeeded, но он про ДРУГОЙ платёж.
        YooKassaClient.RefundResponse foreign = new YooKassaClient.RefundResponse();
        foreign.setId("ref_attacker_own");
        foreign.setStatus("succeeded");
        foreign.setPaymentId("yk_some_other_payment");
        when(yooKassaClient.getRefund("ref_attacker_own")).thenReturn(foreign);

        paymentProcessingService.handleRefundSucceeded(
                refundObjectMap("ref_attacker_own", YK_PAYMENT_ID, "150.00"));

        // Жертва не тронута: платёж успешен, подписка активна, уведомлений нет.
        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.ACTIVE);
        verifyNoInteractions(notificationService);
        verify(accessProvider, never()).disableUser(anyString());
    }

    /**
     * Сумма возврата берётся из подтверждённого API-ответа, а не из неподписанного вебхука:
     * иначе отправитель вебхука мог назвать любую сумму (она попадает и в БД, и в текст юзеру).
     */
    @Test
    void handleRefundSucceeded_shouldUseApiAmountNotWebhookAmount() {
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);
        reset(notificationService);

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_amount");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        YooKassaClient.Amount amount = new YooKassaClient.Amount();
        amount.setValue("150.00");
        refund.setAmount(amount);
        when(yooKassaClient.getRefund("ref_amount")).thenReturn(refund);

        // Вебхук врёт про сумму — 1 копейка.
        paymentProcessingService.handleRefundSucceeded(
                refundObjectMap("ref_amount", YK_PAYMENT_ID, "0.01"));

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getRefundAmountKopeks()).isEqualTo(15000L);
    }

    @Test
    void handleRefundSucceeded_shouldRejectWhenReVerifyApiThrows() {
        // Раньше при exception от getRefund мы шли «proceeding with webhook data» и применяли
        // refund — вектор атаки через подбор невалидного, но валидно-выглядящего refundId.
        // Сейчас explicit reject.
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);
        reset(notificationService);

        when(yooKassaClient.getRefund("ref_unknown"))
                .thenThrow(new RuntimeException("YK 404"));

        paymentProcessingService.handleRefundSucceeded(
                refundObjectMap("ref_unknown", YK_PAYMENT_ID, "150.00"));

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.ACTIVE);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleRefundSucceeded_shouldClearAutorenewWhenCardPaymentRefunded() {
        // Юзер оплатил с save_payment_method=true, у него в Customer появился autorenew_method_id.
        YooKassaClient.PaymentResponse paidWithMethod = new YooKassaClient.PaymentResponse();
        paidWithMethod.setId(YK_PAYMENT_ID);
        paidWithMethod.setStatus("succeeded");
        YooKassaClient.PaymentMethod method = new YooKassaClient.PaymentMethod();
        method.setId("pm_saved_123");
        method.setSaved(true);
        method.setType("bank_card");
        paidWithMethod.setPaymentMethod(method);
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(paidWithMethod);

        insertPendingPayment();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        Customer afterPay = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(afterPay.getAutorenewMethodId()).isEqualTo("pm_saved_123");
        assertThat(afterPay.isAutorenewEnabled()).isTrue();

        // Возврат → autorenew должен стереться.
        reset(notificationService);
        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_abc");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_abc")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_abc", YK_PAYMENT_ID, "150.00"));

        Customer afterRefund = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(afterRefund.getAutorenewMethodId()).isNull();
        assertThat(afterRefund.getAutorenewPlanId()).isNull();
        assertThat(afterRefund.isAutorenewEnabled()).isFalse();
    }

    @Test
    void handleRefundSucceeded_shouldIgnoreMissingPaymentId() {
        java.util.Map<String, Object> refundWithoutPaymentId = new java.util.HashMap<>();
        refundWithoutPaymentId.put("id", "ref_no_pay");
        refundWithoutPaymentId.put("status", "succeeded");
        // no payment_id

        paymentProcessingService.handleRefundSucceeded(refundWithoutPaymentId);

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    // ========================
    // Referral bonus rollback on refund
    // Bug: +14/+5 дней оставались после refund — см. changeset 008.
    // ========================

    @Test
    void handleRefundSucceeded_shouldRevertRefereeAndReferrerBonuses() {
        // 1) Оплата B — активирует подписку на 30 дней + начисляет +5 реферии / +14 рефереру.
        Long referrerId = 900_001L;
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        insertReferral(referrerId, TELEGRAM_ID);
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // Зафиксируем состояние до refund: B ~35 дней, A ~14 дней.
        Customer bBefore = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        Customer aBefore = customerRepository.findByTelegramId(referrerId).orElseThrow();
        assertThat(bBefore.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(34), LocalDateTime.now().plusDays(36));
        assertThat(aBefore.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(13), LocalDateTime.now().plusDays(15));

        // 2) Refund первой (и единственной) оплаты.
        reset(notificationService);
        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_ref_test");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_ref_test")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_ref_test", YK_PAYMENT_ID, "150.00"));

        // 3) B имел 35 дней (30 plan + 5 bonus), refund -30 → 5 дней, -5 bonus → 0 → disable.
        Customer bAfter = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(bAfter.getStatus()).isEqualTo(Customer.Status.DISABLED);
        // 4) A имел 14 дней бонуса, -14 → 0 → disable.
        Customer aAfter = customerRepository.findByTelegramId(referrerId).orElseThrow();
        assertThat(aAfter.getStatus()).isEqualTo(Customer.Status.DISABLED);

        Referral updated = referralRepository.findByReferredTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(updated.getBonusRevertedAt()).isNotNull();
    }

    @Test
    void handleRefundSucceeded_shouldBeIdempotentForReferralRevert() {
        // Двойная доставка refund-webhook: бонусы не должны откатиться дважды.
        Long referrerId = 900_002L;
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        insertReferral(referrerId, TELEGRAM_ID);
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_idemp");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_idemp")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_idemp", YK_PAYMENT_ID, "150.00"));
        // Второй раз — тот же webhook.
        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_idemp", YK_PAYMENT_ID, "150.00"));

        // Referral.bonus_reverted_at проставлен ровно один раз — второй вызов должен увидеть
        // его и никого повторно не трогать. Проверяем, что Customer-ы не ушли в отрицательные дни
        // (expires_at был бы дальше в прошлое, но наш код clamp-ит к disabled на первом же
        // отрицательном результате). Главное — Referral помечен.
        Referral updated = referralRepository.findByReferredTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(updated.getBonusRevertedAt()).isNotNull();

        // Customer-ы disabled после первого refund — второй раз disableAccess был бы безопасным no-op,
        // но мы хотим проверить, что код вообще не пошёл в revertBonusForUser на втором вызове.
        // Косвенная проверка: если бы пошёл, он бы лог-warn не кинул — но expires_at всё равно
        // не должен превратиться в странное прошлое-далёкое.
        Customer referrer = customerRepository.findByTelegramId(referrerId).orElseThrow();
        assertThat(referrer.getStatus()).isEqualTo(Customer.Status.DISABLED);
    }

    @Test
    void handleRefundSucceeded_shouldReduceExpiryWhenBonusLeavesDaysRemaining() {
        // Если у реферера после вычитания бонуса остаются дни — clamp to now не нужен,
        // просто уменьшаем expires_at. Сценарий: у A уже была 30-дневная подписка, +14 за реферала,
        // итого 44. После -14 должно остаться ~30.
        Long referrerId = 900_003L;
        accessService.activatePaidSubscription(referrerId, "alice", 30);

        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        insertReferral(referrerId, TELEGRAM_ID);
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // У A сейчас ~44 дня.
        Customer aBefore = customerRepository.findByTelegramId(referrerId).orElseThrow();
        assertThat(aBefore.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(43), LocalDateTime.now().plusDays(45));

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_reduce");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_reduce")).thenReturn(refund);
        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_reduce", YK_PAYMENT_ID, "150.00"));

        // A: 44 - 14 = 30 дней, статус остаётся активным.
        Customer aAfter = customerRepository.findByTelegramId(referrerId).orElseThrow();
        assertThat(aAfter.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(aAfter.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29), LocalDateTime.now().plusDays(31));
    }

    @Test
    void handleRefundSucceeded_shouldSkipRevertWhenReferralHasNoPaymentId() {
        // Legacy-строка: Referral создан до миграции 008, payment_id=NULL — revert невозможен.
        // Проверяем, что refund не падает и просто делает обычный refund основного платежа.
        Long referrerId = 900_004L;

        // Триггерим оплату без создания Referral → после оплаты вручную добавляем "старый" Referral
        // с paid_at но без payment_id, как будто он был создан до миграции.
        insertPendingPayment();
        mockYooKassaPaymentSucceeded();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // Legacy-стиль: paidAt заполнен (когда-то награда выдавалась), но paymentId = null.
        Referral legacy = Referral.builder()
                .referrerTelegramId(referrerId)
                .referredTelegramId(TELEGRAM_ID)
                .referredAt(LocalDateTime.now().minusDays(10))
                .paidAt(LocalDateTime.now().minusDays(9))
                .rewardBonusDays(ReferralService.REFERRER_BONUS_DAYS)
                .refereeBonusDays(ReferralService.REFEREE_BONUS_DAYS)
                // paymentId = null — имитируем pre-008 строку
                .build();
        referralRepository.save(legacy);

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_legacy");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_legacy")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_legacy", YK_PAYMENT_ID, "150.00"));

        // Refund основного платежа сработал, Payment=REFUNDED.
        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.REFUNDED);

        // Referral не помечен как revertedл (для legacy-строк revert невозможен — их нельзя
        // привязать к конкретному платежу).
        Referral stillLegacy = referralRepository.findByReferredTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(stillLegacy.getBonusRevertedAt()).isNull();
        assertThat(stillLegacy.getPaymentId()).isNull();
    }

    /** Создаёт Referral связь referrer → referred с referredAt=now. */
    private Referral insertReferral(Long referrerTelegramId, Long referredTelegramId) {
        return referralRepository.save(Referral.builder()
                .referrerTelegramId(referrerTelegramId)
                .referredTelegramId(referredTelegramId)
                .referredAt(LocalDateTime.now())
                .build());
    }

    /** Мокирует getPayment успешным ответом для YK_PAYMENT_ID (для повторного использования). */
    private void mockYooKassaPaymentSucceeded() {
        YooKassaClient.PaymentResponse ok = new YooKassaClient.PaymentResponse();
        ok.setId(YK_PAYMENT_ID);
        ok.setStatus("succeeded");
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ok);
    }

    /** Строит тело webhook refund.object — как шлёт YooKassa. */
    private java.util.Map<String, Object> refundObjectMap(String refundId, String paymentId, String amountValue) {
        java.util.Map<String, Object> amount = new java.util.LinkedHashMap<>();
        amount.put("value", amountValue);
        amount.put("currency", "RUB");
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", refundId);
        map.put("status", "succeeded");
        map.put("payment_id", paymentId);
        map.put("amount", amount);
        return map;
    }

    // ========================
    // Регрессия: refund отвязывает карту даже если её перепривязал ДРУГОЙ план
    // ========================

    @Test
    void handleRefundSucceeded_shouldClearAutorenewEvenIfPlanIdDiffers() {
        // Сценарий: юзер купил month_1 с save_method (autorenewPlanId=month_1).
        // Потом купил month_3 с save_method — карта перепривязалась
        // (autorenewPlanId=month_3, autorenewMethodId=pm_new). После этого
        // владелец делает refund на ПЕРВЫЙ платёж (month_1) вручную в ЛК.
        // До фикса карта оставалась привязанной (planId не совпадал) — нарушение
        // правил YooKassa и юзер мог продолжать получать автосписания с возвращённой карты.

        // Платёж 1 (month_1) с привязкой карты.
        YooKassaClient.PaymentResponse paid1 = new YooKassaClient.PaymentResponse();
        paid1.setId(YK_PAYMENT_ID);
        paid1.setStatus("succeeded");
        YooKassaClient.PaymentMethod m1 = new YooKassaClient.PaymentMethod();
        m1.setId("pm_old");
        m1.setSaved(true);
        m1.setType("bank_card");
        paid1.setPaymentMethod(m1);
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(paid1);
        insertPendingPayment();
        paymentProcessingService.handleSucceeded(YK_PAYMENT_ID);

        // Платёж 2 (month_3) перепривязывает карту.
        paymentRepository.save(Payment.builder()
                .telegramId(TELEGRAM_ID).telegramUsername(TELEGRAM_USERNAME)
                .provider(Payment.Provider.YOOKASSA)
                .yookassaPaymentId("yk_pay_second")
                .planId("month_3").amountKopeks(40000L)
                .status(Payment.Status.PENDING)
                .build());
        YooKassaClient.PaymentResponse paid2 = new YooKassaClient.PaymentResponse();
        paid2.setId("yk_pay_second");
        paid2.setStatus("succeeded");
        YooKassaClient.PaymentMethod m2 = new YooKassaClient.PaymentMethod();
        m2.setId("pm_new");
        m2.setSaved(true);
        m2.setType("bank_card");
        paid2.setPaymentMethod(m2);
        when(yooKassaClient.getPayment("yk_pay_second")).thenReturn(paid2);
        paymentProcessingService.handleSucceeded("yk_pay_second");

        // У юзера сейчас autorenewPlanId=month_3, autorenewMethodId=pm_new.
        Customer before = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(before.getAutorenewMethodId()).isEqualTo("pm_new");
        assertThat(before.getAutorenewPlanId()).isEqualTo("month_3");

        // Refund на ПЕРВЫЙ платёж (planId=month_1 ≠ month_3 в customer).
        reset(notificationService);
        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_old");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_PAYMENT_ID);
        when(yooKassaClient.getRefund("ref_old")).thenReturn(refund);
        paymentProcessingService.handleRefundSucceeded(refundObjectMap("ref_old", YK_PAYMENT_ID, "150.00"));

        // Карта должна быть отвязана несмотря на mismatch planId — compliance с YK правилами.
        Customer after = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(after.getAutorenewMethodId()).isNull();
        assertThat(after.getAutorenewPlanId()).isNull();
        assertThat(after.isAutorenewEnabled()).isFalse();
    }
}
