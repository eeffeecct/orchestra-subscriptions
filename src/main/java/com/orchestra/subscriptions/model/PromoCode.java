package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Промокод на скидку. Создаётся через админ-команду {@code /promo create}, применяется
 * юзером при выборе способа оплаты в Mini App. После SUCCEEDED-оплаты создаётся {@link PromoUsage},
 * счётчик {@link #usedCount} инкрементится. При refund — откатывается через {@code reverted_at}.
 *
 * @see com.orchestra.subscriptions.service.PromoCodeService
 */
@Entity
@Table(name = "promo_codes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromoCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    /** Хранится в верхнем регистре. Lookup в сервисе тоже uppercase. */
    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 16)
    private DiscountType discountType;

    /**
     * Для {@link DiscountType#PERCENT} — целое 1..99 (процент скидки).
     * Для {@link DiscountType#FIXED}  — копейки (фиксированная скидка с цены).
     */
    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    /** NULL = безлимит. */
    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    /** NULL = бессрочный. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Soft-disable. {@code /promo disable} ставит false; строка остаётся для аудита. */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Telegram ID админа, создавшего код. Для аудита. */
    @Column(name = "created_by_telegram_id")
    private Long createdByTelegramId;

    public enum DiscountType {
        PERCENT, FIXED
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
