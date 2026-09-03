package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Referral;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.ReferralRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Реферальная программа.
 * <p>
 * Политика (согласована 2026-04-17):
 * <ul>
 *   <li>Приглашающий (реферер) A получает +14 дней при первой SUCCEEDED-оплате приглашённого B</li>
 *   <li>Приглашённый B получает +5 дней поверх купленной подписки</li>
 *   <li>Награждение происходит только на ПЕРВОЙ успешной оплате B (повторы не считаются)</li>
 *   <li>Anti-abuse: максимум 10 награждённых рефералов на одного реферера за последние 30 дней</li>
 *   <li>Self-referral (A=B) молча скипается</li>
 * </ul>
 */
@Slf4j
@Service
public class ReferralService {

    public static final int REFERRER_BONUS_DAYS = 14;
    public static final int REFEREE_BONUS_DAYS = 5;
    public static final int MONTHLY_REWARDED_CAP = 10;

    private final ReferralRepository referralRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository userRepository;
    private final AccessService accessService;
    /**
     * Lazy — потому что NotificationService сам инжектит SubscriptionBot, а SubscriptionBot инжектит
     * ReferralService: классический Spring-цикл. {@code @Lazy} разрывает его на старте.
     */
    private final NotificationService notificationService;
    /**
     * REQUIRES_NEW для каждого вызова в afterCommit-хуке. Spring Synchronization docs прямо
     * рекомендуют использовать именно REQUIRES_NEW в afterCommit: оригинальная транзакция
     * уже закоммичена, но её thread-local контекст ещё привязан к потоку — REQUIRED бы
     * попытался реюзать "уже закрытую" транзакцию. REQUIRES_NEW гарантированно открывает
     * чистую новую транзакцию, которая видит все committed данные outer-tx (READ_COMMITTED ок,
     * потому что outer уже committed) и изолирует rollback одной стороны от другой.
     */
    private final TransactionTemplate bonusTxTemplate;

    public ReferralService(ReferralRepository referralRepository,
                           PaymentRepository paymentRepository,
                           CustomerRepository userRepository,
                           AccessService accessService,
                           @Lazy NotificationService notificationService,
                           PlatformTransactionManager transactionManager) {
        this.referralRepository = referralRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
        this.notificationService = notificationService;
        // REQUIRES_NEW: внутри afterCommit-хука thread всё ещё связан с уже закоммиченной
        // outer-tx. Spring docs рекомендуют именно REQUIRES_NEW для любых tx-операций из
        // afterCommit. Гарантирует чистую новую транзакцию с видимостью всех committed данных.
        this.bonusTxTemplate = new TransactionTemplate(transactionManager);
        this.bonusTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Регистрирует связь "A пригласил B". Вызывается при /start ref_A от юзера B.
     * Идемпотентно: если B уже кем-то приглашён, второй вызов ничего не делает.
     *
     * @return true — связь создана; false — пропущена (self-referral, уже приглашён, или невалидно)
     */
    @Transactional
    public boolean recordReferral(Long referrerTelegramId, Long referredTelegramId) {
        if (referrerTelegramId == null || referredTelegramId == null) {
            return false;
        }
        if (referrerTelegramId.equals(referredTelegramId)) {
            log.debug("Self-referral skipped for telegramId={}", referrerTelegramId);
            return false;
        }
        if (referralRepository.existsByReferredTelegramId(referredTelegramId)) {
            log.debug("Referral for telegramId={} already exists, ignoring new referrer {}",
                    referredTelegramId, referrerTelegramId);
            return false;
        }
        Referral referral = Referral.builder()
                .referrerTelegramId(referrerTelegramId)
                .referredTelegramId(referredTelegramId)
                .build();
        try {
            referralRepository.save(referral);
            log.info("Recorded referral: referrer={} → referred={}", referrerTelegramId, referredTelegramId);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Гонка: другой запрос успел вставить строку. Это ок, просто не создаём дубль.
            log.debug("Referral race lost for telegramId={}", referredTelegramId);
            return false;
        }
    }

    /**
     * Проверяет, нужно ли наградить реферера за оплату приглашённого, и награждает если да.
     * Вызывается из PaymentProcessingService после {@code payment.setStatus(SUCCEEDED); save}.
     * <p>
     * Условия награждения:
     * <ol>
     *   <li>У B есть referral-запись с paid_at=NULL (то есть его пригласил кто-то)</li>
     *   <li>Это первый в жизни B оплаченный платёж (count == 1 по SUCCEEDED ∪ REFUNDED).
     *       Учитываем REFUNDED, чтобы закрыть эксплойт pay→refund→ref→pay: иначе после
     *       /refund статус уходит в REFUNDED, count по SUCCEEDED становится 0, и следующий
     *       платёж снова трактуется как "первый" — группа друзей могла бы циклично
     *       фармить +14d реферер-бонусы, упираясь лишь в cap 10/мес.</li>
     *   <li>У A за последние 30 дней меньше {@value #MONTHLY_REWARDED_CAP} награждённых рефералов</li>
     * </ol>
     * <p>
     * Idempotency: conditional UPDATE на paid_at=NULL → NOW() гарантирует, что если параллельные
     * вебхуки (дубликат-delivery) оба дошли до этого метода, только один проставит paid_at и
     * только он начислит бонусы.
     *
     * @param triggeringPaymentId id первой SUCCEEDED-оплаты. Сохраняется в Referral.payment_id
     *                            и используется при refund для отката бонусов у обоих.
     */
    @Transactional
    public void maybeAwardReferral(Long referredTelegramId, Long triggeringPaymentId) {
        Optional<Referral> referralOpt = referralRepository.findByReferredTelegramId(referredTelegramId);
        if (referralOpt.isEmpty()) {
            return;
        }
        Referral referral = referralOpt.get();
        if (referral.getPaidAt() != null) {
            // Уже награждали — повторная оплата B не триггерит новое награждение.
            return;
        }

        // Проверяем, что это первая в жизни B оплата (включая возвращённые). Вызывающая
        // сторона уже сохранила новый платёж со статусом SUCCEEDED, так что count=1 = "только что";
        // count>=2 означает, что у B уже был прошлый платёж (неважно, SUCCEEDED или REFUNDED) —
        // и награда не положена.
        long anyPaidCount = paymentRepository.countByTelegramIdAndStatusIn(
                referredTelegramId,
                List.of(Payment.Status.SUCCEEDED, Payment.Status.REFUNDED));
        if (anyPaidCount != 1) {
            // 0 — вызвали до save (баг в вызывающей стороне). >1 — это не первая оплата,
            // награда не положена (в т.ч. если прошлые были возвращены — это закрывает
            // эксплойт "pay → /refund → /start ref_X → pay снова").
            log.debug("Skip referral reward for telegramId={}: anyPaidCount={} (incl. refunded)",
                    referredTelegramId, anyPaidCount);
            return;
        }

        // Anti-abuse cap: скользящее окно 30 дней.
        LocalDateTime capWindow = LocalDateTime.now().minusDays(30);
        long recentRewards = referralRepository.countByReferrerTelegramIdAndPaidAtAfter(
                referral.getReferrerTelegramId(), capWindow);
        if (recentRewards >= MONTHLY_REWARDED_CAP) {
            log.warn("Referral cap hit for referrerId={} ({} awards in 30d), skipping reward for referredId={}",
                    referral.getReferrerTelegramId(), recentRewards, referredTelegramId);
            return;
        }

        // Conditional UPDATE — если кто-то уже проставил paid_at, мы не начисляем повторно.
        int updated = referralRepository.markRewardedIfNotYet(
                referredTelegramId, LocalDateTime.now(),
                REFERRER_BONUS_DAYS, REFEREE_BONUS_DAYS, triggeringPaymentId);
        if (updated == 0) {
            log.debug("Referral for telegramId={} was concurrently marked, skipping bonuses", referredTelegramId);
            return;
        }

        // Начисление бонусов откладываем до AFTER_COMMIT outer-транзакции (та, что открыл
        // PaymentProcessingService.handleSucceeded). Две причины:
        //
        // 1. activatePaidSubscription сам @Transactional. Если он бросает внутри outer-tx,
        //    Spring помечает её rollback-only — даже если мы catch-нем, на выходе из outer-tx
        //    кинется UnexpectedRollbackException и **весь платёж откатится**.
        //
        // 2. Если бы мы делали REQUIRES_NEW, новая транзакция не видела бы только что
        //    созданного/обновлённого Customer реферала (READ_COMMITTED isolation, outer ещё
        //    не закоммичен) — и бонус бы скипался с warn "Customer not found".
        //
        // afterCommit-хук решает оба вопроса: запускается уже после успешного commit outer-tx,
        // открывает свою чистую транзакцию через bonusTxTemplate, видит все committed данные,
        // failure локализован.
        //
        // Если outer-tx откатывается по другой причине — afterCommit просто не вызовется,
        // а conditional UPDATE с paid_at тоже откатится (он внутри outer-tx). Consistent.
        Long referrerId = referral.getReferrerTelegramId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    applyBonuses(referredTelegramId, referrerId);
                }
            });
        } else {
            // Нет outer-транзакции (вызов не из payment-flow, например, из теста или ручного
            // тригера) — активируем сразу, в собственных транзакциях bonusTxTemplate.
            applyBonuses(referredTelegramId, referrerId);
        }
    }

    /**
     * Реальное начисление бонусов после commit outer payment-tx.
     * Каждый из двух вызовов в своей транзакции через {@link #bonusTxTemplate} —
     * сбой одной стороны (AccessProvider лагает) не утаскивает другую и не задевает payment.
     * Если хоть один упал, алертим владельца: paid_at уже проставлен (в outer-tx, она
     * закоммичена), автоповтора не будет — нужен ручной /extend.
     */
    private void applyBonuses(Long referredTelegramId, Long referrerTelegramId) {
        boolean refereeFailed = false;
        boolean referrerFailed = false;
        String firstException = null;

        // Referee (B): стекаем +5 дней поверх только что купленной подписки.
        try {
            bonusTxTemplate.executeWithoutResult(status -> {
                Optional<Customer> refereeOpt = userRepository.findByTelegramId(referredTelegramId);
                if (refereeOpt.isPresent()) {
                    Customer referee = refereeOpt.get();
                    accessService.addDays(
                            referredTelegramId, referee.getTelegramUsername(), REFEREE_BONUS_DAYS,
                            com.orchestra.subscriptions.model.SubscriptionLedger.Kind.BONUS);
                } else {
                    log.warn("Referee Customer not found after payment for telegramId={}, skipping referee bonus",
                            referredTelegramId);
                }
            });
        } catch (Exception e) {
            refereeFailed = true;
            firstException = e.getMessage();
            log.error("Failed to apply referee bonus for referredId={}", referredTelegramId, e);
        }

        // Referrer (A): если у него нет Customer — создаётся новый с 14 днями.
        try {
            bonusTxTemplate.executeWithoutResult(status -> {
                Optional<Customer> referrerOpt = userRepository.findByTelegramId(referrerTelegramId);
                String referrerUsername = referrerOpt.map(Customer::getTelegramUsername).orElse(null);
                accessService.addDays(
                        referrerTelegramId, referrerUsername, REFERRER_BONUS_DAYS,
                        com.orchestra.subscriptions.model.SubscriptionLedger.Kind.BONUS);
            });
        } catch (Exception e) {
            referrerFailed = true;
            if (firstException == null) firstException = e.getMessage();
            log.error("Failed to apply referrer bonus for referrerId={}", referrerTelegramId, e);
        }

        if (refereeFailed || referrerFailed) {
            notificationService.notifyOwnerReferralBonusError(
                    referrerTelegramId, referredTelegramId,
                    refereeFailed, referrerFailed, firstException);
        } else {
            log.info("Awarded referral: referrer={} (+{}d), referred={} (+{}d)",
                    referrerTelegramId, REFERRER_BONUS_DAYS,
                    referredTelegramId, REFEREE_BONUS_DAYS);
        }
    }

    /** Статистика для /referral UI. */
    public ReferralStats getStats(Long telegramId) {
        long invited = referralRepository.countByReferrerTelegramId(telegramId);
        long rewarded = referralRepository.countByReferrerTelegramIdAndPaidAtNotNull(telegramId);
        long capUsed = referralRepository.countByReferrerTelegramIdAndPaidAtAfter(
                telegramId, LocalDateTime.now().minusDays(30));
        long capRemaining = Math.max(0, MONTHLY_REWARDED_CAP - capUsed);
        long totalBonusDays = rewarded * REFERRER_BONUS_DAYS;
        return new ReferralStats(invited, rewarded, totalBonusDays, capRemaining);
    }

    public record ReferralStats(long invited, long rewarded, long totalBonusDays, long capRemaining) {}
}
