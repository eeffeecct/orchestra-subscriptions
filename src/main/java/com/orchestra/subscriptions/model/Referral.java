package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Связь "реферер (A) — приглашённый (B)". Создаётся при /start ref_A от B.
 * Когда B совершает свою первую SUCCEEDED-оплату, paid_at заполняется, а обоим
 * пользователям начисляется бонус (14 дней A, 5 дней B). Конкретные числа
 * хранятся в колонках reward_bonus_days / referee_bonus_days для аудита —
 * если в будущем изменим политику, старые награды останутся с исходными суммами.
 */
@Entity
@Table(name = "referrals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "referrer_telegram_id", nullable = false)
    private Long referrerTelegramId;

    /** UNIQUE: каждый юзер может быть приглашён ровно одним. */
    @Column(name = "referred_telegram_id", nullable = false, unique = true)
    private Long referredTelegramId;

    @Column(name = "referred_at", nullable = false)
    private LocalDateTime referredAt;

    /**
     * NULL = награды ещё не начислены. Единственное условие: B совершил первую
     * SUCCEEDED-оплату. Используется как idempotency-маркер — conditional UPDATE
     * на paid_at=NULL → NOW() гарантирует, что параллельные вебхуки не начислят
     * награду дважды.
     */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "reward_bonus_days")
    private Integer rewardBonusDays;

    @Column(name = "referee_bonus_days")
    private Integer refereeBonusDays;

    /**
     * id строки в payments, которая триггернула награждение (первая SUCCEEDED-оплата B).
     * Нужен для отката бонусов при refund этой же оплаты: когда PaymentProcessingService
     * обрабатывает refund.succeeded, он ищет Referral по payment_id и вычитает
     * reward_bonus_days / referee_bonus_days у обоих пользователей.
     * <p>
     * NULL для строк, созданных до миграции 008 — для них revert невозможен
     * (старые награды «замораживаются»), новые циклы invite → pay → refund
     * попадают под новую логику.
     */
    @Column(name = "payment_id")
    private Long paymentId;

    /**
     * Timestamp отката бонусов. NULL = ещё не возвращали. Используется как idempotency-маркер:
     * повторный refund-webhook (дубликат delivery) не откатит бонусы второй раз.
     */
    @Column(name = "bonus_reverted_at")
    private LocalDateTime bonusRevertedAt;

    @PrePersist
    protected void onCreate() {
        if (referredAt == null) referredAt = LocalDateTime.now();
    }
}
