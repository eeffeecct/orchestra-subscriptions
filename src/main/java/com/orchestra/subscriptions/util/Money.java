package com.orchestra.subscriptions.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Форматирование денежных сумм для текстов, которые видит человек.
 * <p>
 * Суммы внутри системы везде в копейках ({@code amount_kopeks}), а в сообщениях нужны рубли.
 * Прямое {@code kopeks / 100} — целочисленное деление: 13333 коп. (цена со скидкой по
 * промокоду) превращалось в «133 ₽» вместо «133.33 ₽». Копейки появляются у любой цены,
 * умноженной на множитель ступени устройств, и у частичных возвратов.
 */
public final class Money {

    private Money() {}

    /**
     * Копейки → рубли для показа: целые суммы без дробной части («215»), дробные — с двумя
     * знаками («133.33»). Без символа валюты — вызывающий добавляет « ₽» сам, потому что в
     * части сообщений валюта уже стоит в шаблоне.
     */
    public static String rub(long kopeks) {
        BigDecimal rubles = BigDecimal.valueOf(kopeks).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        return rubles.stripTrailingZeros().scale() <= 0
                ? rubles.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : rubles.toPlainString();
    }

    /**
     * Копейки → рубли для ТЕЛА ЗАПРОСА к платёжному провайдеру: всегда два знака («215.00»).
     * Отличается от {@link #rub(long)} тем, что нули не срезаются — ЮKassa и Crypto Pay ждут
     * каноничный формат суммы, а не «красивый» человеческий.
     */
    public static BigDecimal apiRubles(long kopeks) {
        return BigDecimal.valueOf(kopeks).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@link #apiRubles(long)} строкой — как её кладут в JSON платёжных API. */
    public static String apiAmount(long kopeks) {
        return apiRubles(kopeks).toPlainString();
    }
}
