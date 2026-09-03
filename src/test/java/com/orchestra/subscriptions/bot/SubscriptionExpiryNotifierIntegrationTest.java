package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.NotificationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Интеграционные тесты планировщика истечения подписок.
 * <p>
 * Этот шедулер — последняя линия обороны: он отключает неактивных юзеров в AccessProvider,
 * переводит их в DISABLED и шлёт прощальное сообщение. Если здесь баг — либо мы
 * продолжаем отдавать доступ бесплатно (утечка ресурсов), либо массово отключаем живых
 * платящих клиентов (финансовая катастрофа). Обе ситуации критичны.
 * <p>
 * Триал 7 дней: теперь получает за-24h и за-1h уведомления как и платные.
 * Раньше при триале 24h они были бессмысленны и пропускались; сейчас за день
 * до окончания триала — лучший конверсионный момент для напоминания.
 */
@SpringBootTest
@Testcontainers
class SubscriptionExpiryNotifierIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    SubscriptionExpiryNotifier notifier;

    @Autowired
    CustomerRepository userRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    AccessProvider accessProvider;

    @MockitoBean
    NotificationService notificationService;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;



    private static final Long PAID_TELEGRAM_ID = 1000001L;
    private static final Long TRIAL_TELEGRAM_ID = 1000002L;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        userRepository.deleteAll();
        doNothing().when(accessProvider).disableUser(anyString());
    }

    private Customer savePaidUser(LocalDateTime expiresAt, Customer.Status status,
                                 boolean notified24h, boolean notified1h) {
        Customer user = userRepository.save(Customer.builder()
                .telegramId(PAID_TELEGRAM_ID)
                .telegramUsername("paiduser")
                .providerUsername("tg_paiduser")
                .subscriptionLink("https://test.example.com/sub/paid")
                .status(status)
                .expiresAt(expiresAt)
                .notified24h(notified24h)
                .notified1h(notified1h)
                .build());
        // Платный юзер обязан иметь хотя бы одну SUCCEEDED-оплату — теперь это
        // и есть критерий «не триал» в SubscriptionExpiryNotifier.
        paymentRepository.save(Payment.builder()
                .telegramId(PAID_TELEGRAM_ID).telegramUsername("paiduser")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(30))
                .build());
        return user;
    }

    private Customer saveTrialUser(LocalDateTime expiresAt, Customer.Status status) {
        return userRepository.save(Customer.builder()
                .telegramId(TRIAL_TELEGRAM_ID)
                .telegramUsername("trialuser")
                .providerUsername("trial_trialuser")
                .subscriptionLink("https://test.example.com/sub/trial")
                .status(status)
                .expiresAt(expiresAt)
                .notified24h(false)
                .notified1h(false)
                .build());
    }

    // ========================
    // Отключение истёкших
    // ========================

    @Test
    void processExpired_shouldDisablePaidUserAndSendPaidSpecificText() {
        savePaidUser(LocalDateTime.now().minusMinutes(10), Customer.Status.ACTIVE, false, false);

        notifier.processExpiredSubscriptions();

        Customer after = userRepository.findByTelegramId(PAID_TELEGRAM_ID).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Customer.Status.DISABLED);
        verify(accessProvider).disableUser("tg_paiduser");
        // Текст для платной: содержит "Подписка закончилась" и не содержит "пробный"
        verify(notificationService).sendPlainText(eq(PAID_TELEGRAM_ID), contains("Подписка закончилась"));
    }

    @Test
    void processExpired_shouldDisableTrialUserAndSendTrialSpecificText() {
        saveTrialUser(LocalDateTime.now().minusMinutes(10), Customer.Status.ACTIVE);

        notifier.processExpiredSubscriptions();

        Customer after = userRepository.findByTelegramId(TRIAL_TELEGRAM_ID).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Customer.Status.DISABLED);
        verify(accessProvider).disableUser("trial_trialuser");
        // Текст для триала: содержит "Пробный период"
        verify(notificationService).sendPlainText(eq(TRIAL_TELEGRAM_ID), contains("Пробный период"));
    }

    @Test
    void processExpired_shouldNotTouchActiveUserWithFutureExpiry() {
        savePaidUser(LocalDateTime.now().plusDays(5), Customer.Status.ACTIVE, false, false);

        notifier.processExpiredSubscriptions();

        Customer after = userRepository.findByTelegramId(PAID_TELEGRAM_ID).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        verify(accessProvider, never()).disableUser(anyString());
    }

    @Test
    void processExpired_shouldIgnoreAlreadyDisabledUser() {
        // Уже DISABLED, даже если expiresAt в прошлом — не трогаем, не шлём повторных сообщений
        savePaidUser(LocalDateTime.now().minusDays(3), Customer.Status.DISABLED, true, true);

        notifier.processExpiredSubscriptions();

        verify(accessProvider, never()).disableUser(anyString());
        verify(notificationService, never()).sendPlainText(eq(PAID_TELEGRAM_ID), anyString());
    }

    // ========================
    // 24h warning
    // ========================

    @Test
    void warning24h_shouldSendForPaidUserAndSetFlag() {
        savePaidUser(LocalDateTime.now().plusHours(20), Customer.Status.ACTIVE, false, false);

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendExpiryWarning24h(PAID_TELEGRAM_ID);
        assertThat(userRepository.findByTelegramId(PAID_TELEGRAM_ID).orElseThrow().isNotified24h()).isTrue();
    }

    @Test
    void warning24h_shouldSendForTrialUserToo() {
        // Триал 7 дней (раньше был 24h, и тогда 24h-уведомление триалу было
        // бессмысленно). Сейчас за день до окончания триала имеет смысл напомнить
        // «время купить» — это лучший конверсионный момент.
        saveTrialUser(LocalDateTime.now().plusHours(20), Customer.Status.ACTIVE);

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendExpiryWarning24h(TRIAL_TELEGRAM_ID);
        assertThat(userRepository.findByTelegramId(TRIAL_TELEGRAM_ID).orElseThrow().isNotified24h()).isTrue();
    }

    @Test
    void warning24h_shouldNotSendIfAlreadyNotified() {
        savePaidUser(LocalDateTime.now().plusHours(20), Customer.Status.ACTIVE, true, false);

        notifier.processExpiredSubscriptions();

        verify(notificationService, never()).sendExpiryWarning24h(PAID_TELEGRAM_ID);
    }

    // ========================
    // 1h warning
    // ========================

    @Test
    void warning1h_shouldSendForPaidUserAndSetFlag() {
        // Истекает через 30 минут — попадает в окно 1h-уведомления
        // (и в окно 24h тоже, оба должны отработать корректно)
        savePaidUser(LocalDateTime.now().plusMinutes(30), Customer.Status.ACTIVE, false, false);

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendExpiryWarning1h(PAID_TELEGRAM_ID);
        Customer after = userRepository.findByTelegramId(PAID_TELEGRAM_ID).orElseThrow();
        assertThat(after.isNotified1h()).isTrue();
    }

    @Test
    void warning1h_shouldSendForTrialUserToo() {
        // Аналогично warning24h — теперь триал тоже получает напоминание за 1 час.
        saveTrialUser(LocalDateTime.now().plusMinutes(30), Customer.Status.ACTIVE);

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendExpiryWarning1h(TRIAL_TELEGRAM_ID);
        assertThat(userRepository.findByTelegramId(TRIAL_TELEGRAM_ID).orElseThrow().isNotified1h()).isTrue();
    }

    // ========================
    // Комбинированные и пакетные сценарии
    // ========================

    @Test
    void processExpired_shouldHandleMultipleUsersInOneTick() {
        // Три разных состояния одновременно:
        //  - платный истёкший → disable + paid text
        //  - триал истёкший → disable + trial text
        //  - платный далеко в будущем → не трогаем
        savePaidUser(LocalDateTime.now().minusMinutes(5), Customer.Status.ACTIVE, false, false);
        saveTrialUser(LocalDateTime.now().minusMinutes(5), Customer.Status.ACTIVE);
        userRepository.save(Customer.builder()
                .telegramId(9999999L)
                .telegramUsername("safe")
                .providerUsername("tg_safe")
                .subscriptionLink("https://test.example.com/sub/safe")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build());

        notifier.processExpiredSubscriptions();

        assertThat(userRepository.findByTelegramId(PAID_TELEGRAM_ID).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.DISABLED);
        assertThat(userRepository.findByTelegramId(TRIAL_TELEGRAM_ID).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.DISABLED);
        assertThat(userRepository.findByTelegramId(9999999L).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.ACTIVE);

        verify(accessProvider).disableUser("tg_paiduser");
        verify(accessProvider).disableUser("trial_trialuser");
        verify(accessProvider, never()).disableUser("tg_safe");
    }

    @Test
    void processExpired_shouldDoNothingOnEmptyDatabase() {
        // Санити: пустая БД, шедулер не падает и ничего не делает
        notifier.processExpiredSubscriptions();

        verifyNoInteractions(accessProvider);
        verifyNoInteractions(notificationService);
    }

    // ========================
    // processWinbacks — post-expiry напоминание
    // ========================

    @Test
    void winback_shouldSendToDisabledUserInsideWindow() {
        // Юзер истёк 3 дня назад, status=DISABLED.
        Customer user = userRepository.save(Customer.builder()
                .telegramId(PAID_TELEGRAM_ID)
                .telegramUsername("paiduser")
                .providerUsername("tg_paiduser")
                .subscriptionLink("https://test.example.com/sub/paid")
                .status(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(3))
                .winbackSent(false)
                .build());
        // SUCCEEDED-оплата нужна, чтобы юзер не был классифицирован как триал
        // и попал в winback (триалам winback не шлём).
        paymentRepository.save(Payment.builder()
                .telegramId(PAID_TELEGRAM_ID).telegramUsername("paiduser")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(33))
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendWinback(PAID_TELEGRAM_ID);
        Customer after = userRepository.findById(user.getId()).orElseThrow();
        assertThat(after.isWinbackSent()).isTrue();
    }

    @Test
    void winback_shouldSkipIfOutsideWindow() {
        // Юзер истёк вчера — ещё рано (внутри окна только от -2 до -4 дней).
        userRepository.save(Customer.builder()
                .telegramId(PAID_TELEGRAM_ID)
                .telegramUsername("paiduser").providerUsername("tg_paiduser")
                .subscriptionLink("https://test.example.com/sub/paid")
                .status(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .winbackSent(false)
                .build());
        // Юзер истёк 10 дней назад — уже поздно.
        userRepository.save(Customer.builder()
                .telegramId(PAID_TELEGRAM_ID + 1)
                .telegramUsername("old").providerUsername("tg_old")
                .subscriptionLink("https://test.example.com/sub/old")
                .status(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(10))
                .winbackSent(false)
                .build());
        // Оба ОПЛАЧИВАЛИ: без этого оба отсеклись бы как триалы, и тест зеленел бы
        // независимо от границ окна — то есть проверял бы не то, что написано в имени.
        savePaidPayment(PAID_TELEGRAM_ID, "paiduser", LocalDateTime.now().minusDays(31));
        savePaidPayment(PAID_TELEGRAM_ID + 1, "old", LocalDateTime.now().minusDays(40));

        notifier.processExpiredSubscriptions();

        verify(notificationService, never()).sendWinback(anyLong());
    }

    /** Успешная оплата — признак «не триал» для winback-фильтра. */
    private void savePaidPayment(long telegramId, String username, LocalDateTime paidAt) {
        paymentRepository.save(Payment.builder()
                .telegramId(telegramId)
                .telegramUsername(username)
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(paidAt)
                .build());
    }

    @Test
    void winback_shouldNotResend() {
        // Уже отправили — второй раз не должны.
        userRepository.save(Customer.builder()
                .telegramId(PAID_TELEGRAM_ID)
                .telegramUsername("paiduser").providerUsername("tg_paiduser")
                .subscriptionLink("https://test.example.com/sub/paid")
                .status(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(3))
                .winbackSent(true)
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService, never()).sendWinback(anyLong());
    }

    @Test
    void winback_shouldSkipTrialUser() {
        // Триалы не получают winback, но флаг ставим чтобы не долбиться в цикле.
        Customer trial = userRepository.save(Customer.builder()
                .telegramId(TRIAL_TELEGRAM_ID)
                .telegramUsername("trialuser").providerUsername("trial_trialuser")
                .subscriptionLink("https://test.example.com/sub/trial")
                .status(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(3))
                .winbackSent(false)
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService, never()).sendWinback(anyLong());
        Customer after = userRepository.findById(trial.getId()).orElseThrow();
        assertThat(after.isWinbackSent()).isTrue();
    }

    @Test
    void winback_shouldSkipIfRecentlyRefunded() {
        Customer user = userRepository.save(Customer.builder()
                .telegramId(PAID_TELEGRAM_ID)
                .telegramUsername("paiduser").providerUsername("tg_paiduser")
                .subscriptionLink("https://test.example.com/sub/paid")
                .status(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(3))
                .winbackSent(false)
                .build());
        // Refund был 5 дней назад → внутри 30-дневного cooldown'а → не отправляем.
        paymentRepository.save(Payment.builder()
                .telegramId(PAID_TELEGRAM_ID).telegramUsername("paiduser")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.REFUNDED)
                .refundedAt(LocalDateTime.now().minusDays(5))
                .refundAmountKopeks(15000L)
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService, never()).sendWinback(anyLong());
        Customer after = userRepository.findById(user.getId()).orElseThrow();
        // Флаг ставим, чтобы больше не проверять этот случай.
        assertThat(after.isWinbackSent()).isTrue();
    }

    // ========================
    // Regression: триал-апгрейд в платную подписку (provider_username остаётся trial_*)
    // До фикса такие юзеры пропускали 24h/1h уведомления и получали
    // «Пробный период закончился» вместо «Подписка закончилась».
    // ========================

    /**
     * Юзер начал с триала (provider_username=trial_*), потом купил месячную подписку.
     * AccessProvider-аккаунт переиспользуется без переименования (см. AccessService:72-73),
     * поэтому префикс «trial_» сохраняется. Триал-/платный-классификатор должен
     * смотреть на наличие SUCCEEDED-платежа, а не на префикс.
     */
    @Test
    void upgradedTrialUser_shouldGetPaidExpiryMessage_notTrialMessage() {
        Long telegramId = 7000001L;
        userRepository.save(Customer.builder()
                .telegramId(telegramId)
                .telegramUsername("upgraded")
                .providerUsername("trial_upgraded")  // префикс остался от триала
                .subscriptionLink("https://test.example.com/sub/up")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build());
        paymentRepository.save(Payment.builder()
                .telegramId(telegramId).telegramUsername("upgraded")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(30))
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendPlainText(eq(telegramId), contains("Подписка закончилась"));
        verify(notificationService, never()).sendPlainText(eq(telegramId), contains("Пробный период"));
    }

    @Test
    void upgradedTrialUser_shouldGet24hWarning() {
        // Тот же сценарий, но за 20 часов до истечения. Раньше уведомление пропускалось
        // из-за префикса trial_ — теперь должно отправляться.
        Long telegramId = 7000002L;
        userRepository.save(Customer.builder()
                .telegramId(telegramId)
                .telegramUsername("upgraded24h")
                .providerUsername("trial_upgraded24h")
                .subscriptionLink("https://test.example.com/sub/up24")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusHours(20))
                .notified24h(false).notified1h(false)
                .build());
        paymentRepository.save(Payment.builder()
                .telegramId(telegramId).telegramUsername("upgraded24h")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(29))
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendExpiryWarning24h(telegramId);
    }

    @Test
    void upgradedTrialUser_shouldGet1hWarning() {
        Long telegramId = 7000003L;
        userRepository.save(Customer.builder()
                .telegramId(telegramId)
                .telegramUsername("upgraded1h")
                .providerUsername("trial_upgraded1h")
                .subscriptionLink("https://test.example.com/sub/up1")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .notified24h(false).notified1h(false)
                .build());
        paymentRepository.save(Payment.builder()
                .telegramId(telegramId).telegramUsername("upgraded1h")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(30))
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendExpiryWarning1h(telegramId);
    }

    /**
     * REFUNDED-платёж тоже считается «не триалом» — юзер прошёл через успешную оплату
     * и сам её вернул, это не первая проба.
     */
    @Test
    void refundedUser_shouldGetPaidExpiryMessage() {
        Long telegramId = 7000004L;
        userRepository.save(Customer.builder()
                .telegramId(telegramId)
                .telegramUsername("refunded")
                .providerUsername("trial_refunded")
                .subscriptionLink("https://test.example.com/sub/ref")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build());
        paymentRepository.save(Payment.builder()
                .telegramId(telegramId).telegramUsername("refunded")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.REFUNDED)
                .paidAt(LocalDateTime.now().minusDays(20))
                .refundedAt(LocalDateTime.now().minusDays(15))
                .refundAmountKopeks(15000L)
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendPlainText(eq(telegramId), contains("Подписка закончилась"));
        verify(notificationService, never()).sendPlainText(eq(telegramId), contains("Пробный период"));
    }

    // ========================
    // winback (продолжение)
    // ========================

    @Test
    void winback_shouldSendIfOldRefundOutsideCooldown() {
        userRepository.save(Customer.builder()
                .telegramId(PAID_TELEGRAM_ID)
                .telegramUsername("paiduser").providerUsername("tg_paiduser")
                .subscriptionLink("https://test.example.com/sub/paid")
                .status(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(3))
                .winbackSent(false)
                .build());
        // Refund старше 30 дней — уже вышли за cooldown.
        paymentRepository.save(Payment.builder()
                .telegramId(PAID_TELEGRAM_ID).telegramUsername("paiduser")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").amountKopeks(15000L)
                .status(Payment.Status.REFUNDED)
                .refundedAt(LocalDateTime.now().minusDays(45))
                .refundAmountKopeks(15000L)
                .build());

        notifier.processExpiredSubscriptions();

        verify(notificationService).sendWinback(PAID_TELEGRAM_ID);
    }
}
