package com.orchestra.subscriptions.util;

/**
 * Человекочитаемые причины отказа промокода — единственная копия текстов на все три канала
 * (бот, Mini App, web-ЛК).
 * <p>
 * Раньше этот {@code switch} лежал дословной копией в {@code UserInputHandler},
 * {@code WebAppController} и {@code AccountController}. Состояние промокода одно, значит и
 * формулировка обязана быть одна: разойдись копии — человек, который видит «Промокод отключён»
 * в боте и «Не удалось применить промокод» в приложении, решит, что это разные проблемы.
 */
public final class PromoMessages {

    private PromoMessages() {}

    /**
     * @param reason код причины из {@code PromoCodeService.PromoResult#reason()}
     * @return текст для человека; для {@code null} и незнакомых кодов — нейтральная формулировка
     */
    public static String reasonMessage(String reason) {
        if (reason == null) return "Не удалось применить промокод";
        return switch (reason) {
            case "empty" -> "Промокод пустой";
            case "not_found" -> "Промокод не найден";
            case "disabled" -> "Промокод отключён";
            case "expired" -> "Срок действия промокода истёк";
            case "max_uses_reached" -> "Промокод больше не действует (исчерпан лимит)";
            case "already_used" -> "Вы уже использовали этот промокод";
            // Резерв: код держит собственный неоплаченный счёт человека, а не прошлая покупка.
            case "pending_checkout" -> "Счёт с этим промокодом уже выставлен. "
                    + "Оплатите его или дождитесь отмены — после этого код снова станет доступен";
            default -> "Не удалось применить промокод";
        };
    }
}
