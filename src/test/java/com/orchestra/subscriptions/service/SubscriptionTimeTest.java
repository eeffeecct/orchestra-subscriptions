package com.orchestra.subscriptions.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Арифметика срока подписки. Главное свойство под проверкой — округление ВСЕГДА в пользу
 * юзера: конвертации «остаток → дата → остаток» будут выполняться на каждом переключении
 * подписки, и систематическая потеря даже секунды за цикл со временем съедала бы оплаченное
 * время (именно на целых сутках этот баг уже случался дважды).
 */
class SubscriptionTimeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 3, 14, 15);

    @Test
    @DisplayName("Дни → секунды")
    void daysToSeconds() {
        assertThat(SubscriptionTime.daysToSeconds(30)).isEqualTo(2_592_000L);
        assertThat(SubscriptionTime.daysToSeconds(0)).isZero();
        // Отрицательных дней не бывает, но падать на них — плохая идея в платёжном пути.
        assertThat(SubscriptionTime.daysToSeconds(-5)).isZero();
    }

    @Test
    @DisplayName("Остаток считается вверх и никогда не отрицательный")
    void remainingSeconds() {
        assertThat(SubscriptionTime.remainingSeconds(NOW.plusDays(1), NOW)).isEqualTo(86_400L);
        // Прошедший срок — ноль, а не отрицательное число.
        assertThat(SubscriptionTime.remainingSeconds(NOW.minusDays(1), NOW)).isZero();
        assertThat(SubscriptionTime.remainingSeconds(null, NOW)).isZero();
        // Долю секунды округляем вверх — в пользу юзера.
        assertThat(SubscriptionTime.remainingSeconds(NOW.plusSeconds(10).plusNanos(1), NOW))
                .isEqualTo(11L);
    }

    @Test
    @DisplayName("ceilSeconds округляет вверх, а не вниз как Duration.toSeconds")
    void ceilSeconds() {
        assertThat(SubscriptionTime.ceilSeconds(Duration.ofSeconds(5))).isEqualTo(5L);
        assertThat(SubscriptionTime.ceilSeconds(Duration.ofSeconds(5).plusNanos(1))).isEqualTo(6L);
        assertThat(SubscriptionTime.ceilSeconds(Duration.ZERO)).isZero();
        assertThat(SubscriptionTime.ceilSeconds(Duration.ofSeconds(-5))).isZero();
        assertThat(SubscriptionTime.ceilSeconds(null)).isZero();
    }

    @Test
    @DisplayName("Дни для показа округляются вверх: остался час — это «1 день», не «0»")
    void displayDays() {
        assertThat(SubscriptionTime.displayDays(0)).isZero();
        assertThat(SubscriptionTime.displayDays(3600)).isEqualTo(1L);
        assertThat(SubscriptionTime.displayDays(86_400)).isEqualTo(1L);
        assertThat(SubscriptionTime.displayDays(86_401)).isEqualTo(2L);
        assertThat(SubscriptionTime.displayDays(-100)).isZero();
    }

    /**
     * Ключевой тест под будущее переключение подписок: 100 циклов «заморозили остаток →
     * разморозили в дату → снова заморозили». Дрейф допустим только В ПЛЮС юзеру и должен
     * быть мизерным; систематическая потеря здесь = молча съеденные оплаченные дни.
     */
    @Test
    @DisplayName("100 циклов заморозки/разморозки не крадут время у юзера")
    void parkResumeRoundTripsNeverLoseUserTime() {
        long remaining = SubscriptionTime.daysToSeconds(90) + 12_345L;
        long original = remaining;
        LocalDateTime clock = NOW;

        for (int i = 0; i < 100; i++) {
            LocalDateTime expiry = SubscriptionTime.expiryFrom(clock, remaining);
            remaining = SubscriptionTime.remainingSeconds(expiry, clock);
        }

        assertThat(remaining).isGreaterThanOrEqualTo(original);
        assertThat(remaining - original).isLessThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("expiryFrom обратна remainingSeconds")
    void expiryFromIsInverse() {
        long seconds = 1234567L;
        LocalDateTime expiry = SubscriptionTime.expiryFrom(NOW, seconds);
        assertThat(SubscriptionTime.remainingSeconds(expiry, NOW)).isEqualTo(seconds);
        // Отрицательный остаток не уводит дату в прошлое.
        assertThat(SubscriptionTime.expiryFrom(NOW, -100)).isEqualTo(NOW);
    }
}
