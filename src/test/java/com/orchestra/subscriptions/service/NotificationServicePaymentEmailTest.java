package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.bot.SubscriptionBot;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест на {@link NotificationService#sendPaymentSuccessEmail} — без Spring контекста.
 * Проверяем что:
 *  - null/blank email → emailService.sendHtml не зовётся (no-op),
 *  - заданный email → зовётся ровно один раз с правильным HTML и plain-fallback,
 *  - guideUrl корректно подставляется (или опускается, если в конфиге пусто).
 * <p>
 * 2026-05-29: метод переведён с {@code emailService.send} (plain-text) на
 * {@code emailService.sendHtml} (multipart HTML + plain) — Gmail дропал plain-text-only
 * транзакционные письма. Подробности в javadoc метода.
 * <p>
 * Сам факт надёжной доставки покрыт контрактом {@link EmailService}: он swallow'ит
 * исключения и dry-run'ит при {@code app.email.enabled=false}, отдельно тестируется
 * (для нас тут — чёрный ящик, проверяем что туда уходит правильный текст).
 */
class NotificationServicePaymentEmailTest {

    private EmailService emailService;
    private AppConfig appConfig;
    private AppConfig.TelegramConfig telegramCfg;
    private SubscriptionBot subscriptionBot;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        appConfig = mock(AppConfig.class);
        telegramCfg = mock(AppConfig.TelegramConfig.class);
        when(appConfig.getTelegram()).thenReturn(telegramCfg);
        when(telegramCfg.getGuideUrl()).thenReturn("https://example.com/guide");
        // SubscriptionBot — verifiable mock, чтобы проверять DM-fallback (отправляется в TG при
        // неудаче доставки email — например, suppression list у SMTP-провайдера).
        subscriptionBot = mock(SubscriptionBot.class);
        // По умолчанию emailService.sendHtml возвращает true (успешная доставка) —
        // тесты на «email сломан» переопределяют это явно.
        when(emailService.sendHtml(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        service = new NotificationService(subscriptionBot, appConfig, emailService,
                mock(com.orchestra.subscriptions.repository.SubscriptionRepository.class));
    }

    private Customer userWith(String email) {
        return Customer.builder()
                .telegramId(1L)
                .providerUsername("sub_1")
                .subscriptionLink("https://example.com/sub/abcdef")
                .expiresAt(LocalDateTime.of(2026, 6, 1, 12, 0))
                .email(email)
                .build();
    }

    private AppConfig.Plan samplePlan() {
        AppConfig.Plan plan = new AppConfig.Plan();
        plan.setId("month_1");
        plan.setName("1 месяц");
        plan.setDays(30);
        plan.setPriceKopeks(15_000L);
        return plan;
    }

    @Test
    void noopWhenEmailNull() {
        service.sendPaymentSuccessEmail(userWith(null), samplePlan());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void noopWhenEmailBlank() {
        service.sendPaymentSuccessEmail(userWith("   "), samplePlan());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendsWithExpectedBody() {
        service.sendPaymentSuccessEmail(userWith("user@example.com"), samplePlan());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(to.capture(), subject.capture(), html.capture(), plain.capture());

        assertThat(to.getValue()).isEqualTo("user@example.com");
        assertThat(subject.getValue()).contains("Orchestra", "активирован");

        // Plain-text fallback — содержит человекочитаемое содержимое, URL'ы без escape'а.
        assertThat(plain.getValue())
                .contains("Тариф: 1 месяц")
                .contains("https://example.com/sub/abcdef")
                .contains("https://example.com/guide")
                .contains("Профиль → Email");

        // HTML body — структура (doctype + бренд) + те же ключевые куски (название
        // плана, префикс subscription-ссылки до URL-special-символов, кнопка-инструкция).
        assertThat(html.getValue())
                .startsWith("<!DOCTYPE html>")
                .contains("Orchestra")
                .contains("1 месяц")
                .contains("https://example.com/sub/abcdef")
                .contains("Инструкция по подключению")
                .contains("Профиль → Email");
    }

    @Test
    void omitsGuideUrlWhenBlank() {
        when(telegramCfg.getGuideUrl()).thenReturn("");

        service.sendPaymentSuccessEmail(userWith("user@example.com"), samplePlan());

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(any(), any(), html.capture(), plain.capture());
        assertThat(plain.getValue()).doesNotContain("Инструкция:");
        assertThat(html.getValue()).doesNotContain("Инструкция по подключению");
    }

    /**
     * Когда SMTP отказался доставить (suppression list, hard bounce и т.п.) —
     * шлём DM в Telegram с объяснением. Сценарий 2026-05-29: Unisender блочил
     * user@example.com, юзер видел «email привязан» но писем не получал и
     * думал что сервис сломан. Теперь предупреждаем.
     */
    @Test
    void dmFallbackWhenEmailDeliveryFails() throws Exception {
        when(emailService.sendHtml(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        service.sendPaymentSuccessEmail(userWith("user@example.com"), samplePlan());

        ArgumentCaptor<SendMessage> dm = ArgumentCaptor.forClass(SendMessage.class);
        verify(subscriptionBot).execute(dm.capture());

        SendMessage sent = dm.getValue();
        assertThat(sent.getChatId()).isEqualTo("1"); // telegramId юзера
        assertThat(sent.getParseMode()).isEqualTo("HTML");
        assertThat(sent.getText())
                .contains("Копия подписки не дошла на email")
                .contains("Профиль → Email")  // куда идти чтобы сменить
                .contains("ссылка-конфиг — в сообщении выше");  // не дублируем саму ссылку
    }

    @Test
    void noDmFallbackWhenEmailDeliverySucceeds() throws Exception {
        // default mock = sendHtml → true (см. setUp)
        service.sendPaymentSuccessEmail(userWith("user@example.com"), samplePlan());

        verify(subscriptionBot, never()).execute(any(SendMessage.class));
    }
}
