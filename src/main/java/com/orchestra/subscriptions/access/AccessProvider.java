package com.orchestra.subscriptions.access;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Внешняя система, которая физически раздаёт доступ к сервису.
 *
 * <p>Приложение не знает, как именно доступ устроен изнутри: оно оперирует аккаунтом
 * ({@link AccessAccount}) с именем, сроком и ссылкой, по которой клиент забирает свои
 * параметры подключения. Всё остальное — дело реализации: это может быть отдельная панель
 * управления по HTTP, лицензионный сервер, биллинг стороннего вендора или, как в этом
 * репозитории, локальная заглушка {@link LocalAccessProvider}.
 *
 * <p><b>Почему интерфейс, а не класс.</b> Провайдер доступа — единственная часть системы,
 * которая меняется при смене продукта; подписки, платежи, промокоды и жизненный цикл от него
 * не зависят. Держать его за интерфейсом значит менять продукт одним классом, а не правкой
 * платёжного слоя.
 *
 * <p><b>Контракт вызовов.</b> Все методы, кроме {@code create}, идемпотентны и задают
 * АБСОЛЮТНОЕ состояние («включить до такой-то даты», «выключить»), а не приращение.
 * Повторный вызов с теми же аргументами приводит к тому же результату — на этом держится
 * реконсилер: он досылает состояние вслепую, не зная, дошёл ли предыдущий вызов.
 */
public interface AccessProvider {

    /** Завести аккаунт с лимитом устройств по умолчанию. */
    AccessAccount createUser(String username, int expireDays, long dataLimitGb);

    /**
     * Завести аккаунт.
     *
     * @param deviceLimitOverride персональный лимит одновременных устройств; {@code null}/≤0 —
     *                            берётся общий {@code app.provider.device-limit}
     */
    AccessAccount createUser(String username, int expireDays, long dataLimitGb, Integer deviceLimitOverride);

    /**
     * Прочитать аккаунт у провайдера. {@link Optional#empty()} означает и «аккаунта нет»,
     * и «спросить не удалось» — вызывающий обязан трактовать пустоту консервативно и не
     * принимать по ней решений об отзыве доступа.
     */
    Optional<AccessAccount> getUser(String username);

    /**
     * Перевыпустить подписочный токен: старая ссылка немедленно перестаёт работать, новая
     * возвращается в {@link AccessAccount#getSubscriptionUrl()}. Это действие «выйти со всех
     * устройств» — им же отзывается утёкшая ссылка.
     */
    AccessAccount revokeSubscription(String username);

    /** Выключить доступ, не удаляя аккаунт (срок и история сохраняются). */
    void disableUser(String username);

    /** Включить доступ на {@code expireDays} суток от текущего момента. */
    void enableUser(String username, int expireDays, Integer deviceLimitOverride);

    /**
     * Включить доступ до ТОЧНОГО момента.
     *
     * <p>Отдельный метод от {@link #enableUser} не косметика: наша база хранит срок с
     * точностью до секунды, и округление до целых суток разъезжается с ней в обе стороны —
     * вниз доступ умирает раньше, чем показывает интерфейс, вверх расхождение ещё и
     * накапливается при каждом продлении.
     */
    void enableUserUntil(String username, Instant expireAt, Integer deviceLimitOverride);

    /** Узлы, через которые раздаётся доступ — для админской диагностики и статус-экрана. */
    List<AccessNode> getNodes();

    /**
     * Аккаунт на стороне провайдера. Плоский DTO: поля названы так, как их отдаёт типичный
     * HTTP-API управляющей панели (snake_case в JSON), чтобы реализация поверх реального
     * вендора не требовала своего маппинга.
     */
    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    class AccessAccount {
        private String username;
        /** {@code active} / {@code disabled} / {@code expired} / {@code limited}. */
        private String status;
        /** Ссылка, по которой клиент забирает свои параметры доступа. */
        @JsonProperty("subscription_url")
        private String subscriptionUrl;
        @JsonProperty("used_traffic")
        private Long usedTraffic;
        @JsonProperty("data_limit")
        private Long dataLimit;
        /** Момент истечения, unix-время в секундах. */
        private Long expire;

        /**
         * Когда провайдер последний раз видел активность аккаунта. Единственный источник
         * «был в сети» — в нашей базе такого следа нет, там лежит только факт обновления
         * подписки клиентом. Хранится строкой: формат задаёт провайдер, и его нестабильность
         * не должна ронять разбор всего аккаунта.
         */
        @JsonProperty("online_at")
        private String onlineAt;

        /** Когда клиент последний раз забирал параметры доступа. */
        @JsonProperty("sub_updated_at")
        private String subUpdatedAt;

        /** Каким клиентом забирал — User-Agent последнего запроса. */
        @JsonProperty("sub_last_user_agent")
        private String subLastUserAgent;
    }

    /** Узел раздачи доступа — то, что видно в админке провайдера. */
    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    class AccessNode {
        private Long id;
        private String name;
        private String address;
        /** connected / connecting / error / disabled. */
        private String status;
        /** Последнее сообщение провайдера (обычно текст ошибки при {@code status=error}). */
        private String message;
        @JsonProperty("core_version")
        private String coreVersion;
    }
}
