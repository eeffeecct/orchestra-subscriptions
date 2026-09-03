package com.orchestra.subscriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Закрывает главную дыру в проверках этого репозитория: обычные тесты стартуют с
 * {@code ddl-auto: create-drop} и Liquibase НЕ выполняют вовсе — схему им генерирует
 * Hibernate по сущностям. То есть зелёный CI НЕ доказывает, что миграции корректны, а
 * прод стартует с {@code validate} по схеме из миграций. Расхождение между тем и другим
 * роняет контейнер на старте — уже после того, как деплой признан успешным.
 * <p>
 * Здесь всё наоборот: Liquibase прогоняет НАСТОЯЩИЙ changelog на пустой базе, а Hibernate
 * работает в режиме {@code validate}. Если хоть одна сущность не сходится со схемой из
 * миграций (нет колонки, короче тип у enum-колонки, другое имя), контекст не поднимется и
 * тест упадёт — здесь, а не в проде.
 * <p>
 * Дополнительно проверяются вещи, которые Hibernate по сущностям НЕ создаёт и которые несут
 * весь смысл модели подписок: частичные уникальные индексы и CHECK-ограничения.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.liquibase.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
class MigrationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    com.orchestra.subscriptions.access.AccessProvider accessProvider;
    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;
    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;

    /**
     * Сам факт поднявшегося контекста уже доказывает, что схема из миграций и сущности
     * сходятся ({@code ddl-auto: validate}). Здесь фиксируем, что миграции реально
     * выполнились, а не были пропущены.
     */
    @Test
    @DisplayName("Changelog применяется, и сущности проходят validate по полученной схеме")
    void changelogAppliesAndEntitiesValidate() {
        List<String> applied = jdbc.queryForList(
                "SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class);
        assertThat(applied).contains(
                "025-create-subscription-ledger",
                "026-create-subscriptions",
                "026-subscriptions-constraints",
                "026-add-subscription-refs",
                "027-fix-null-optimistic-lock-versions");
    }

    /**
     * Регресс на живую мину, найденную в проде 2026-08-12: у 19 платежей {@code version}
     * был NULL, и Hibernate падал с NPE при ЛЮБОМ их обновлении — то есть возврат денег по
     * такому платежу не прошёл бы. Миграция обязана вычищать NULL во всех таблицах с
     * оптимистичной блокировкой.
     */
    @Test
    @DisplayName("Строк с version = NULL не остаётся ни в одной таблице с @Version")
    void noNullOptimisticLockVersionsRemain() {
        List<String> versioned = List.of("payments", "customers", "user_devices", "referrals",
                "magic_links", "promo_codes", "promo_usages", "subscriptions");
        for (String table : versioned) {
            // Строка с NULL проходит вставку (колонка nullable), но миграция уже отработала —
            // проверяем, что после неё чинить нечего, и что запрос вообще валиден для таблицы.
            Integer nulls = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE version IS NULL", Integer.class);
            assertThat(nulls).as("%s.version IS NULL", table).isZero();
        }
    }

    @Test
    @DisplayName("Частичные уникальные индексы созданы — Hibernate их не генерирует")
    void partialUniqueIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'subscriptions'", String.class);
        assertThat(indexes).contains(
                "uk_subscriptions_one_current",
                "uk_subscriptions_provider_username",
                "uk_subscriptions_token");
    }

    /** «Ровно одна текущая подписка на человека» должно держаться базой, а не кодом. */
    @Test
    @DisplayName("Вторую текущую подписку одному человеку база не даст вставить")
    void oneCurrentSubscriptionPerPerson() {
        jdbc.update("INSERT INTO subscriptions (telegram_id, seq, is_current, expires_at, "
                + "autorenew_enabled, notified_24h, notified_1h, winback_sent, provider_attempts, version) "
                + "VALUES (4242, 1, true, now(), false, false, false, false, 0, 0)");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("INSERT INTO subscriptions (telegram_id, seq, is_current, expires_at, "
                                + "autorenew_enabled, notified_24h, notified_1h, winback_sent, provider_attempts, version) "
                                + "VALUES (4242, 2, true, now(), false, false, false, false, 0, 0)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Часы разделены по состоянию: у текущей — дата, у припаркованной — остаток.
     * Держать оба поля разом нельзя, иначе припаркованная подписка снова стала бы видимой
     * для запросов по датам (напоминания, автопродление) и её дни бы тикали.
     */
    @Test
    @DisplayName("CHECK не даёт текущей подписке иметь замороженный остаток")
    void clockConstraintRejectsBothClocks() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("INSERT INTO subscriptions (telegram_id, seq, is_current, expires_at, "
                                + "balance_seconds, autorenew_enabled, notified_24h, notified_1h, "
                                + "winback_sent, provider_attempts, version) "
                                + "VALUES (4343, 1, true, now(), 100, false, false, false, false, 0, 0)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** Отрицательный остаток означал бы «юзер должен нам время» — такого состояния быть не может. */
    @Test
    @DisplayName("CHECK не даёт отрицательный замороженный остаток")
    void balanceConstraintRejectsNegative() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("INSERT INTO subscriptions (telegram_id, seq, is_current, "
                                + "balance_seconds, autorenew_enabled, notified_24h, notified_1h, "
                                + "winback_sent, provider_attempts, version) "
                                + "VALUES (4444, 1, false, -1, false, false, false, false, 0, 0)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Состояние после полного возврата денег: подписка текущая, но срока нет. Ужесточение
     * CHECK'а до «у текущей дата обязательна» сломало бы каждый такой возврат.
     */
    @Test
    @DisplayName("Текущая подписка без срока разрешена — это состояние после полного возврата")
    void currentWithoutExpiryIsAllowed() {
        jdbc.update("INSERT INTO subscriptions (telegram_id, seq, is_current, expires_at, "
                + "autorenew_enabled, notified_24h, notified_1h, winback_sent, provider_attempts, version) "
                + "VALUES (4545, 1, true, NULL, false, false, false, false, 0, 0)");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE telegram_id = 4545", Integer.class))
                .isEqualTo(1);
    }
}
