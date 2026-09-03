package com.orchestra.subscriptions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Журнал изменений оплаченного времени: одна строка на каждое событие, которое двигало срок
 * подписки. Только append — строки никогда не обновляются и не удаляются.
 * <p>
 * <b>Зачем.</b> Сейчас узнать «почему у юзера такой срок» можно только по косвенным следам
 * в логах, которые ротируются. Каждый разбор жалобы «у меня пропали дни» — это археология.
 * Леджер даёт прямой ответ: кто, когда и на сколько подвинул срок, и каким платежом это
 * вызвано. Отдельная ценность — доказательство, что система НЕ теряет дни: это база
 * сравнения для всех последующих правок сроков.
 * <p>
 * <b>Почему секунды, а не дни</b> — см. {@link com.orchestra.subscriptions.service.SubscriptionTime}.
 * <p>
 * {@code subscriptionId} — подписка, к которой относится событие. Заполняется всегда, когда
 * подписку удалось определить: явным адресом (минт, заморозка/разморозка, зачисление и возврат
 * по замороженной) либо подстановкой текущей подписки в
 * {@link com.orchestra.subscriptions.service.SubscriptionLedgerService}. {@code null} остаётся у истории
 * людей без строки в {@code subscriptions} — записи, сделанные до появления таблицы, и те,
 * кого не догнал бэкфилл зеркала.
 * <p>
 * ⚠️ Колонка НЕ резервная: на ней построен запрос напоминаний о замороженных деньгах
 * ({@code ParkedFundsReminder} ищет последнюю оплату в подписку). Считать её «заведённой на
 * будущее» и перестать заполнять — значит молча выключить эти напоминания.
 * <p>
 * ⚠️ Без FK на {@code customers} — конвенция репозитория ({@code referrals}, {@code magic_links},
 * {@code user_devices} тоже держат «голый» {@code telegram_id}): журнал должен переживать
 * удаление юзера, иначе мы теряем историю ровно там, где она нужнее всего.
 */
@Entity
@Table(name = "subscription_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionLedger {

    /** Что подвинуло срок. Строкой в БД — читаемо глазами при разборе инцидента. */
    public enum Kind {
        /** Выдача бесплатного триала. */
        TRIAL,
        /** Оплата тарифа юзером (любой провайдер). */
        PURCHASE,
        /** Списание по привязанной карте. */
        AUTORENEW,
        /** Реферальный бонус (приглашающему или приглашённому). */
        BONUS,
        /** Подарок владельца (pending grant). */
        GRANT,
        /** Ручное продление админом (/extend, /extend_all). */
        ADMIN_EXTEND,
        /** Возврат денег — срок урезан или подписка отключена. */
        REFUND,
        /** Откат реферального бонуса после возврата. */
        BONUS_REVERT,
        /** Срок вышел, доступ отключён планировщиком. */
        EXPIRE,
        /**
         * Подписка заморожена при переключении: дата превратилась в остаток, дни встали.
         * {@code delta_seconds == 0} — время не выдавали и не забирали, поменялась только
         * форма хранения.
         */
        PARK,
        /** Подписка разморожена при переключении: остаток снова стал датой. {@code delta == 0}. */
        RESUME,
        /** Ручная правка/страховка — всё, что не попало в остальные категории. */
        ADJUST
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Подписка, к которой относится событие. {@code null} — адрес определить не удалось. */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private Kind kind;

    /**
     * На сколько секунд событие подвинуло срок: {@code +} — добавили время, {@code −} — забрали.
     * Обычное «протухание» времени здесь НЕ отражается (иначе пришлось бы писать строку
     * каждую секунду), поэтому сумма дельт НЕ равна остатку — она равна всему когда-либо
     * выданному времени. Остаток на момент события — в {@link #balanceAfterSeconds}.
     */
    @Column(name = "delta_seconds", nullable = false)
    private long deltaSeconds;

    /** Остаток оплаченного времени СРАЗУ ПОСЛЕ события — «фотография» состояния. */
    @Column(name = "balance_after_seconds", nullable = false)
    private long balanceAfterSeconds;

    /** Платёж-причина, если событие вызвано деньгами. */
    @Column(name = "payment_id")
    private Long paymentId;

    /** Человекочитаемая пометка для разбора инцидентов (тариф, кто из админов, и т.п.). */
    @Column(name = "note")
    private String note;
}
