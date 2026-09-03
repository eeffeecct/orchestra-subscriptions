package com.orchestra.subscriptions.service.payment;

/**
 * Чекаут отклонён ДО обращения к провайдеру: счёт не создаётся, деньги не принимаются.
 * <p>
 * Бросается только на явную просьбу «завести отдельную подписку» — по умолчанию (когда
 * канал цель не называет) отказа быть не может, там всегда есть куда зачислить. Поэтому
 * в проде это исключение недостижимо, пока выбор цели не появился на экранах.
 * <p>
 * {@link #reason()} — код для текста отказа, а не для логов: причины разные по смыслу
 * («лимит подписок» и «такая подписка уже есть» требуют разных ответов человеку).
 */
public class CheckoutRejectedException extends RuntimeException {

    /** Функция нескольких подписок выключена флагом (или человек не в allow-list). */
    public static final String MULTI_DISABLED = "MULTI_DISABLED";
    /** Достигнут потолок {@code app.multi-subscription.max-per-user}. */
    public static final String LIMIT = "LIMIT";
    /** Подписка такой же ступени уже есть — вторая неотличимая бесполезна, надо продлевать. */
    public static final String SAME_TIER_EXISTS = "SAME_TIER_EXISTS";

    private final String reason;

    public CheckoutRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
