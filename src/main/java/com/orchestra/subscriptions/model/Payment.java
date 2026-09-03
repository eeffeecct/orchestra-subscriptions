package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    /**
     * Сентинел в {@link #planId} для покупки «+1 устройство» (докупка слота к активной
     * подписке, {@code app.device-addon}). Такой платёж НЕ является планом: webhook-обработчики
     * ({@code PaymentProcessingService}) ветвятся на нём ДО {@code findPlan} и вместо
     * начисления дней инкрементят {@code customers.device_limit}. Выбран строкой, а не
     * отдельной колонкой: planId NOT NULL в схеме, а «добавить kind-колонку» ради одного
     * типа платежа — лишняя миграция.
     */
    public static final String DEVICE_ADDON_PLAN_ID = "device_addon";

    /** Платёж-докупка «+1 устройство», а не тарифный план. */
    public boolean isDeviceAddon() {
        return DEVICE_ADDON_PLAN_ID.equals(planId);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "yookassa_payment_id", unique = true)
    private String yookassaPaymentId;

    /**
     * Invoice id from CryptoBot (Crypto Pay API). Filled for Provider.CRYPTOBOT payments only.
     * YooKassa payments leave this null. Unique so webhook lookup by invoice id can't collide.
     */
    @Column(name = "crypto_invoice_id", unique = true)
    private String cryptoInvoiceId;

    /**
     * {@code telegram_payment_charge_id} from a Telegram Stars payment.
     * Set only for {@link Provider#STARS} rows. Unique — each Stars charge has a unique id,
     * so we use it for idempotency/refund lookup. Lookup on successful_payment still happens
     * by invoice payload (our payment id) — this column is an audit/refund handle.
     */
    @Column(name = "telegram_charge_id", unique = true)
    private String telegramChargeId;

    /**
     * Which payment gateway this row belongs to. Defaults to YOOKASSA in {@link #onCreate()}
     * so that rows created before this column existed (and backfilled by Hibernate as NULL)
     * still behave correctly when read by YooKassa-only code paths.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private Provider provider;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "telegram_username")
    private String telegramUsername;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    /**
     * Id ступени устройств ({@code app.device-tiers}), выбранной при покупке. NULL —
     * legacy-платёж без выбора устройств (базовая цена плана, лимит не назначается).
     * На webhook SUCCEEDED из неё выставляется {@code customers.device_limit}.
     */
    @Column(name = "device_tier_id", length = 64)
    private String deviceTierId;

    @Column(name = "amount_kopeks", nullable = false)
    private long amountKopeks;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Момент возврата. NULL если платёж не возвращён. Используется как audit trail. */
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    /**
     * Фактически возвращённая сумма в копейках. NULL если платёж не возвращён.
     * В v1 возвращаем только 100% платежа, так что это поле равно {@link #amountKopeks}.
     * Хранится отдельно, чтобы поддержать partial refund в v2 без миграции.
     */
    @Column(name = "refund_amount_kopeks")
    private Long refundAmountKopeks;

    /**
     * Нормализованный (uppercase) промокод, применённый к этому платежу. NULL если
     * промо не использовался. Используется для двух сценариев:
     * <ol>
     *   <li>Аудит: который код привёл к скидке (чтобы /promo info показывал нормальную
     *       историю даже если юзер удалил приложение).</li>
     *   <li>Резервирование: при создании PENDING код не только валидируется, но и
     *       резервируется — заводится {@code PromoUsage} и растёт {@code used_count}
     *       (см. {@code PromoCodeService#reserveForCheckout}). Иначе последний слот
     *       {@code max_uses} успевали занять параллельные счета. Невыкупленные резервы
     *       освобождает {@code releaseDeadReservations}, а на webhook SUCCEEDED
     *       {@code recordUsageOnSucceeded} лишь дозаписывает то, что не зарезервировали.</li>
     * </ol>
     */
    @Column(name = "promo_code_used", length = 64)
    private String promoCodeUsed;

    /**
     * Платёж создан планировщиком автопродления ({@code AutoRenewService}), а не рукой юзера.
     * <p>
     * Влияет на текст DM в {@code PaymentProcessingService.handleSucceeded}: автосписание
     * должно объяснить, что деньги списались сами, и дать ссылку на управление. Отличить по
     * {@code provider}/{@code plan_id} нельзя — у ручной покупки картой они те же. Флаг читает
     * и реконсилер, догоняющий застрявшую активацию, поэтому он в БД, а не transient-поле.
     */
    @Column(name = "autorenew", nullable = false)
    private boolean autorenew;

    /**
     * В какую подписку идут эти деньги. {@code null} — платёж создан до появления
     * нескольких подписок: резолвится в текущую подписку человека.
     */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    /**
     * {@code EXTEND} — продлить подписку из {@link #subscriptionId}; {@code NEW} — создать
     * новую (id проставляется при активации). {@code null} — как EXTEND в текущую.
     * Строкой, а не enum: значение приходит из платёжного флоу и переживает деплои.
     */
    @Column(name = "target_kind")
    private String targetKind;

    public enum Status {
        PENDING, SUCCEEDED, CANCELED, FAILED, REFUNDED
    }

    public enum Provider {
        YOOKASSA, CRYPTOBOT, STARS
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = Status.PENDING;
        if (provider == null) provider = Provider.YOOKASSA;
    }
}
