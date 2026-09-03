package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Напоминание про оплаченную, но не включённую подписку.
 * <p>
 * Проверяются два свойства, ошибка в каждом бьёт по живым людям: письмо уходит не раньше
 * суток после оплаты — и НЕ уходит повторно, сколько бы раз ни перезапустился контейнер.
 * Второе особенно важно: планировщик стартует заново при каждом подъёме контекста, а деплой
 * это и есть рестарт, и их бывает несколько за час.
 */
class ParkedFundsReminderTest {

    private static final long TG_ID = 4242L;

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionLedgerRepository ledgerRepository;
    private NotificationService notificationService;
    private ParkedFundsReminder reminder;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        ledgerRepository = mock(SubscriptionLedgerRepository.class);
        notificationService = mock(NotificationService.class);
        AppConfig appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        when(appConfig.getTelegram().getOwnerId()).thenReturn(null);
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        reminder = new ParkedFundsReminder(subscriptionRepository, ledgerRepository,
                notificationService, appConfig);
        ReflectionTestUtils.setField(reminder, "enabled", true);
    }

    @Test
    @DisplayName("Через 24 часа после оплаты напоминаем")
    void remindsAtTwentyFourHours() {
        givenParkedPaidHoursAgo(24);

        reminder.remindAboutParkedFunds();

        verify(notificationService).sendParkedSubscriptionReminder(anyLong(), any());
    }

    @Test
    @DisplayName("Через 72 часа напоминаем ещё раз")
    void remindsAtSeventyTwoHours() {
        givenParkedPaidHoursAgo(72);

        reminder.remindAboutParkedFunds();

        verify(notificationService).sendParkedSubscriptionReminder(anyLong(), any());
    }

    @Test
    @DisplayName("До суток молчим")
    void staysSilentBeforeFirstWindow() {
        givenParkedPaidHoursAgo(10);

        reminder.remindAboutParkedFunds();

        verify(notificationService, never()).sendParkedSubscriptionReminder(anyLong(), any());
    }

    /**
     * Регресс-щит. Планировщик стартует заново при каждом подъёме контекста, а деплой — это
     * рестарт: у нас их бывает несколько за час. Пока «напоминали или нет» держалось на
     * часовом окне, каждый деплой рассылал письма повторно.
     */
    @Test
    @DisplayName("Повторный прогон (рестарт контейнера) не шлёт второе письмо")
    void secondRunDoesNotResend() {
        givenParkedPaidHoursAgo(24);

        reminder.remindAboutParkedFunds();
        reminder.remindAboutParkedFunds();

        verify(notificationService, org.mockito.Mockito.times(1))
                .sendParkedSubscriptionReminder(anyLong(), any());
    }

    /**
     * Человек включал эту подписку и выключил сам — он про неё знает, напоминать не о чем.
     * Иначе напоминание превращается в «мы заметили, что Вы выключили то, что выключили».
     */
    @Test
    @DisplayName("Подписку, которой пользовались, не напоминаем")
    void skipsSubscriptionsUserAlreadyUsed() {
        Subscription used = parked();
        used.setLastActivatedAt(LocalDateTime.now().minusDays(2));
        when(subscriptionRepository.findByCurrentFalseAndBalanceSecondsGreaterThan(0L))
                .thenReturn(List.of(used));

        reminder.remindAboutParkedFunds();

        verify(notificationService, never()).sendParkedSubscriptionReminder(anyLong(), any());
    }

    @Test
    @DisplayName("Дни пришли не оплатой — напоминать не о чем")
    void skipsSubscriptionsWithoutPayment() {
        Subscription parked = parked();
        when(subscriptionRepository.findByCurrentFalseAndBalanceSecondsGreaterThan(0L))
                .thenReturn(List.of(parked));
        when(ledgerRepository.findTopBySubscriptionIdAndKindInOrderByOccurredAtDesc(
                anyLong(), anyCollection())).thenReturn(Optional.empty());

        reminder.remindAboutParkedFunds();

        verify(notificationService, never()).sendParkedSubscriptionReminder(anyLong(), any());
    }

    @Test
    @DisplayName("Выключенный планировщик не шлёт ничего")
    void disabledSchedulerSendsNothing() {
        ReflectionTestUtils.setField(reminder, "enabled", false);
        givenParkedPaidHoursAgo(24);

        reminder.remindAboutParkedFunds();

        verify(notificationService, never()).sendParkedSubscriptionReminder(anyLong(), any());
    }

    private void givenParkedPaidHoursAgo(int hours) {
        Subscription parked = parked();
        when(subscriptionRepository.findByCurrentFalseAndBalanceSecondsGreaterThan(0L))
                .thenReturn(List.of(parked));
        when(ledgerRepository.findTopBySubscriptionIdAndKindInOrderByOccurredAtDesc(
                anyLong(), anyCollection()))
                .thenReturn(Optional.of(SubscriptionLedger.builder()
                        .telegramId(TG_ID)
                        .subscriptionId(parked.getId())
                        .kind(SubscriptionLedger.Kind.PURCHASE)
                        // Минус минута: Duration.toHours() режет вниз, и ровно круглый час
                        // на границе мог бы дать 23 вместо 24 из-за микросекунд.
                        .occurredAt(LocalDateTime.now().minusHours(hours).minusMinutes(1))
                        .build()));
    }

    private static Subscription parked() {
        return Subscription.builder()
                .id(7L).telegramId(TG_ID).seq(2).current(false)
                .balanceSeconds(SubscriptionTime.daysToSeconds(30))
                .build();
    }
}
