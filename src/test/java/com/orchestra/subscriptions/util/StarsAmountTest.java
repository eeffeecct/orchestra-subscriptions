package com.orchestra.subscriptions.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-functional unit-тест на формулу Stars-charge с учётом скидки. Раньше эта формула жила
 * inline в WebAppController.createStarsPayment, а pre_checkout в SubscriptionBot сравнивал с full price —
 * любая Stars-оплата с промокодом молча резалась. Теперь helper один на оба места, плюс этот тест.
 */
class StarsAmountTest {

    private static final long PLAN_KOPEKS = 15_000L;   // 150₽
    private static final long PLAN_STARS = 180L;       // 180⭐ за полную цену

    @Test
    void shouldReturnFullStarsWhenNoDiscount() {
        assertThat(StarsAmount.compute(PLAN_STARS, PLAN_KOPEKS, PLAN_KOPEKS)).isEqualTo(180);
    }

    @Test
    void shouldScaleUpProportionallyForDeviceTiers() {
        // Ступени устройств: chargeKopeks > planKopeks — Stars растут пропорционально.
        // До 2026-07-15 тут был кламп «не больше planStars» — семейный тариф уходил
        // по Stars-цене базовой ступени (~65% недоплаты на f10).
        // d3 ×1.2 на 1мес: 179₽ → 215₽. ceil(215 × 21500 / 17900) = ceil(258.2) = 259.
        assertThat(StarsAmount.compute(215, 17_900, 21_500)).isEqualTo(259);
        // f10 ×2.8 на 12мес: 1440₽ → 4030₽. ceil(1725 × 403000 / 144000) = ceil(4827.6) = 4828.
        assertThat(StarsAmount.compute(1725, 144_000, 403_000)).isEqualTo(4828);
        // Ceil и вверх тоже: 180 × 15100 / 15000 = 181.2 → 182
        assertThat(StarsAmount.compute(PLAN_STARS, PLAN_KOPEKS, PLAN_KOPEKS + 100)).isEqualTo(182);
    }

    @Test
    void shouldReducePropotionallyWith20PercentDiscount() {
        // 20% скидка: 150 → 120 рублей. Stars: 180 * 12000 / 15000 = 144
        long charge = 12_000L;
        assertThat(StarsAmount.compute(PLAN_STARS, PLAN_KOPEKS, charge)).isEqualTo(144);
    }

    @Test
    void shouldRoundUpNotDown() {
        // 150 → 100 рублей (33% скидка). Stars: ceil(180 * 100 / 150) = ceil(120) = 120
        // Если бы округляли вниз — было бы 119. Округление вверх защищает от FIXED-скидок
        // близких к full-price, через которые юзер мог бы получить «бесплатно».
        long charge = 10_000L;
        assertThat(StarsAmount.compute(PLAN_STARS, PLAN_KOPEKS, charge)).isEqualTo(120);

        // Дробный пример: charge=7₽ даёт нецелое число звёзд и округляется вверх.
        assertThat(StarsAmount.compute(PLAN_STARS, PLAN_KOPEKS, 700)).isEqualTo(9); // 180*700=126000; ceil(126000/15000)=9 (rounded up from 8.4)
    }

    @Test
    void shouldReturnAtLeastOneStarOnTinyDiscount() {
        // Скидка 99%: charge = 1₽. ceil(180 * 100 / 15000) = ceil(1.2) = 2 — но даже если был бы 0,
        // должны вернуть 1 (Telegram требует положительное число).
        assertThat(StarsAmount.compute(PLAN_STARS, PLAN_KOPEKS, 100)).isGreaterThanOrEqualTo(1);
        assertThat(StarsAmount.compute(PLAN_STARS, PLAN_KOPEKS, 1)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldRejectZeroOrNegativePlanInputs() {
        assertThatThrownBy(() -> StarsAmount.compute(0, PLAN_KOPEKS, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StarsAmount.compute(PLAN_STARS, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMatchInvoiceComputationForRealPlanSizes() {
        // Smoke test для всех 4 реальных тарифов (application.yml, база = 2 устройства)
        // с 20% скидкой. Регрессия: если кто-то поменяет helper и забудет — здесь упадёт.
        assertThat(StarsAmount.compute(215, 17_900, 14_320)).isEqualTo(172);     // 1мес
        assertThat(StarsAmount.compute(575, 48_000, 38_400)).isEqualTo(460);     // 3мес
        assertThat(StarsAmount.compute(1005, 84_000, 67_200)).isEqualTo(804);    // 6мес
        assertThat(StarsAmount.compute(1725, 144_000, 115_200)).isEqualTo(1380); // 12мес
    }
}
