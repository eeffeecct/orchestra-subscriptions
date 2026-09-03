package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ReferralRepository extends JpaRepository<Referral, Long> {

    Optional<Referral> findByReferredTelegramId(Long referredTelegramId);

    /** Поиск по triggering-платежу для отката бонусов при refund. */
    Optional<Referral> findByPaymentId(Long paymentId);

    boolean existsByReferredTelegramId(Long referredTelegramId);

    /** Всего приглашённых юзером (любых — с награждением и без). */
    long countByReferrerTelegramId(Long referrerTelegramId);

    /** Количество награждённых рефералов у юзера. Для /referral stats. */
    long countByReferrerTelegramIdAndPaidAtNotNull(Long referrerTelegramId);

    /** Anti-abuse cap: сколько реферер получил наград за указанный период. */
    long countByReferrerTelegramIdAndPaidAtAfter(Long referrerTelegramId, LocalDateTime since);

    /**
     * Idempotent-award: UPDATE paid_at только если он ещё NULL. Возвращает число обновлённых
     * строк. 1 = мы первые, можем начислять бонусы. 0 = уже обработано / нет строки.
     */
    @Modifying
    @Query("UPDATE Referral r SET r.paidAt = :now, r.rewardBonusDays = :rewardDays, " +
            "r.refereeBonusDays = :refereeDays, r.paymentId = :paymentId " +
            "WHERE r.referredTelegramId = :referredId AND r.paidAt IS NULL")
    int markRewardedIfNotYet(@Param("referredId") Long referredTelegramId,
                             @Param("now") LocalDateTime now,
                             @Param("rewardDays") int rewardDays,
                             @Param("refereeDays") int refereeDays,
                             @Param("paymentId") Long paymentId);
}
