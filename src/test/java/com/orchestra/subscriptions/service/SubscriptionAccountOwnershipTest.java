package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Решающая функция сужения 409-восстановления в {@code AccessProvider.createUser}.
 * <p>
 * 409 от панели означает «аккаунт с таким именем уже есть», но за этим стоят два
 * противоположных случая. Первый — <b>наш недоделанный аккаунт</b>: прошлый {@code createUser}
 * прошёл, а транзакция БД откатилась; такой надо подобрать, иначе оплата не активируется
 * никогда. Второй — <b>чужой рабочий аккаунт</b>: имя уже закреплено за существующей
 * подпиской; подобрать его значит слить две подписки в один аккаунт панели (общий срок, общий
 * лимит устройств, общая ссылка) — человек заплатил дважды, а получил одно, и заметно это
 * только по жалобе.
 * <p>
 * Различает их ровно этот класс, поэтому он и проверяется отдельно: сам 409-путь в
 * {@code AccessProvider} без стенда HTTP не воспроизвести, а решение принимается здесь.
 */
class SubscriptionPanelAccountsTest {

    private static final String NAME = "tg_alice_42";

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionAccountOwnership accounts;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        accounts = new SubscriptionAccountOwnership(subscriptionRepository);
    }

    @Test
    @DisplayName("Имя закреплено за подпиской — подбирать нельзя")
    void nameOwnedBySubscription() {
        when(subscriptionRepository.findByProviderUsername(NAME))
                .thenReturn(Optional.of(Subscription.builder().id(7L).seq(2).providerUsername(NAME).build()));

        assertThat(accounts.ownedBySubscription(NAME)).isTrue();
    }

    @Test
    @DisplayName("За именем никто не числится — это наш недоделанный аккаунт, подбираем")
    void unknownNameIsFreeToAdopt() {
        when(subscriptionRepository.findByProviderUsername(anyString())).thenReturn(Optional.empty());

        assertThat(accounts.ownedBySubscription(NAME)).isFalse();
    }

    @Test
    @DisplayName("Пустое имя — не повод считать аккаунт занятым")
    void blankNameIsNotOwned() {
        assertThat(accounts.ownedBySubscription(null)).isFalse();
        assertThat(accounts.ownedBySubscription("")).isFalse();
        assertThat(accounts.ownedBySubscription("   ")).isFalse();
    }

    /**
     * База недоступна — считаем имя ЗАНЯТЫМ. Из двух ошибок «не активировали оплату, пока БД
     * лежит» обратима, а «слили две подписки в один аккаунт панели» — нет: восстановить
     * разъехавшиеся сроки и ссылки уже нечем.
     */
    @Test
    @DisplayName("Сбой базы — считаем занятым, а не свободным")
    void repositoryFailureIsTreatedAsOwned() {
        when(subscriptionRepository.findByProviderUsername(anyString()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(accounts.ownedBySubscription(NAME)).isTrue();
    }
}
