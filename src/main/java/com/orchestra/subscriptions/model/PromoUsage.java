package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Факт применения промокода к конкретному платежу. Создаётся в одной транзакции с
 * SUCCEEDED-переходом платежа (см. {@code PaymentProcessingService#handle*}, через
 * {@code PromoCodeService.recordUsageForPayment}).
 * <p>
 * UNIQUE(payment_id) — один промо на платёж. {@code reverted_at} ставится при refund
 * того же платежа: служит idempotency-маркером + позволяет /promo info показывать
 * актуальную статистику применений.
 */
@Entity
@Table(name = "promo_usages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromoUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "promo_code_id", nullable = false)
    private Long promoCodeId;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    /** UNIQUE — 1 промо на 1 платёж. */
    @Column(name = "payment_id", nullable = false, unique = true)
    private Long paymentId;

    /** Фактическая сумма скидки в копейках, применённая к этому платежу. */
    @Column(name = "discount_kopeks", nullable = false)
    private long discountKopeks;

    @Column(name = "used_at", nullable = false)
    private LocalDateTime usedAt;

    /** NULL = активный usage. Заполняется при refund. */
    @Column(name = "reverted_at")
    private LocalDateTime revertedAt;

    @PrePersist
    protected void onCreate() {
        if (usedAt == null) usedAt = LocalDateTime.now();
    }
}
