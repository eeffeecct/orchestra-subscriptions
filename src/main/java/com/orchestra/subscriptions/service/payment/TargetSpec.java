package com.orchestra.subscriptions.service.payment;

/**
 * Чего попросил КАНАЛ (бот, Mini App, web-ЛК, планировщик автопродления) — сырое пожелание
 * до проверки прав и лимитов. Во что оно превратится, решает {@link PaymentTargetResolver};
 * результат — {@link PaymentTarget}.
 * <p>
 * Разделение намеренное: {@code TargetSpec} приходит снаружи и ему нельзя верить (id может
 * быть чужим), {@code PaymentTarget} — уже проверенное решение, которое пишется в БД.
 * <p>
 * {@link #current()} — значение по умолчанию для всех каналов: платим в то, что канал
 * показывает человеку на экране.
 */
public record TargetSpec(Kind kind, Long subscriptionId) {

    public enum Kind {
        /** Цель не названа — подразумевается подписка, которая работает у человека сейчас. */
        CURRENT,
        /** Явная просьба «завести отдельную подписку». */
        NEW,
        /** Явно названная подписка (её принадлежность перепроверяется резолвером). */
        SUB
    }

    private static final TargetSpec CURRENT = new TargetSpec(Kind.CURRENT, null);

    public static TargetSpec current() {
        return CURRENT;
    }

    public static TargetSpec openNew() {
        return new TargetSpec(Kind.NEW, null);
    }

    public static TargetSpec of(long subscriptionId) {
        return new TargetSpec(Kind.SUB, subscriptionId);
    }

    /**
     * Разбор поля {@code target} из тела запроса Mini App: {@code null}/пусто/{@code "current"}
     * — текущая подписка, {@code "new"} — отдельная, число — названная подписка.
     * <p>
     * Непонятное значение — ОШИБКА, а не молчаливый откат к текущей. Откат означал бы, что
     * человеку показали «зачислится в подписку #2», а деньги ушли в #1: ровно тот класс
     * ошибок, ради которого весь этот разбор целей и появился. Фронт наш, и 400 здесь
     * вскрывает его баг сразу, а не через месяц в тикете про пропавшие дни.
     *
     * @throws IllegalArgumentException если значение не из перечисленных
     */
    public static TargetSpec ofWire(String raw) {
        if (raw == null || raw.isBlank() || "current".equals(raw)) {
            return current();
        }
        if ("new".equals(raw)) {
            return openNew();
        }
        try {
            return of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("неизвестная цель платежа: " + raw);
        }
    }
}
