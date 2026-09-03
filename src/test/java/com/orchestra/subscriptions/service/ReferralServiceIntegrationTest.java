package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class ReferralServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ReferralService referralService;

    // SpyBean — даёт настоящее поведение по умолчанию, но позволяет стаббить отдельные
    // вызовы через doThrow() в тестах частичных сбоев активации бонусов.
    @MockitoSpyBean
    AccessService accessService;

    @Autowired
    ReferralRepository referralRepository;

    @Autowired
    CustomerRepository userRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;



    // Мокаем, чтобы проверять owner-алерты при частичных сбоях и не тянуть SubscriptionBot.
    @MockitoBean
    NotificationService notificationService;

    private static final Long A = 100L; // referrer
    private static final Long B = 200L; // referred

    @BeforeEach
    void setUp() {
        referralRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mockUser = new AccessProvider.AccessAccount();
        mockUser.setSubscriptionUrl("https://example.com/sub/test");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mockUser);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());

        // Spy на AccessService — сбрасываем между тестами, чтобы стаббы из одного не утекали в другой.
        reset(accessService);
        reset(notificationService);
    }

    // ========================
    // recordReferral
    // ========================

    @Test
    void recordReferral_shouldCreateRowForValidPair() {
        boolean ok = referralService.recordReferral(A, B);

        assertThat(ok).isTrue();
        Optional<Referral> r = referralRepository.findByReferredTelegramId(B);
        assertThat(r).isPresent();
        assertThat(r.get().getReferrerTelegramId()).isEqualTo(A);
        assertThat(r.get().getReferredAt()).isNotNull();
        assertThat(r.get().getPaidAt()).isNull();
    }

    @Test
    void recordReferral_shouldRejectSelfReferral() {
        boolean ok = referralService.recordReferral(A, A);

        assertThat(ok).isFalse();
        assertThat(referralRepository.count()).isZero();
    }

    @Test
    void recordReferral_shouldRejectNulls() {
        assertThat(referralService.recordReferral(null, B)).isFalse();
        assertThat(referralService.recordReferral(A, null)).isFalse();
    }

    @Test
    void recordReferral_shouldBeFirstComeFirstServe() {
        Long A2 = 101L; // другой потенциальный реферер

        assertThat(referralService.recordReferral(A, B)).isTrue();
        // Второй реферер приходит за тем же юзером — игнорируется.
        assertThat(referralService.recordReferral(A2, B)).isFalse();

        Referral r = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(r.getReferrerTelegramId()).isEqualTo(A); // остался первый
    }

    // ========================
    // maybeAwardReferral
    // ========================

    @Test
    void maybeAwardReferral_shouldAwardBothOnFirstSuccess() {
        referralService.recordReferral(A, B);

        // У B есть активная подписка после оплаты — симулируем.
        accessService.activatePaidSubscription(B, "bob", 30);
        // Сохраняем SUCCEEDED-платёж (это то, что делает PaymentProcessingService до нашего хука).
        Payment trigger = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, trigger.getId());

        Referral updated = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(updated.getPaidAt()).isNotNull();
        assertThat(updated.getRewardBonusDays()).isEqualTo(ReferralService.REFERRER_BONUS_DAYS);
        assertThat(updated.getRefereeBonusDays()).isEqualTo(ReferralService.REFEREE_BONUS_DAYS);
        // payment_id должен быть проставлен — используется при refund для отката бонусов.
        assertThat(updated.getPaymentId()).isEqualTo(trigger.getId());

        // У B подписка продлилась на 5 дней: было 30, стало ~35.
        Customer bUser = userRepository.findByTelegramId(B).orElseThrow();
        assertThat(bUser.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(34),
                LocalDateTime.now().plusDays(36));

        // У A создался Customer с 14 днями подписки.
        Customer aUser = userRepository.findByTelegramId(A).orElseThrow();
        assertThat(aUser.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(aUser.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(13),
                LocalDateTime.now().plusDays(15));

        // AccessProvider был позван для обоих (минимум две активации поверх созданий).
        verify(accessProvider, atLeastOnce()).enableUserUntil(anyString(), any(), any());
    }

    @Test
    void maybeAwardReferral_shouldStackOnExistingReferrerSubscription() {
        referralService.recordReferral(A, B);

        // У A УЖЕ есть активная подписка на 20 дней.
        accessService.activatePaidSubscription(A, "alice", 20);
        // У B — только что купленная.
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment trigger = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, trigger.getId());

        Customer aUser = userRepository.findByTelegramId(A).orElseThrow();
        // 20 + 14 = ~34 дня
        assertThat(aUser.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(33),
                LocalDateTime.now().plusDays(35));
    }

    @Test
    void maybeAwardReferral_shouldNotAwardTwiceOnSecondPayment() {
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment first = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, first.getId());

        // Вторая оплата B — не должна повторно наградить.
        Payment second = saveSuccessfulPayment(B);
        referralService.maybeAwardReferral(B, second.getId());

        Referral r = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(r.getPaidAt()).isNotNull();

        Customer aUser = userRepository.findByTelegramId(A).orElseThrow();
        // A получил только одну награду (14 дней), не 28.
        assertThat(aUser.getExpiresAt()).isBefore(LocalDateTime.now().plusDays(16));
    }

    @Test
    void maybeAwardReferral_shouldDoNothingIfNoReferral() {
        // B никогда не был приглашён — никакого награждения.
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment trigger = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, trigger.getId());

        assertThat(userRepository.findByTelegramId(A)).isEmpty();
    }

    @Test
    void maybeAwardReferral_shouldSkipIfNotFirstPayment() {
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        // Уже есть старый SUCCEEDED-платёж + новый SUCCEEDED → count = 2 → не первый.
        saveSuccessfulPayment(B);
        Payment second = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, second.getId());

        Referral r = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(r.getPaidAt()).isNull();
        assertThat(userRepository.findByTelegramId(A)).isEmpty();
    }

    @Test
    void maybeAwardReferral_doesNotAwardBonusWhenRefereePreviouslyHadRefundedPayment() {
        // Закрываем эксплойт pay → /refund → /start ref_X → pay: после возврата статус
        // прошлого платежа REFUNDED, но мы всё равно не считаем его "первым".
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        // Старый платёж, который был возвращён через /refund.
        saveRefundedPayment(B);
        // Новая оплата (только что прошла SUCCEEDED).
        Payment newPayment = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, newPayment.getId());

        Referral r = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(r.getPaidAt()).isNull();
        // У A не должен был создаться Customer — бонус не начислялся.
        assertThat(userRepository.findByTelegramId(A)).isEmpty();
    }

    @Test
    void maybeAwardReferral_doesNotAwardBonusWhenRefereeAlreadyHadSuccessfulPayment() {
        // Явное покрытие существующего поведения: прошлый SUCCEEDED → бонус не начисляется.
        // (Дублирует логику shouldSkipIfNotFirstPayment, но оставлено как явная regression guard
        // на случай будущих изменений критерия «первый платёж».)
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        saveSuccessfulPayment(B); // прошлый успешный платёж
        Payment newPayment = saveSuccessfulPayment(B); // новый SUCCEEDED

        referralService.maybeAwardReferral(B, newPayment.getId());

        Referral r = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(r.getPaidAt()).isNull();
        assertThat(userRepository.findByTelegramId(A)).isEmpty();
    }

    @Test
    void maybeAwardReferral_shouldSkipAfterMonthlyCapHit() {
        // Симулируем что A уже получил 10 наград за последние 30 дней.
        for (long i = 0; i < ReferralService.MONTHLY_REWARDED_CAP; i++) {
            Long refereeId = 1000L + i;
            Referral r = Referral.builder()
                    .referrerTelegramId(A)
                    .referredTelegramId(refereeId)
                    .referredAt(LocalDateTime.now().minusDays(5))
                    .paidAt(LocalDateTime.now().minusDays(5))
                    .rewardBonusDays(ReferralService.REFERRER_BONUS_DAYS)
                    .refereeBonusDays(ReferralService.REFEREE_BONUS_DAYS)
                    .build();
            referralRepository.save(r);
        }

        // Теперь приходит 11-й реферал.
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment trigger = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, trigger.getId());

        Referral current = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(current.getPaidAt()).isNull(); // не помечен как награждённый
        // У A не должен был создаться новый Customer (награды не было).
        assertThat(userRepository.findByTelegramId(A)).isEmpty();
    }

    @Test
    void maybeAwardReferral_shouldCountOnlyLast30DaysForCap() {
        // 10 старых наград (45 дней назад) — не должны засчитываться в cap.
        for (long i = 0; i < 10; i++) {
            Long refereeId = 2000L + i;
            Referral old = Referral.builder()
                    .referrerTelegramId(A)
                    .referredTelegramId(refereeId)
                    .referredAt(LocalDateTime.now().minusDays(45))
                    .paidAt(LocalDateTime.now().minusDays(45))
                    .rewardBonusDays(ReferralService.REFERRER_BONUS_DAYS)
                    .refereeBonusDays(ReferralService.REFEREE_BONUS_DAYS)
                    .build();
            referralRepository.save(old);
        }

        // Новый реферал — должен награждаться, потому что старые уже вне окна.
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment trigger = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, trigger.getId());

        Referral current = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(current.getPaidAt()).isNotNull();
    }

    // ========================
    // getStats
    // ========================

    @Test
    void getStats_shouldAggregateCorrectly() {
        // 3 приглашённых, из них 2 заплатили.
        Referral paid1 = Referral.builder()
                .referrerTelegramId(A).referredTelegramId(300L)
                .referredAt(LocalDateTime.now().minusDays(5))
                .paidAt(LocalDateTime.now().minusDays(4))
                .rewardBonusDays(ReferralService.REFERRER_BONUS_DAYS)
                .refereeBonusDays(ReferralService.REFEREE_BONUS_DAYS)
                .build();
        Referral paid2 = Referral.builder()
                .referrerTelegramId(A).referredTelegramId(301L)
                .referredAt(LocalDateTime.now().minusDays(3))
                .paidAt(LocalDateTime.now().minusDays(2))
                .rewardBonusDays(ReferralService.REFERRER_BONUS_DAYS)
                .refereeBonusDays(ReferralService.REFEREE_BONUS_DAYS)
                .build();
        Referral pending = Referral.builder()
                .referrerTelegramId(A).referredTelegramId(302L)
                .referredAt(LocalDateTime.now().minusDays(1))
                .build();
        referralRepository.save(paid1);
        referralRepository.save(paid2);
        referralRepository.save(pending);

        ReferralService.ReferralStats stats = referralService.getStats(A);

        assertThat(stats.invited()).isEqualTo(3);
        assertThat(stats.rewarded()).isEqualTo(2);
        assertThat(stats.totalBonusDays()).isEqualTo(2L * ReferralService.REFERRER_BONUS_DAYS);
        assertThat(stats.capRemaining()).isEqualTo(ReferralService.MONTHLY_REWARDED_CAP - 2);
    }

    @Test
    void getStats_shouldReturnZeroesForNewUser() {
        ReferralService.ReferralStats stats = referralService.getStats(9999L);

        assertThat(stats.invited()).isZero();
        assertThat(stats.rewarded()).isZero();
        assertThat(stats.totalBonusDays()).isZero();
        assertThat(stats.capRemaining()).isEqualTo(ReferralService.MONTHLY_REWARDED_CAP);
    }

    // ========================
    // idempotency / concurrency
    // ========================

    @Test
    void maybeAwardReferral_shouldBeIdempotentOnRepeatedCalls() {
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment trigger = saveSuccessfulPayment(B);

        referralService.maybeAwardReferral(B, trigger.getId());
        // Сразу второй вызов (имитируем дубликат-delivery webhook).
        referralService.maybeAwardReferral(B, trigger.getId());

        Customer aUser = userRepository.findByTelegramId(A).orElseThrow();
        // A получил только 14 дней один раз.
        assertThat(aUser.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(13),
                LocalDateTime.now().plusDays(15));
    }

    // ========================
    // partial failure in bonus activation — owner alerted
    // ========================

    @Test
    void maybeAwardReferral_alertsOwnerWhenRefereeBonusActivationFails() {
        // Setup: у B активная подписка после оплаты.
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment trigger = saveSuccessfulPayment(B);

        // Стаббим ТОЛЬКО вызов для referee (B) с REFEREE_BONUS_DAYS — он упадёт.
        // Остальные вызовы (в т.ч. последующий для referrer A на REFERRER_BONUS_DAYS)
        // пойдут через реальный путь.
        doThrow(new RuntimeException("AccessProvider sync blew up on referee"))
                .when(accessService).addDays(
                        eq(B), anyString(), eq(ReferralService.REFEREE_BONUS_DAYS), any());

        referralService.maybeAwardReferral(B, trigger.getId());

        // Referrer всё равно получил бонус — это главное: один сбой не должен утащить
        // второго юзера за собой.
        Customer aUser = userRepository.findByTelegramId(A).orElseThrow();
        assertThat(aUser.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(13),
                LocalDateTime.now().plusDays(15));

        // Owner получил алерт: refereeFailed=true, referrerFailed=false.
        verify(notificationService).notifyOwnerReferralBonusError(
                eq(A), eq(B), eq(true), eq(false), anyString());

        // paid_at проставлен — автоповтора не будет (это и есть причина алерта).
        Referral r = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(r.getPaidAt()).isNotNull();
    }

    @Test
    void maybeAwardReferral_alertsOwnerWhenReferrerBonusActivationFails() {
        // Setup: у B активная подписка после оплаты.
        referralService.recordReferral(A, B);
        accessService.activatePaidSubscription(B, "bob", 30);
        Payment trigger = saveSuccessfulPayment(B);

        // Стаббим ТОЛЬКО вызов для referrer (A) с REFERRER_BONUS_DAYS — он упадёт.
        // Referee-бонус (B на REFEREE_BONUS_DAYS) пойдёт через реальный путь.
        doThrow(new RuntimeException("AccessProvider sync blew up on referrer"))
                .when(accessService).addDays(
                        eq(A), any(), eq(ReferralService.REFERRER_BONUS_DAYS), any());

        referralService.maybeAwardReferral(B, trigger.getId());

        // Referee всё равно получил +5d поверх своих 30: должно быть ~35.
        Customer bUser = userRepository.findByTelegramId(B).orElseThrow();
        assertThat(bUser.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(34),
                LocalDateTime.now().plusDays(36));

        // Referrer не получил свой бонус — Customer для A не должен был создаться.
        assertThat(userRepository.findByTelegramId(A)).isEmpty();

        // Owner получил алерт: refereeFailed=false, referrerFailed=true.
        verify(notificationService).notifyOwnerReferralBonusError(
                eq(A), eq(B), eq(false), eq(true), anyString());

        // paid_at проставлен — автоповтора не будет.
        Referral r = referralRepository.findByReferredTelegramId(B).orElseThrow();
        assertThat(r.getPaidAt()).isNotNull();
    }

    // ========================
    // helpers
    // ========================

    private Payment saveSuccessfulPayment(Long telegramId) {
        Payment p = Payment.builder()
                .telegramId(telegramId)
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1")
                .amountKopeks(15000)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now())
                .build();
        return paymentRepository.save(p);
    }

    private Payment saveRefundedPayment(Long telegramId) {
        Payment p = Payment.builder()
                .telegramId(telegramId)
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1")
                .amountKopeks(15000)
                .status(Payment.Status.REFUNDED)
                .paidAt(LocalDateTime.now().minusDays(3))
                .refundedAt(LocalDateTime.now().minusDays(1))
                .refundAmountKopeks(15000L)
                .build();
        return paymentRepository.save(p);
    }
}
