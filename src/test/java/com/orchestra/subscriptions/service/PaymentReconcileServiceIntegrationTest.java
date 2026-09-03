package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Интеграционные тесты на догон потерянных YK webhook-уведомлений: создаём «зависший»
 * PENDING-платёж в БД, мокаем ответ YK API, дёргаем {@link PaymentReconcileService#runOnce()}
 * и проверяем, что состояние сошлось — подписка активирована, Customer создан, AccessProvider задёрнут.
 * <p>
 * Разобранный случай: человек заплатил 150₽, эквайринг = SUCCEEDED, наш Payment = PENDING
 * (webhook ушёл в момент рестарта контейнера). Лечилось вручную через {@code curl POST /webhook/yookassa}
 * благодаря идемпотентности {@code handleSucceeded}. Этот reconciler автоматизирует тот же путь.
 */
@SpringBootTest
@Testcontainers
class PaymentReconcileServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PaymentReconcileService paymentReconcileService;

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


    @MockitoBean
    NotificationService notificationService;


    private static final Long TELEGRAM_ID = 276_205_301L;
    private static final String TELEGRAM_USERNAME = "stuck_user";
    private static final String YK_PAYMENT_ID = "2f0c7a5e-stale-5000-8000-1234567890ab";
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

    /**
     * Сохраняет PENDING payment с искусственно состаренным {@code created_at}.
     * Хаком обходит {@code @PrePersist} (который переписал бы createdAt на now): сначала save()
     * — entity вставлена, createdAt=now; затем update — JPA dirty checking шлёт UPDATE,
     * @PrePersist на UPDATE не фирится.
     */
    private Payment insertStalePendingPayment(LocalDateTime createdAt) {
        return insertPendingWithProviderAndYkId(createdAt, Payment.Provider.YOOKASSA, YK_PAYMENT_ID);
    }

    private Payment insertPendingWithProviderAndYkId(LocalDateTime createdAt,
                                                     Payment.Provider provider,
                                                     String ykId) {
        Payment p = paymentRepository.save(Payment.builder()
                .yookassaPaymentId(ykId)
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID)
                .amountKopeks(PRICE_KOPEKS)
                .provider(provider)
                .status(Payment.Status.PENDING)
                .build());
        p.setCreatedAt(createdAt);
        return paymentRepository.save(p);
    }

    private YooKassaClient.PaymentResponse ykSaid(String status) {
        YooKassaClient.PaymentResponse r = new YooKassaClient.PaymentResponse();
        r.setId(YK_PAYMENT_ID);
        r.setStatus(status);
        return r;
    }

    /** Имитирует то, что бросил бы {@link YooKassaClient#getPayment} при 404 от YK API. */
    private IllegalStateException ykNotFoundException() {
        WebClientResponseException cause = new WebClientResponseException(
                404, "Not Found", null, null, null);
        return new IllegalStateException("YooKassa getPayment failed: 404 Not Found", cause);
    }

    // ========================
    // Happy path: догон потерянного webhook'а
    // ========================

    @Test
    void runOnce_shouldRecoverStalePendingAsSucceeded() {
        // Платёж создан 10 минут назад, webhook не дошёл, в YK уже succeeded.
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(10));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ykSaid("succeeded"));

        paymentReconcileService.runOnce();

        // Payment добит до SUCCEEDED.
        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(p.getPaidAt()).isNotNull();

        // Подписка действительно создана (как при штатном webhook).
        Customer user = customerRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29), LocalDateTime.now().plusDays(31));

        verify(accessProvider).createUser(anyString(), anyInt(), anyLong());
        verify(notificationService).sendPaymentSuccess(
                eq(TELEGRAM_ID), any(Customer.class), any());
    }

    @Test
    void runOnce_shouldRecoverStalePendingAsCanceled() {
        // Платёж завис, в YK — canceled (юзер закрыл вкладку, 3DS истёк).
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(10));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ykSaid("canceled"));

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.CANCELED);
        // Никакой подписки/уведомления — это просто закрытие висяка.
        assertThat(customerRepository.findByTelegramId(TELEGRAM_ID)).isEmpty();
        verifyNoInteractions(accessProvider);
    }

    // ========================
    // Threshold: молодые платежи не трогаем — webhook ещё в пути
    // ========================

    @Test
    void runOnce_shouldSkipPendingBelowMinAge() {
        // Платёж создан 1 минуту назад — webhook ещё реалистично может дойти.
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(1));

        paymentReconcileService.runOnce();

        // Не должны были даже спросить YK API.
        verify(yooKassaClient, never()).getPayment(anyString());
        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.PENDING);
    }

    // ========================
    // YK says still pending — даём ещё шанс (но не вечно)
    // ========================

    @Test
    void runOnce_shouldKeepPendingWhenYkStillPending() {
        // 10 минут — выше порога, но 24h give-up ещё далеко.
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(10));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ykSaid("pending"));

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.PENDING);
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void runOnce_shouldForceCaptureWhenStuckInWaitingForCapture() {
        // YK иногда задерживает auto-capture (мы создаём платежи с capture=true, но YK
        // может застрять в waiting_for_capture для 3DS-callback'а). Раньше юзер ждал 24h.
        // Теперь reconciler пушит capture явно — успех ⇒ моментальная активация.
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(10));
        // Первый getPayment (в reconcileOne) → waiting_for_capture; следующий (re-verify
        // внутри handleSucceeded после capture'а) → succeeded. Так это и происходит на проде:
        // capture форсирует переход состояния на стороне YK.
        when(yooKassaClient.getPayment(YK_PAYMENT_ID))
                .thenReturn(ykSaid("waiting_for_capture"))
                .thenReturn(ykSaid("succeeded"));
        when(yooKassaClient.capturePayment(YK_PAYMENT_ID)).thenReturn(ykSaid("succeeded"));

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        verify(yooKassaClient).capturePayment(YK_PAYMENT_ID);
        // Активация подписки прошла через handleSucceeded.
        verify(accessProvider).createUser(anyString(), anyInt(), anyLong());
    }

    @Test
    void runOnce_shouldStayPendingWhenCaptureFailsTransiently() {
        // Если capture упал (network blip, YK 5xx) — оставляем PENDING, дотолкаем в next tick.
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(10));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ykSaid("waiting_for_capture"));
        when(yooKassaClient.capturePayment(YK_PAYMENT_ID))
                .thenThrow(new RuntimeException("YK 502"));

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.PENDING);
        verifyNoInteractions(accessProvider);
    }

    @Test
    void runOnce_shouldForceCaptureCanceledTransitionToCanceled() {
        // YK мог отклонить захват (insufficient funds на pre-auth, fraud-hold expired) — после
        // capture статус становится canceled, мы помечаем платёж CANCELED, не дёргая AccessProvider.
        // getPayment вызывается дважды: reconcileOne читает waiting_for_capture, затем handleCanceled
        // ре-верифицирует статус (он уже canceled — в проде getPayment консистентен с capture).
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(10));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID))
                .thenReturn(ykSaid("waiting_for_capture"), ykSaid("canceled"));
        when(yooKassaClient.capturePayment(YK_PAYMENT_ID)).thenReturn(ykSaid("canceled"));

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.CANCELED);
        verifyNoInteractions(accessProvider);
    }

    // ========================
    // Give-up: после 24h даже pending помечаем FAILED — чтобы не висели вечно
    // ========================

    @Test
    void runOnce_shouldMarkFailedAfterGiveUpWhenYkStillPending() {
        // 25 часов назад — за пределами give-up порога 24ч.
        insertStalePendingPayment(LocalDateTime.now().minusHours(25));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ykSaid("pending"));

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.FAILED);
        // Подписка не должна была активироваться — статус всё ещё pending в YK.
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    @Test
    void runOnce_shouldMarkFailedAfterGiveUpOn404() {
        // Платёж 25h назад + 404 от YK (запись потерялась/протухла) → помечаем FAILED.
        insertStalePendingPayment(LocalDateTime.now().minusHours(25));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenThrow(ykNotFoundException());

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    @Test
    void runOnce_shouldNotMarkFailedOn404WhenYounger() {
        // 6h назад + 404 — возможно YK ещё не закатил запись, не торопимся.
        insertStalePendingPayment(LocalDateTime.now().minusHours(6));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenThrow(ykNotFoundException());

        paymentReconcileService.runOnce();

        Payment p = paymentRepository.findByYookassaPaymentId(YK_PAYMENT_ID).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.PENDING);
    }

    // ========================
    // Не наш контур: Stars / CryptoBot платежи проходят мимо
    // ========================

    @Test
    void runOnce_reconcilesCrypto_andSkipsStarsWithoutChargeId() {
        // Stars в PENDING без telegram_charge_id — звёзды ещё не списаны / charge не сохранён,
        // реконсилеру нечего ретраить (нужен chargeId). Не трогаем.
        Payment stars = paymentRepository.save(Payment.builder()
                .telegramId(TELEGRAM_ID).telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID).amountKopeks(PRICE_KOPEKS)
                .provider(Payment.Provider.STARS)
                .status(Payment.Status.PENDING)
                .build());
        stars.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        paymentRepository.save(stars);

        // CryptoBot в PENDING с invoice id — теперь реконсилится (раньше игнорировался):
        // опросим Crypto Pay API. Мок вернёт null (не "paid") → активации нет, остаётся PENDING.
        Payment crypto = paymentRepository.save(Payment.builder()
                .telegramId(TELEGRAM_ID).telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID).amountKopeks(PRICE_KOPEKS)
                .provider(Payment.Provider.CRYPTOBOT)
                .cryptoInvoiceId("inv_xyz")
                .status(Payment.Status.PENDING)
                .build());
        crypto.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        paymentRepository.save(crypto);

        paymentReconcileService.runOnce();

        // YK API не дёргается — stale YooKassa нет.
        verify(yooKassaClient, never()).getPayment(anyString());
        // Crypto опрашивается.
        verify(cryptoBotClient).getInvoice("inv_xyz");
        // Оба остаются PENDING (crypto не "paid", stars без chargeId не взят).
        assertThat(paymentRepository.findById(stars.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.PENDING);
        assertThat(paymentRepository.findById(crypto.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.PENDING);
    }

    @Test
    void runOnce_shouldSkipPaymentsWithoutYookassaPaymentId() {
        // YooKassa-платёж, но без yookassa_payment_id (createPayment упал между save() и API).
        // Опрашивать YK API нечем — пропускаем.
        Payment p = paymentRepository.save(Payment.builder()
                .telegramId(TELEGRAM_ID).telegramUsername(TELEGRAM_USERNAME)
                .planId(PLAN_ID).amountKopeks(PRICE_KOPEKS)
                .provider(Payment.Provider.YOOKASSA)
                .status(Payment.Status.PENDING)
                .build());
        p.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        paymentRepository.save(p);

        paymentReconcileService.runOnce();

        verify(yooKassaClient, never()).getPayment(anyString());
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.PENDING);
    }

    // ========================
    // Идемпотентность: повторный прогон ничего не ломает
    // ========================

    @Test
    void runOnce_shouldBeIdempotent_secondPassIsNoop() {
        insertStalePendingPayment(LocalDateTime.now().minusMinutes(10));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ykSaid("succeeded"));

        paymentReconcileService.runOnce(); // догнали succeeded
        // Сбрасываем ВКЛЮЧАЯ yooKassaClient — иначе verify(...).never() поймает легитимные
        // вызовы getPayment из первого прогона (reconcileOne + handleSucceeded re-verify).
        reset(accessProvider, notificationService, yooKassaClient);

        paymentReconcileService.runOnce(); // второй прогон — должен быть no-op

        // Платёж уже SUCCEEDED → выпадает из фильтра findStaleYookassaPending.
        verify(yooKassaClient, never()).getPayment(anyString());
        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    // ========================
    // Защитный catch: ошибка по одному платежу не должна остановить весь прогон
    // ========================

    @Test
    void runOnce_shouldContinueProcessingAfterPerPaymentError() {
        // Один платёж, на котором YK API кинет нештатную ошибку.
        Payment broken = insertPendingWithProviderAndYkId(
                LocalDateTime.now().minusMinutes(15),
                Payment.Provider.YOOKASSA,
                "broken-yk-id");
        // Второй (свежее) — нормальный, должен догнаться до succeeded.
        Payment ok = insertPendingWithProviderAndYkId(
                LocalDateTime.now().minusMinutes(10),
                Payment.Provider.YOOKASSA,
                YK_PAYMENT_ID);

        when(yooKassaClient.getPayment("broken-yk-id"))
                .thenThrow(new RuntimeException("YK API totally borked"));
        when(yooKassaClient.getPayment(YK_PAYMENT_ID)).thenReturn(ykSaid("succeeded"));

        paymentReconcileService.runOnce();

        // Сломанный платёж остался PENDING — всё ок.
        assertThat(paymentRepository.findById(broken.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.PENDING);
        // Хороший — догнан до SUCCEEDED.
        assertThat(paymentRepository.findById(ok.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.SUCCEEDED);
    }
}
