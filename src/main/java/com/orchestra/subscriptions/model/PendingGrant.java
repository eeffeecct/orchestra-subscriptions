package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Отложенный приз: дни подписки, зарезервированные админом для победителя конкурса,
 * у которого ещё нет записи в {@code customers}. Сработает в момент, когда юзер
 * впервые откроет бот или Mini App — см. {@code PendingGrantService.applyIfAny}.
 * <p>
 * Привязан к {@link #username} (lower-case), не к {@code telegram_id}: админ в канале
 * знает только @username, а резолвить незнакомый @username в {@code id} через Bot API
 * нельзя — Telegram не отдаёт чужие id ботам, пока юзер сам с ботом не заговорил.
 */
@Entity
@Table(name = "pending_grants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingGrant {

    /**
     * Запасной срок годности, если {@link #expiresAt} не проставили явно. Основное значение
     * задаёт {@code app.retention.pending-grant-days} и ставит {@code PendingGrantService};
     * эта константа нужна только чтобы ни одна строка не легла в БД с {@code expires_at = NULL} —
     * колонка NOT NULL, а NULL молча провалил бы фильтр «не просрочен» в выборках.
     */
    private static final int DEFAULT_TTL_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lowercased Telegram username без @. */
    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false)
    private int days;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    /**
     * Когда приз сгорает. Проставляется при создании как {@code granted_at +
     * app.retention.pending-grant-days} (по умолчанию 30 дней).
     * <p>
     * Зачем: грант привязан к @username, а не к telegram_id, и до этой правки висел вечно —
     * то есть вечно хранил ник человека, который мог и не завести аккаунт, и вечно оставался
     * готовым сработать на ЛЮБОГО, кто когда-нибудь займёт этот ник. Просроченные гранты
     * выпадают из выборок ({@code PendingGrantRepository}) и удаляются плановой чисткой
     * ({@code RetentionCleanupService}).
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Telegram-id админа, выдавшего приз. NULLable — допускает автогенерируемые гранты. */
    @Column(name = "granted_by_telegram_id")
    private Long grantedByTelegramId;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean used = false;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** Фактический получатель приза (может отличаться от {@link #username}, если юзер
     *  сменил ник между granted_at и used_at — приз достанется новому владельцу ника). */
    @Column(name = "used_by_telegram_id")
    private Long usedByTelegramId;

    @PrePersist
    protected void onCreate() {
        if (grantedAt == null) grantedAt = LocalDateTime.now();
        if (expiresAt == null) expiresAt = grantedAt.plusDays(DEFAULT_TTL_DAYS);
    }
}
