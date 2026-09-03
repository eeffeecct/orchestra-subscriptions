package com.orchestra.subscriptions.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест ценообразования ступеней устройств: множитель 1.0 (база «2 устройства») отдаёт
 * цену плана как есть («психологические» 179 ₽ не округляются), остальные — план × множитель
 * с округлением до 5 ₽ (500 копеек). Формула продублирована в Mini App (script.js
 * tierPriceRub) — ожидаемые значения ниже фиксируют контракт для обеих сторон.
 */
class AppConfigDeviceTierTest {

    private static AppConfig.Plan plan(long kopeks) {
        AppConfig.Plan p = new AppConfig.Plan();
        p.setId("test");
        p.setPriceKopeks(kopeks);
        return p;
    }

    private static AppConfig.DeviceTier tier(String id, int devices, double mult) {
        AppConfig.DeviceTier t = new AppConfig.DeviceTier();
        t.setId(id);
        t.setDevices(devices);
        t.setPriceMultiplier(mult);
        return t;
    }

    @Test
    void nullTier_returnsBasePrice() {
        assertThat(new AppConfig().tierPriceKopeks(plan(17900), null)).isEqualTo(17900);
    }

    @Test
    void baseMultiplier_keepsPsychologicalPriceExact() {
        // ×1.0 БЕЗ округления: 179 ₽ остаётся 179, а не 180
        assertThat(new AppConfig().tierPriceKopeks(plan(17900), tier("d2", 2, 1.0))).isEqualTo(17900);
    }

    @Test
    void multiplierRoundsToFiveRubles() {
        AppConfig cfg = new AppConfig();
        // Реальная матрица (application.yml): база 179₽/мес за 2 устройства.
        // d1 ×0.555 → ровные 100₽ (решение владельца); d3 ×1.2 → 215₽; f5 ×1.8 → 320₽; f10 ×2.8 → 500₽
        assertThat(cfg.tierPriceKopeks(plan(17900), tier("d1", 1, 0.555))).isEqualTo(10000);
        assertThat(cfg.tierPriceKopeks(plan(17900), tier("d3", 3, 1.2))).isEqualTo(21500);
        assertThat(cfg.tierPriceKopeks(plan(17900), tier("f5", 5, 1.8))).isEqualTo(32000);
        assertThat(cfg.tierPriceKopeks(plan(17900), tier("f10", 10, 2.8))).isEqualTo(50000);
        // Остальные сроки d1: 3 мес → 265₽, 6 мес → 465₽, 12 мес → ровные 800₽
        assertThat(cfg.tierPriceKopeks(plan(48000), tier("d1", 1, 0.555))).isEqualTo(26500);
        assertThat(cfg.tierPriceKopeks(plan(84000), tier("d1", 1, 0.555))).isEqualTo(46500);
        assertThat(cfg.tierPriceKopeks(plan(144000), tier("d1", 1, 0.555))).isEqualTo(80000);
        // 12 месяцев (144000): d3 → 1730₽, f10 → 4030₽
        assertThat(cfg.tierPriceKopeks(plan(144000), tier("d3", 3, 1.2))).isEqualTo(173000);
        assertThat(cfg.tierPriceKopeks(plan(144000), tier("f10", 10, 2.8))).isEqualTo(403000);
    }

    @Test
    void addonPricing_proRataWithFloor() {
        AppConfig.DeviceAddonConfig addon = new AppConfig.DeviceAddonConfig();
        assertThat(addon.enabled()).isFalse(); // дефолт: выключено (цена 0)

        addon.setPriceKopeksPerMonth(3900); // 39₽/мес — как в application.yml
        assertThat(addon.enabled()).isTrue();
        // Округление до 1₽: полный месяц ровно 39₽; 300 дней → 390₽; 15 дней → 19.5₽ → 20₽
        assertThat(addon.priceKopeks(30)).isEqualTo(3900);
        assertThat(addon.priceKopeks(300)).isEqualTo(39000);
        assertThat(addon.priceKopeks(15)).isEqualTo(2000);
        // Минимум 10₽ даже на короткий хвост (1 день = 1.3₽ → floor 10₽)
        assertThat(addon.priceKopeks(1)).isEqualTo(1000);
    }

    @Test
    void effectiveProviderDeviceLimit_defaultsToDevicesPlusOne() {
        assertThat(tier("d3", 3, 1.6).effectiveProviderDeviceLimit()).isEqualTo(4);
        AppConfig.DeviceTier explicit = tier("f5", 5, 2.2);
        explicit.setProviderDeviceLimit(9);
        assertThat(explicit.effectiveProviderDeviceLimit()).isEqualTo(9);
    }

    @Test
    void findDeviceTierOptional_nullSafe() {
        AppConfig cfg = new AppConfig();
        assertThat(cfg.findDeviceTierOptional("d1")).isEmpty(); // список не задан
        assertThat(cfg.deviceTiersEnabled()).isFalse();

        cfg.setDeviceTiers(List.of(tier("d1", 1, 1.0)));
        assertThat(cfg.deviceTiersEnabled()).isTrue();
        assertThat(cfg.findDeviceTierOptional(null)).isEmpty();
        assertThat(cfg.findDeviceTierOptional("nope")).isEmpty();
        assertThat(cfg.findDeviceTierOptional("d1")).isPresent();
    }
}
