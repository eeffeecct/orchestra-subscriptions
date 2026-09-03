package com.orchestra.subscriptions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Одна подписка человека. У человека их может быть несколько, но «текущая» — ровно одна
 * (гарантируется частичным уникальным индексом {@code uk_subscriptions_one_current}).
 * <p>
 * <b>Часы разделены по состоянию.</b> Текущая подписка живёт по {@link #expiresAt};
 * припаркованная — по замороженному остатку {@link #balanceSeconds}, а её {@code expiresAt}
 * равен {@code null}. Это главное свойство модели: все существующие запросы по датам
 * (напоминания об истечении, автопродление, winback) физически не могут зацепить
 * припаркованную подписку — не потому, что кто-то добавил фильтр, а потому что у неё нет
 * даты. Инвариант закреплён CHECK-ограничением {@code ck_subscriptions_clock}.
 * <p>
 * ⚠️ {@code is_current = true} при {@code expiresAt = null} — ЛЕГАЛЬНО: так выглядит
 * подписка после полного возврата денег.
 * <p>
 * На этом этапе таблица только наполняется (зеркало {@code customers}); читатели переходят
 * на неё следующим этапом. Полный план — {@code docs/multi-subscription-plan.md}.
 */
@Entity
@Table(name = "subscriptions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    /** Откуда взялась подписка. Нужна, чтобы счётчики триалов не прыгали при переключениях. */
    public enum Origin {
        TRIAL, PAID, GRANT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    /**
     * Номер подписки внутри человека (1, 2, 3…). Аллоцируется из
     * {@code customers.subscription_seq} под блокировкой строки, НИКОГДА не из {@code count(*)}:
     * переиспользованный номер дал бы 409-recovery в {@code AccessProvider.createUser} шанс
     * «усыновить» чужой аккаунт в панели.
     */
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "provider_username")
    private String providerUsername;

    @Column(name = "subscription_link", length = 500)
    private String subscriptionLink;

    @Column(name = "subscription_token")
    private String subscriptionToken;

    /** Ровно одна текущая на человека — частичный уникальный индекс в БД. */
    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private Customer.Status state;

    /** Только у текущей подписки. {@code null} у припаркованной — и после полного возврата. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Только у припаркованной: замороженный остаток оплаченного времени. */
    @Column(name = "balance_seconds")
    private Long balanceSeconds;

    @Column(name = "device_limit")
    private Integer deviceLimit;

    @Column(name = "device_tier_id", length = 64)
    private String deviceTierId;

    @Column(name = "autorenew_enabled", nullable = false)
    private boolean autorenewEnabled;

    @Column(name = "autorenew_plan_id")
    private String autorenewPlanId;

    @Column(name = "autorenew_device_tier_id", length = 64)
    private String autorenewDeviceTierId;

    @Column(name = "autorenew_last_failure_at")
    private LocalDateTime autorenewLastFailureAt;

    @Column(name = "notified_24h", nullable = false)
    private boolean notified24h;

    @Column(name = "notified_1h", nullable = false)
    private boolean notified1h;

    @Column(name = "winback_sent", nullable = false)
    private boolean winbackSent;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin")
    private Origin origin;

    /** Подпись в переключателе бота. */
    @Column(name = "label", length = 64)
    private String label;

    /**
     * Транзакционный outbox для AccessProvider: намерение пишется в ТОЙ ЖЕ транзакции, что и
     * состояние подписки, поэтому падение между коммитом в БД и вызовом панели не теряется —
     * реконсилер увидит незакрытое намерение и доведёт его. Заполняется на этапе переключений.
     */
    @Column(name = "provider_intent")
    private String providerIntent;

    @Column(name = "provider_intent_at")
    private LocalDateTime providerIntentAt;

    @Column(name = "provider_attempts", nullable = false)
    private int providerAttempts;

    @Column(name = "provider_synced_at")
    private LocalDateTime providerSyncedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "parked_at")
    private LocalDateTime parkedAt;

    @Column(name = "last_activated_at")
    private LocalDateTime lastActivatedAt;

    /**
     * Когда последний раз напоминали, что подписка оплачена, но не включена.
     * <p>
     * Хранится, а не выводится из журнала: единственной защитой от повтора было бы «возраст
     * оплаты попал в часовое окно», а планировщик стартует заново при каждом деплое — и
     * человек получал бы письмо на каждый рестарт.
     */
    @Column(name = "parked_reminded_at")
    private LocalDateTime parkedRemindedAt;
}
