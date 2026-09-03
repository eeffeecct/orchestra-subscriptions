package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;

import java.time.LocalDateTime;

/**
 * Подписочное состояние в том виде, в каком оно нужно потребителям: выдаче подписки,
 * авторизации на hy2-нодах, учёту устройств, экранам.
 * <p>
 * Существует, чтобы потребители перестали зависеть от того, ГДЕ лежат данные. Сейчас
 * источник — {@code customers}, скоро — {@code subscriptions}; пока переезд идёт, оба
 * варианта должны отдавать неотличимый результат, и проверять это удобно, когда есть один
 * общий тип, а не две почти одинаковые ветки в каждом сервисе.
 *
 * @param subscriptionId id строки в {@code subscriptions}; {@code null}, если прочитано из
 *                       {@code customers} (старый источник)
 * @param parked         подписка ЗАМОРОЖЕНА, то есть не является текущей: её время лежит в
 *                       остатке и не расходуется, аккаунт в панели выключен. Из старого
 *                       источника всегда {@code false} — там замороженных строк не бывает
 *                       в принципе, подписка у человека ровно одна
 */
public record SubscriptionView(Long telegramId,
                               Long subscriptionId,
                               String telegramUsername,
                               String providerUsername,
                               String subscriptionLink,
                               String subscriptionToken,
                               Customer.Status status,
                               LocalDateTime expiresAt,
                               Integer deviceLimit,
                               String deviceTierId,
                               boolean parked) {

    /**
     * Подписка действует прямо сейчас. Ровно то же условие, что было в
     * {@code NodeAuthService} и на экранах бота: статус ACTIVE и срок ещё не вышел.
     */
    public boolean activeAt(LocalDateTime now) {
        return status == Customer.Status.ACTIVE && expiresAt != null && expiresAt.isAfter(now);
    }

    /** Публичный вариант {@link #of(Customer)} — для фолбэка на экранах. */
    public static SubscriptionView ofUser(Customer user) {
        return of(user);
    }

    static SubscriptionView of(Customer user) {
        return new SubscriptionView(
                user.getTelegramId(), null, user.getTelegramUsername(),
                user.getProviderUsername(), user.getSubscriptionLink(), user.getSubscriptionToken(),
                user.getStatus(), user.getExpiresAt(), user.getDeviceLimit(), user.getDeviceTierId(),
                false);
    }

    /**
     * {@code telegramUsername} живёт на человеке ({@code customers}), а не на подписке —
     * поэтому передаётся отдельно. Тянуть его в таблицу подписок значило бы дублировать
     * изменяемое поле в N строк.
     */
    static SubscriptionView of(Subscription sub, String telegramUsername) {
        return new SubscriptionView(
                sub.getTelegramId(), sub.getId(), telegramUsername,
                sub.getProviderUsername(), sub.getSubscriptionLink(), sub.getSubscriptionToken(),
                sub.getState(), sub.getExpiresAt(), sub.getDeviceLimit(), sub.getDeviceTierId(),
                !sub.isCurrent());
    }
}
