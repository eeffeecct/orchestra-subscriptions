package com.orchestra.subscriptions.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Имя аккаунта в панели для новых подписок.
 * <p>
 * Свойство, ради которого тест существует: <b>первая подписка человека называется ровно так
 * же, как называлась всегда</b>. Иначе появление второй подписки означало бы переименование
 * аккаунтов у всех живых юзеров — то есть смену ссылки у каждого, кто ничего не покупал.
 */
class SubscriptionMintNamingTest {

    private static final long TG_ID = 100200300L;

    @Test
    @DisplayName("Первая подписка называется сегодняшним именем")
    void firstSubscriptionKeepsTodaysName() {
        assertThat(SubscriptionMintService.buildProviderUsername(TG_ID, "eeffeecct", 1))
                .isEqualTo("tg_eeffeecct_" + TG_ID);
        assertThat(SubscriptionMintService.buildProviderUsername(TG_ID, null, 1))
                .isEqualTo("tg_" + TG_ID);
    }

    @Test
    @DisplayName("Каждая следующая подписка получает свой аккаунт")
    void laterSubscriptionsGetDistinctNames() {
        String second = SubscriptionMintService.buildProviderUsername(TG_ID, "eeffeecct", 2);
        String third = SubscriptionMintService.buildProviderUsername(TG_ID, "eeffeecct", 3);
        assertThat(second).isNotEqualTo(third);
        assertThat(second).isEqualTo("tg_eeffeecct_" + TG_ID + "_s2");
    }

    /**
     * Имя из Telegram приходит от человека: в имя аккаунта панели оно попадать сырым не должно
     * (там и точки, и юникод, и что угодно ещё).
     */
    @Test
    @DisplayName("Имя из Telegram санитизируется так же, как в AccessService")
    void telegramUsernameIsSanitized() {
        assertThat(SubscriptionMintService.buildProviderUsername(TG_ID, "Ivan.Petrov-77", 2))
                .isEqualTo("tg_ivan_petrov_77_" + TG_ID + "_s2");
        assertThat(SubscriptionMintService.buildProviderUsername(TG_ID, "   ", 2))
                .isEqualTo("tg_" + TG_ID + "_s2");
    }
}
