package com.orchestra.subscriptions.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-тест на маскирование адреса в логах {@link EmailService} — без Spring контекста.
 * <p>
 * Защищает 152-ФЗ-инвариант: ни одна строка лога сервиса не содержит адрес получателя
 * в открытом виде — ни в dry-run (в dev адрес такой же настоящий), ни на успешной
 * отправке, ни в ветке ошибки SMTP.
 * <p>
 * Заодно проверяет, что в WARN-ветках исключение осталось ПОСЛЕДНИМ аргументом без
 * своего {@code {}} — иначе SLF4J подставил бы его как параметр и стектрейс исчез бы.
 */
class EmailServiceMaskingTest {

    /** Адрес, который гоняем через сервис. Локальная часть — заведомо узнаваемая. */
    private static final String RAW_EMAIL = "nikolay.petrov@example.com";
    /** Ожидаемый вид после {@code EmailMasker.mask}. */
    private static final String MASKED_EMAIL = "n***@example.com";

    /**
     * «Голый» адрес в логе: 2+ обычных символа вплотную перед {@code @}.
     * Звёздочка исключена намеренно: маска даёт «n***@example.com», где перед {@code @}
     * всегда стоит {@code *}, поэтому замаскированный адрес под шаблон не подпадает,
     * а любой незамаскированный («to=nikolay.petrov@…») — подпадает.
     */
    private static final Pattern BARE_EMAIL = Pattern.compile("[^@\\s*]{2,}@");

    private JavaMailSender mailSender;
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        logger = (Logger) LoggerFactory.getLogger(EmailService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void dryRunNeverLogsBareAddress() {
        EmailService service = service(false);

        assertThat(service.send(RAW_EMAIL, "Тема письма", "Текст")).isTrue();
        assertThat(service.sendHtml(RAW_EMAIL, "Тема письма", "<p>Текст</p>", "Текст")).isTrue();

        List<String> messages = capturedMessages();
        assertThat(messages).hasSize(2);
        assertNoBareAddress(messages);
        // Маска действительно проставлена, а не адрес просто выкинут из строки
        assertThat(messages).allSatisfy(m -> assertThat(m).contains(MASKED_EMAIL));
    }

    @Test
    void smtpFailureNeverLogsBareAddress() {
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));
        when(mailSender.createMimeMessage()).thenThrow(new MailSendException("SMTP down"));

        EmailService service = service(true);

        assertThat(service.send(RAW_EMAIL, "Тема письма", "Текст")).isFalse();
        assertThat(service.sendHtml(RAW_EMAIL, "Тема письма", "<p>Текст</p>", "Текст")).isFalse();

        List<String> messages = capturedMessages();
        assertThat(messages).hasSize(2);
        assertNoBareAddress(messages);
        assertThat(messages).allSatisfy(m -> assertThat(m).contains(MASKED_EMAIL));

        // Исключение осталось throwable'ом (2 плейсхолдера + 3 аргумента), а не параметром
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    /**
     * Собирает сервис и чистит буфер: конструктор пишет собственный from-адрес сервиса
     * (конфиг, не ПДн пользователя) — он не должен мешать проверке строк отправки.
     */
    private EmailService service(boolean enabled) {
        AppConfig appConfig = new AppConfig();
        appConfig.getEmail().setEnabled(enabled);
        EmailService service = new EmailService(mailSender, appConfig);
        appender.list.clear();
        return service;
    }

    private List<String> capturedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertNoBareAddress(List<String> messages) {
        assertThat(messages).isNotEmpty();
        assertThat(messages).allSatisfy(message -> {
            assertThat(message).doesNotContain(RAW_EMAIL);
            assertThat(message).doesNotContain("nikolay.petrov");
            assertThat(BARE_EMAIL.matcher(message).find())
                    .as("строка лога содержит незамаскированный адрес: %s", message)
                    .isFalse();
        });
    }
}
