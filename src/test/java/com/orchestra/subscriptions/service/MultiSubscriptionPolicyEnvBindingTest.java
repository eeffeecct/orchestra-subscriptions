package com.orchestra.subscriptions.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пиннинг ИМЁН ПЕРЕМЕННЫХ ОКРУЖЕНИЯ, которыми флаг включают в проде.
 * <p>
 * Смысл теста — не в проверке Spring, а в том, что рунбук
 * {@code docs/multi-subscription-stage5-plan.md} §5а и реальность обязаны совпадать. Проверить
 * это на живом сервере нечем: actuator выключен, а приложение с НЕпривязавшимся флагом стартует
 * молча и ведёт себя ровно как с выключенным. То есть опечатка в имени переменной выглядит как
 * «фича не работает», и разбор занимает часы. Здесь она падает за секунду.
 * <p>
 * Проверяются обе формы, потому что в проде живёт одна, а в рунбуке стояла другая: связывание
 * {@code @ConfigurationProperties} принимает и каноническую (дефисы убраны), и legacy-форму
 * (дефис → подчёркивание). Если Spring когда-нибудь одну из них уронит, лучше узнать здесь.
 */
class MultiSubscriptionPolicyEnvBindingTest {

    private static final long OWNER = 100200300L;

    /** Ровно то, что записано в рунбуке §5а и лежит в {@code /opt/subscription-service/.env} с 13.08.2026. */
    @Test
    @DisplayName("Форма с подчёркиванием (как в проде) включает флаг и allow-list")
    void underscoreFormBinds() {
        MultiSubscriptionPolicy policy = bind(Map.of(
                "APP_MULTI_SUBSCRIPTION_ENABLED", "true",
                "APP_MULTI_SUBSCRIPTION_ALLOWED_TELEGRAM_IDS_0", String.valueOf(OWNER)));

        assertThat(policy.canOpenNew(OWNER)).isTrue();
        assertThat(policy.canOpenNew(OWNER + 1)).isFalse();
    }

    /** Каноническая форма Spring (дефисы выброшены) — так рунбук был написан до 13.08.2026. */
    @Test
    @DisplayName("Слитная форма связывается тоже — оба написания рабочие")
    void concatenatedFormBinds() {
        MultiSubscriptionPolicy policy = bind(Map.of(
                "APP_MULTISUBSCRIPTION_ENABLED", "true",
                "APP_MULTISUBSCRIPTION_ALLOWEDTELEGRAMIDS_0", String.valueOf(OWNER)));

        assertThat(policy.canOpenNew(OWNER)).isTrue();
    }

    /**
     * Пустой allow-list при {@code enabled=true} = никому. Свойство важное: «включить всем»
     * должно требовать ОТДЕЛЬНОГО осознанного флага, а не получаться само из затёртой или
     * забытой переменной со списком.
     */
    @Test
    @DisplayName("enabled без allow-list не открывает вторую подписку никому")
    void enabledWithoutAllowListOpensNothing() {
        MultiSubscriptionPolicy policy = bind(Map.of("APP_MULTI_SUBSCRIPTION_ENABLED", "true"));

        assertThat(policy.canOpenNew(OWNER)).isFalse();
    }

    /** Открытие на всех — то, чем фича включается людям после обкатки на владельце. */
    @Test
    @DisplayName("allow-all открывает вторую подписку всем, минуя список")
    void allowAllOpensForEveryone() {
        MultiSubscriptionPolicy policy = bind(Map.of(
                "APP_MULTI_SUBSCRIPTION_ENABLED", "true",
                "APP_MULTI_SUBSCRIPTION_ALLOW_ALL", "true"));

        assertThat(policy.canOpenNew(OWNER)).isTrue();
        assertThat(policy.canOpenNew(123456789L)).isTrue();
    }

    /**
     * ⛔ Выключатель остаётся ОДИН: {@code enabled=false} перекрывает и {@code allow-all}.
     * Иначе откат фичи требовал бы снять две переменные, и забытая вторая означала бы, что
     * «выключено» на самом деле включено для всех.
     */
    @Test
    @DisplayName("enabled=false перекрывает allow-all")
    void disabledBeatsAllowAll() {
        MultiSubscriptionPolicy policy = bind(Map.of(
                "APP_MULTI_SUBSCRIPTION_ENABLED", "false",
                "APP_MULTI_SUBSCRIPTION_ALLOW_ALL", "true"));

        assertThat(policy.canOpenNew(OWNER)).isFalse();
    }

    /** Связывание ровно тем же механизмом, что в приложении: переменные окружения → свойства. */
    private static MultiSubscriptionPolicy bind(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        MultiSubscriptionPolicy policy = new MultiSubscriptionPolicy();
        Binder.get(environment).bind("app.multi-subscription", Bindable.ofInstance(policy));
        return policy;
    }
}
