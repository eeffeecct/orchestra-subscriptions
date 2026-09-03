package com.orchestra.subscriptions.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест in-memory reply-состояния бота: ожидание ввода одноразово, отменяемо
 * и не пересекается между юзерами; применённый промокод живёт отдельно от ожидания.
 */
class PendingInputServiceTest {

    private PendingInputService service;

    @BeforeEach
    void setUp() {
        service = new PendingInputService();
    }

    @Test
    void expectAndConsume_oneShot() {
        service.expect(1L, PendingInputService.Kind.PROMO, "month_1:d2");

        assertThat(service.hasPending(1L)).isTrue();
        var input = service.consume(1L);
        assertThat(input).isPresent();
        assertThat(input.get().kind()).isEqualTo(PendingInputService.Kind.PROMO);
        assertThat(input.get().payload()).isEqualTo("month_1:d2");
        // Одноразово: второй consume пуст
        assertThat(service.consume(1L)).isEmpty();
    }

    @Test
    void clear_dropsPending() {
        service.expect(1L, PendingInputService.Kind.EMAIL, null);
        service.clear(1L);
        assertThat(service.hasPending(1L)).isFalse();
        assertThat(service.consume(1L)).isEmpty();
    }

    @Test
    void pending_isPerUser() {
        service.expect(1L, PendingInputService.Kind.DEVICE_NAME, "5");
        assertThat(service.hasPending(2L)).isFalse();
        assertThat(service.consume(2L)).isEmpty();
        assertThat(service.consume(1L)).isPresent();
    }

    @Test
    void appliedPromo_storedSeparatelyFromPendingInput() {
        service.applyPromo(1L, "WELCOME20");
        assertThat(service.getAppliedPromo(1L)).isEqualTo("WELCOME20");
        // Промокод не считается «ожиданием ввода»
        assertThat(service.hasPending(1L)).isFalse();
        // Не одноразовый: читается многократно, снимается явно
        assertThat(service.getAppliedPromo(1L)).isEqualTo("WELCOME20");
        service.clearPromo(1L);
        assertThat(service.getAppliedPromo(1L)).isNull();
    }
}
