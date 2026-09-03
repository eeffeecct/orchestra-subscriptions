package com.orchestra.subscriptions.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Форматирование сумм для сообщений юзеру. Главный кейс — копейки: раньше в текстах стояло
 * целочисленное {@code kopeks / 100}, и цена со скидкой по промокоду (13333 коп.) показывалась
 * как «133 ₽» вместо «133.33 ₽».
 */
class MoneyTest {

    @Test
    @DisplayName("Целые рубли — без дробной части")
    void wholeRubles() {
        assertThat(Money.rub(17900)).isEqualTo("179");
        assertThat(Money.rub(21500)).isEqualTo("215");
        assertThat(Money.rub(100000)).isEqualTo("1000");
    }

    @Test
    @DisplayName("Копейки не теряются")
    void keepsKopeks() {
        assertThat(Money.rub(13333)).isEqualTo("133.33");
        assertThat(Money.rub(1)).isEqualTo("0.01");
        assertThat(Money.rub(17950)).isEqualTo("179.50");
    }

    @Test
    @DisplayName("Ноль и отрицательные не ломают формат")
    void edgeCases() {
        assertThat(Money.rub(0)).isEqualTo("0");
        assertThat(Money.rub(-15000)).isEqualTo("-150");
    }
}
