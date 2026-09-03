package com.orchestra.subscriptions.service.payment;

import com.orchestra.subscriptions.model.Subscription;

/**
 * Куда лягут деньги этого платежа — решено ДО обращения к провайдеру и записано на строку
 * платежа ({@code payments.subscription_id} + {@code payments.target_kind}).
 * <p>
 * <b>Зачем цель на строке, а не «текущая на момент активации».</b> Между созданием счёта и
 * приходом вебхука человек может переключить подписку. Если бы активация каждый раз брала
 * «ту, что текущая сейчас», деньги приземлялись бы не туда, куда человек платил, — и заметить
 * это можно было бы только по жалобе. Цель фиксируется один раз, в момент, когда человек
 * видит экран покупки.
 * <p>
 * {@code seq} и {@code parked} нужны, чтобы экран подтверждения и DM после оплаты могли
 * назвать подписку («Подписка #2, заморожена») без повторного запроса в БД.
 *
 * @param kind           см. {@link Kind}
 * @param subscriptionId цель для {@code EXTEND}; у остальных — {@code null}, подписки ещё нет
 * @param seq            номер подписки внутри человека; {@code null} у {@code NEW}/{@code SEPARATE}
 * @param targetTierId   ступень устройств целевой подписки на момент чекаута
 * @param parked         цель заморожена (не текущая) — её дни не идут, ссылка не работает
 */
public record PaymentTarget(Kind kind, Long subscriptionId, Integer seq,
                            String targetTierId, boolean parked) {

    public enum Kind {
        /** Продлить существующую подписку — сюда попадает подавляющее большинство платежей. */
        EXTEND,
        /**
         * Первая подписка человека: продлевать нечего. Активация идёт сегодняшним путём —
         * {@code AccessService} сам заводит аккаунт и строку {@code customers}.
         */
        NEW,
        /**
         * ОТДЕЛЬНАЯ подписка поверх уже существующих — заводится замороженной.
         * <p>
         * ⚠️ Отличать от {@code NEW} обязательно, и вот почему. Оба случая когда-то писались
         * одним значением, и тогда «зависший» PENDING первой покупки (человек открыл счёт до
         * появления подписки, оплатил его позже — например после триала или со второй попытки)
         * при активации выглядел бы как просьба завести ВТОРУЮ подписку. Человек получил бы
         * замороженные дни вместо продления, и произошло бы это при полностью выключенном
         * флаге мультиподписки.
         */
        SEPARATE
    }

    /** Продлить конкретную подписку. */
    public static PaymentTarget extend(Subscription subscription) {
        return new PaymentTarget(Kind.EXTEND, subscription.getId(), subscription.getSeq(),
                subscription.getDeviceTierId(), !subscription.isCurrent());
    }

    /** Первая подписка человека: у него ещё нет ни одной строки. */
    public static PaymentTarget first() {
        return new PaymentTarget(Kind.NEW, null, null, null, false);
    }

    /** Отдельная подписка поверх существующих — она родится замороженной. */
    public static PaymentTarget separate() {
        return new PaymentTarget(Kind.SEPARATE, null, null, null, true);
    }
}
