package com.orchestra.subscriptions.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прайс продублирован: {@code application.yml} — источник правды, а в Mini App
 * ({@code static/app/script.js}) лежат таблицы {@code TARIFFS} и {@code DEVICE_TIERS},
 * которые показываются, пока не ответил {@code /api/pricing}. Дубль сделан осознанно —
 * витрина должна работать при недоступном API, — но расходится он молча: человек видит одну
 * сумму, а списывается другая.
 * <p>
 * Тест сверяет именно ДАННЫЕ. Формулу проверяет {@link AppConfigDeviceTierTest}: там
 * зафиксирован контракт «множитель 1.0 отдаёт цену как есть, остальные — округление до 5 ₽».
 * <p>
 * <b>Почему id сравниваются через маппинг, а не напрямую.</b> В фоллбэке id короткие
 * ({@code 1m}), у бэкенда — длинные ({@code month_1}); переводит их {@code apiPlanIdFor} в том
 * же script.js. Это не рассинхрон, а рабочая схема, поэтому проверяется вся цепочка:
 * фоллбэк → маппинг → план в конфиге. Забытый {@code case} в маппинге ломает оплату ровно
 * тогда, когда витрина фоллбэковая, — то есть в самый неудачный момент.
 * <p>
 * Файлы читаются с диска, а не из classpath, намеренно: {@code src/test/resources/application.yml}
 * перекрывает основной конфиг в classpath, и тест сверял бы Mini App с тестовым прайсом.
 */
class MiniAppPricingParityTest {

    private static final Path YML = Path.of("src/main/resources/application.yml");
    private static final Path JS = Path.of("src/main/resources/static/app/script.js");

    /** Одна строка таблицы витрины: {@code { id: '1m', title: …, basePrice: 179, months: 1 }}. */
    private record JsPlan(String id, int basePriceRub, int months) {}

    /** Одна строка ступеней витрины: {@code { id: 'd1', …, devices: 1, mult: 0.555 }}. */
    private record JsTier(String id, int devices, double mult) {}

    // =====================================================================================
    // Ступени
    // =====================================================================================

    @Test
    @DisplayName("Ступени устройств в Mini App совпадают с application.yml")
    void deviceTiersMatchConfig() {
        List<JsTier> ui = parseJsTiers();
        List<Map<String, Object>> cfg = configList("device-tiers");

        assertThat(ui).as("ступеней в фоллбэке Mini App").hasSize(cfg.size());

        for (Map<String, Object> tier : cfg) {
            String id = (String) tier.get("id");
            JsTier mirrored = ui.stream().filter(t -> t.id().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Ступень '" + id + "' есть в application.yml, но её нет в DEVICE_TIERS "
                            + "(static/app/script.js). Витрина без API покажет неполный список."));

            assertThat(mirrored.devices())
                    .as("число устройств у ступени '%s'", id)
                    .isEqualTo(((Number) tier.get("devices")).intValue());
            assertThat(mirrored.mult())
                    .as("множитель цены у ступени '%s'", id)
                    .isEqualTo(((Number) tier.get("price-multiplier")).doubleValue());
        }
    }

    // =====================================================================================
    // Тарифы
    // =====================================================================================

    @Test
    @DisplayName("Тарифы в Mini App совпадают с application.yml — через маппинг id")
    void plansMatchConfigThroughIdMapping() {
        List<JsPlan> ui = parseJsPlans();
        Map<String, String> mapping = parseJsPlanIdMapping();
        List<Map<String, Object>> cfg = configList("plans");

        assertThat(ui).as("тарифов в фоллбэке Mini App").hasSize(cfg.size());

        for (JsPlan plan : ui) {
            String backendId = mapping.getOrDefault(plan.id(), plan.id());

            Map<String, Object> real = cfg.stream()
                    .filter(p -> backendId.equals(p.get("id"))).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Тариф витрины '" + plan.id() + "' ведёт на planId '" + backendId
                            + "', которого нет в application.yml. В фоллбэковой витрине покупка "
                            + "такого тарифа получит от бэкенда 400 «unknown plan»."));

            long kopeks = ((Number) real.get("price-kopeks")).longValue();
            assertThat((long) plan.basePriceRub() * 100)
                    .as("цена тарифа '%s' (витрина, ₽) против '%s' (конфиг, копейки)",
                            plan.id(), backendId)
                    .isEqualTo(kopeks);

            int days = ((Number) real.get("days")).intValue();
            assertThat(plan.months())
                    .as("число месяцев у тарифа '%s' (в конфиге %d дней)", plan.id(), days)
                    .isEqualTo(Math.max(1, Math.round(days / 30f)));
        }
    }

    /**
     * Маппинг обязан покрывать ВСЕ короткие id витрины. Незакрытый {@code case} не падает
     * заметно: {@code apiPlanIdFor} возвращает id как есть (это сделано намеренно — бросок
     * исключения раньше убивал оплату молча), и ошибка всплывает 400-м ответом у клиента.
     */
    @Test
    @DisplayName("Каждый id фоллбэк-витрины переводится в существующий planId")
    void everyFallbackIdIsMapped() {
        Map<String, String> mapping = parseJsPlanIdMapping();
        List<String> backendIds = configList("plans").stream()
                .map(p -> (String) p.get("id")).toList();

        assertThat(mapping).as("маппинг apiPlanIdFor разобран из script.js").isNotEmpty();

        for (JsPlan plan : parseJsPlans()) {
            String backendId = mapping.get(plan.id());
            assertThat(backendId)
                    .as("для тарифа витрины '%s' нет case в apiPlanIdFor", plan.id())
                    .isNotNull();
            assertThat(backendIds)
                    .as("apiPlanIdFor переводит '%s' в несуществующий план", plan.id())
                    .contains(backendId);
        }
    }

    /**
     * Шаг округления цены ступени. Java: {@code Math.round(kopeks × mult / 500.0) × 500} —
     * то есть 5 ₽. Совпадение проверяется по тексту: выполнить JS отсюда нельзя, а
     * разошедшийся шаг даёт расхождение витрины и счёта на единицы рублей — самый неприятный
     * вид, потому что глазами не ловится.
     */
    @Test
    @DisplayName("Шаг округления в Mini App — 5 ₽, как в AppConfig.tierPriceKopeks")
    void roundingStepMatchesBackend() {
        String js = read(JS);
        assertThat(js)
                .as("формула tierPriceRub в static/app/script.js разошлась с "
                    + "AppConfig.tierPriceKopeks (там округление до 500 копеек)")
                .contains("Math.round(basePrice * tier.mult / 5) * 5");
    }

    // =====================================================================================
    // Разбор файлов
    // =====================================================================================

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> configList(String key) {
        try (InputStream in = Files.newInputStream(YML)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> app = (Map<String, Object>) root.get("app");
            List<Map<String, Object>> list = (List<Map<String, Object>>) app.get(key);
            assertThat(list).as("app.%s в application.yml", key).isNotEmpty();
            return list;
        } catch (IOException e) {
            throw new IllegalStateException("не прочитался " + YML, e);
        }
    }

    private static final Pattern TIER_LINE = Pattern.compile(
            "\\{\\s*id:\\s*'([^']+)'[^}]*?devices:\\s*(\\d+)[^}]*?mult:\\s*([0-9.]+)");
    private static final Pattern PLAN_LINE = Pattern.compile(
            "\\{\\s*id:\\s*'([^']+)'[^}]*?basePrice:\\s*(\\d+)[^}]*?months:\\s*(\\d+)");
    private static final Pattern MAPPING_CASE = Pattern.compile(
            "case\\s*'([^']+)':\\s*return\\s*'([^']+)';");

    private static List<JsTier> parseJsTiers() {
        List<JsTier> out = new ArrayList<>();
        Matcher m = TIER_LINE.matcher(block("let DEVICE_TIERS = ["));
        while (m.find()) {
            out.add(new JsTier(m.group(1), Integer.parseInt(m.group(2)), Double.parseDouble(m.group(3))));
        }
        assertThat(out).as("DEVICE_TIERS разобран из script.js").isNotEmpty();
        return out;
    }

    private static List<JsPlan> parseJsPlans() {
        List<JsPlan> out = new ArrayList<>();
        Matcher m = PLAN_LINE.matcher(block("let TARIFFS = ["));
        while (m.find()) {
            out.add(new JsPlan(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        }
        assertThat(out).as("TARIFFS разобран из script.js").isNotEmpty();
        return out;
    }

    private static Map<String, String> parseJsPlanIdMapping() {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = MAPPING_CASE.matcher(block("function apiPlanIdFor("));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /**
     * Кусок файла от маркера до ближайшей закрывающей строки. Грубо, но достаточно: обе
     * таблицы и функция маппинга — короткие литералы, а вытаскивать их регуляркой по всему
     * файлу опаснее (совпадёт с чем-нибудь похожим ниже).
     */
    private static String block(String marker) {
        String js = read(JS);
        int start = js.indexOf(marker);
        assertThat(start).as("маркер '%s' не найден в script.js — таблицу переименовали?", marker)
                .isNotEqualTo(-1);
        int end = marker.endsWith("[") ? js.indexOf("];", start) : js.indexOf("\n}", start);
        assertThat(end).as("не нашёлся конец блока '%s'", marker).isGreaterThan(start);
        return js.substring(start, end);
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "не прочитался " + p + " — тест запускается из корня проекта", e);
        }
    }
}
