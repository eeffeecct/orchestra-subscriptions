package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * JPA optimistic lock. Защищает от потери обновлений при параллельных
     * webhook'ах (например, YK и Stars одновременно для одного юзера) — второй
     * save() поверх устаревшей копии получит OptimisticLockingFailureException
     * вместо тихой перезаписи.
     */
    @Version
    private Long version;

    @Column(name = "telegram_id", unique = true, nullable = false)
    private Long telegramId;

    @Column(name = "telegram_username")
    private String telegramUsername;

    @Column(name = "provider_username", unique = true)
    private String providerUsername;

    @Column(name = "subscription_link", length = 500)
    private String subscriptionLink;

    /**
     * Opaque AccessProvider-токен, извлечённый из {@link #subscriptionLink} ({@code .../sub/<token>}).
     * Дублируется отдельной индексированной колонкой, чтобы {@code /sub/<token>} мог
     * найти юзера одним лукапом (детект устройств). Поддерживается автоматически в
     * {@link #syncSubscriptionToken()} при каждом persist/update; для существующих строк —
     * backfill в миграции 023.
     */
    @Column(name = "subscription_token")
    private String subscriptionToken;

    /**
     * Лимит устройств (HWID-слотов) текущего тарифа. NULL = legacy-юзер без выбранной
     * ступени — действует {@code app.default-device-limit} (0 = не ограничивать).
     * Назначается при покупке со ступенью ({@code app.device-tiers}) и при триале
     * ({@code app.trial-device-limit}).
     */
    @Column(name = "device_limit")
    private Integer deviceLimit;

    /** Id ступени устройств последней покупки ({@code app.device-tiers}). NULL = legacy. */
    @Column(name = "device_tier_id", length = 64)
    private String deviceTierId;

    /**
     * Аллокатор номеров подписок этого человека. Следующая подписка получает этот номер,
     * после чего счётчик растёт. НИКОГДА не считать номер как {@code count(*)}: удалённая
     * подписка освободила бы номер, а переиспользованное имя аккаунта в AccessProvider дало бы
     * 409-recovery в {@code AccessProvider.createUser} шанс «усыновить» чужой аккаунт.
     */
    @Column(name = "subscription_seq")
    private Integer subscriptionSeq;

    /**
     * Когда человек израсходовал бесплатный триал. Сегодня признаком служит сам факт
     * существования строки в {@code customers}, но это перестанет работать, как только у
     * человека появится несколько подписок: припарковав все, он «обнулял» бы себя и
     * заново получал триал. Дата — явные ворота, не зависящие от числа подписок.
     */
    @Column(name = "trial_used_at")
    private LocalDateTime trialUsedAt;

    /** Ступень устройств для автосписания — вместе с {@link #autorenewPlanId}. */
    @Column(name = "autorenew_device_tier_id", length = 64)
    private String autorenewDeviceTierId;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "notified_24h", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean notified24h = false;

    @Column(name = "notified_1h", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean notified1h = false;

    /** Отправлено ли post-expiry напоминание этой подписке. Сбрасывается при новой оплате. */
    @Column(name = "winback_sent", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean winbackSent = false;

    /**
     * YooKassa {@code payment_method.id} — сохранённая карта для рекуррентных списаний.
     * Null = карта не привязана (пользователь платил разово или через crypto/Stars).
     */
    @Column(name = "autorenew_method_id")
    private String autorenewMethodId;

    /** Пользовательский toggle. Независим от {@link #autorenewMethodId}: юзер может
     *  временно выключить автопродление, а потом включить без перевыпуска карты. */
    @Column(name = "autorenew_enabled", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean autorenewEnabled = false;

    /** Id тарифа, который будет автосписан. Ставится на момент успешной оплаты
     *  с {@code save_payment_method=true}. */
    @Column(name = "autorenew_plan_id")
    private String autorenewPlanId;

    /** Момент последней неудачной попытки автосписания. Используется для one-shot
     *  политики: в течение 25 часов после failure планировщик не делает повторный charge. */
    @Column(name = "autorenew_last_failure_at")
    private LocalDateTime autorenewLastFailureAt;

    /**
     * Email пользователя (opt-in через capture-баннер в Mini App). NULL =
     * не вводил. UNIQUE на уровне БД для magic-link резолва.
     */
    @Column(name = "email", unique = true)
    private String email;

    /**
     * Момент подтверждения email через клик по magic-link. NULL = ввёл, но
     * не подтвердил. Важное (refund, восстановление доступа) шлём только
     * на verified, маркетинг — на любой введённый.
     */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /**
     * 152-ФЗ audit-trail: timestamp момента, когда юзер дал явное согласие на
     * обработку email (чекбокс в Mini App). NULL до opt-in. При смене email
     * на новый — обновляется (юзер согласился ещё раз через тот же чекбокс).
     * При DELETE — обнуляется вместе с email и email_verified_at (отзыв согласия).
     */
    @Column(name = "email_consent_at")
    private LocalDateTime emailConsentAt;

    /**
     * Версия web-ЛК сессии для server-side revocation stateless signed cookies.
     * Инкрементится при смене или удалении email — выданные до этого cookies
     * становятся невалидными ({@code AccountSessionService.parse} включает sv в
     * payload и сверяет с актуальным; mismatch → 401).
     * <p>
     * Stateless сессии иначе не отзываются: смена секрета разлогинила бы всех.
     * session_version даёт per-user revocation без общего blacklist'а.
     * <p>
     * Default 0 — существующие cookies pre-deploy парсятся как sv=0 (graceful
     * fallback в parse), совпадают со значением 0 в БД, разлогина не происходит.
     * Миграция 020. См. {@code project_audit_2026_05_24.md} (item H3) и сегодняшний
     * аудит ({@code project_audit_2026_05_29.md}).
     */
    @Column(name = "session_version", nullable = false)
    private Integer sessionVersion;

    public enum Status {
        ACTIVE, DISABLED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = Status.ACTIVE;
        // Защита от NPE если builder не задал sessionVersion — миграция выставит default 0
        // для существующих строк, но новые юзеры через Customer.builder() могут пропустить.
        if (sessionVersion == null) sessionVersion = 0;
        syncSubscriptionToken();
    }

    /**
     * Держит {@link #subscriptionToken} в согласии с {@link #subscriptionLink} на каждом
     * persist/update — ни один каллер (createTrial, activatePaid, fixSubscriptionLink…)
     * не обязан помнить про токен. Lifecycle-хук, а не setter-логика: Lombok {@code @Data}
     * генерирует setter'ы, переопределять их вручную хрупко.
     */
    @PreUpdate
    protected void syncSubscriptionToken() {
        subscriptionToken = com.orchestra.subscriptions.util.SubscriptionLinks.extractToken(subscriptionLink);
    }
}
