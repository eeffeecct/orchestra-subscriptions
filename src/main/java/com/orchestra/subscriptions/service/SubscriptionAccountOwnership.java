package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccountOwnership;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Реализация {@link AccountOwnership} поверх таблицы подписок. Проверка тривиальна —
 * на {@code provider_username} есть уникальный индекс {@code uk_subscriptions_provider_username}.
 * <p>
 * Ошибку чтения трактуем как «имя занято»: при недоступной БД безопаснее отказаться подбирать
 * чужой аккаунт (оплата активируется повторной попыткой), чем случайно слить две подписки —
 * второе не откатывается.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionAccountOwnership implements AccountOwnership {

    private final SubscriptionRepository subscriptionRepository;

    @Override
    public boolean ownedBySubscription(String providerUsername) {
        if (providerUsername == null || providerUsername.isBlank()) {
            return false;
        }
        try {
            return subscriptionRepository.findByProviderUsername(providerUsername).isPresent();
        } catch (Exception e) {
            log.warn("Не смог проверить владельца аккаунта {} — считаю занятым", providerUsername, e);
            return true;
        }
    }
}
