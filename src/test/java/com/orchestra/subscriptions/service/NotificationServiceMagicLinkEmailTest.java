package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.bot.SubscriptionBot;
import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест на {@link NotificationService#sendMagicLinkEmail} — без Spring контекста.
 * Проверяет что в HTML и plain-text fallback подставляется правильная ссылка,
 * subject соответствует, и метод no-op'ит на пустом адресе.
 */
class NotificationServiceMagicLinkEmailTest {

    private EmailService emailService;
    private AppConfig appConfig;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        appConfig = mock(AppConfig.class);
        AppConfig.TelegramConfig telegramCfg = mock(AppConfig.TelegramConfig.class);
        when(appConfig.getTelegram()).thenReturn(telegramCfg);
        service = new NotificationService(mock(SubscriptionBot.class), appConfig, emailService,
                mock(com.orchestra.subscriptions.repository.SubscriptionRepository.class));
    }

    @Test
    void noopWhenEmailNull() {
        service.sendMagicLinkEmail(null, "https://example.com/auth?token=abc");
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void noopWhenEmailBlank() {
        service.sendMagicLinkEmail("   ", "https://example.com/auth?token=abc");
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendsHtmlAndPlainWithLink() {
        String url = "https://example.com/auth?token=xyz123";
        service.sendMagicLinkEmail("user@example.com", url);

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(to.capture(), subject.capture(), html.capture(), plain.capture());

        assertThat(to.getValue()).isEqualTo("user@example.com");
        assertThat(subject.getValue()).contains("Orchestra", "вход");

        // HTML: ссылка дважды — в href кнопки и в href текстового fallback'а
        assertThat(html.getValue())
                .contains(url)
                .contains("Войти в кабинет")  // CTA на кнопке
                .contains("15 минут")          // явный срок действия
                .contains("проигнорируйте")    // защитный disclaimer
                .contains("Не пересылайте");   // anti-forward warning (защита от перехвата)

        // Plain-text: тот же URL без HTML-разметки + warning
        assertThat(plain.getValue())
                .contains(url)
                .contains("Orchestra")
                .contains("15 минут")
                .contains("Не пересылайте")
                .doesNotContain("<a ");        // никаких тегов в plain
    }

    @Test
    void returnsBooleanFromEmailService() {
        // sendMagicLinkEmail должен пробрасывать наверх результат от EmailService —
        // контроллер (WebAppController.sendMagicLink) использует его для 502 на провале.
        when(emailService.sendHtml(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        assertThat(service.sendMagicLinkEmail("user@example.com", "https://x.com/auth?token=t")).isTrue();

        when(emailService.sendHtml(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        assertThat(service.sendMagicLinkEmail("user@example.com", "https://x.com/auth?token=t")).isFalse();
    }

    @Test
    void returnsFalseOnBlankEmailWithoutCallingService() {
        assertThat(service.sendMagicLinkEmail("", "https://x.com/auth?token=t")).isFalse();
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString(), anyString());
    }
}
