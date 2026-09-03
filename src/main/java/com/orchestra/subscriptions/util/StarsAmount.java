package com.orchestra.subscriptions.util;

/**
 * Конвертация рублёвой цены (с возможной скидкой) в Stars-эквивалент для конкретного плана.
 * <p>
 * Используется в ДВУХ местах: при создании Stars-инвойса (WebAppController) и при валидации
 * pre_checkout_query (SubscriptionBot). Раньше каждое место считало само — это разъезжалось при
 * применении промокода: invoice уходил с discounted Stars, а pre_checkout сверял с full price
 * и молча отклонял оплату. См. fix-аудит 2026-04-30.
 */
public final class StarsAmount {

    private StarsAmount() {}

    /**
     * Сколько Stars списать, если рублёвая цена = {@code chargeKopeks}, при том что план в
     * базовой цене стоит {@code planKopeks} рублей или {@code planStars} ⭐. Пропорция работает
     * В ОБЕ СТОРОНЫ: вниз — промокоды-скидки и ступень d1 (×0.84), вверх — ступени устройств
     * ({@code AppConfig.tierPriceKopeks}: ×1.2…×2.8) и докупка слота. До 2026-07-15 тут
     * стоял кламп {@code chargeKopeks >= planKopeks → planStars} — со ступенями он молча продавал
     * семейный тариф по Stars-цене одного устройства (и pre_checkout это «подтверждал», потому
     * что считал той же формулой).
     * <p>
     * Округляем ВВЕРХ — лучше списать на 1 Star больше, чем дать FIXED-скидку, близкую к full price,
     * «бесплатно» через округление вниз. Минимум 1 Star (Telegram требует положительное число).
     * Точное равенство {@code chargeKopeks == planKopeks} → {@code planStars} as-is (без дрожи
     * округления на базовой цене).
     */
    public static long compute(long planStars, long planKopeks, long chargeKopeks) {
        if (planKopeks <= 0 || planStars <= 0) {
            throw new IllegalArgumentException(
                    "planStars и planKopeks должны быть > 0; got planStars=" + planStars + " planKopeks=" + planKopeks);
        }
        if (chargeKopeks == planKopeks) {
            return planStars;
        }
        long stars = (planStars * chargeKopeks + planKopeks - 1) / planKopeks;
        return Math.max(1, stars);
    }
}
