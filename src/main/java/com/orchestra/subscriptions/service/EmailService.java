package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.util.EmailMasker;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Транзакционная email-рассылка через SMTP. Конкретный провайдер настраивается
 * через {@code spring.mail.*} в {@code application.yml} (host/port/login/password из ENV).
 * <p>
 * Используется для:
 * <ul>
 *   <li>Magic-link авторизация в web-ЛК (когда пользователь не может в Telegram).</li>
 *   <li>Резервная копия параметров доступа при продлении (на случай блокировки TG).</li>
 *   <li>Критичные нотификации (отказ платежа, refund подтверждён, и т.п.).</li>
 * </ul>
 * <p>
 * Контракт класса:
 * <ul>
 *   <li>Если {@code app.email.enabled=false} (дефолт) — методы логируют «would send»,
 *       но реально через SMTP не шлют. Безопасный режим для dev/локального запуска:
 *       случайно отправить письмо во время теста невозможно.</li>
 *   <li>Все методы swallow-аят исключения: падение SMTP не должно ронять платёж/активацию
 *       подписки. Любой failure → WARN в логах, метод возвращается без исключения.</li>
 *   <li>{@code JavaMailSender} bean создаёт Spring Boot из {@code spring.mail.*}, см.
 *       {@code application.yml}. Если SMTP-пароль не задан — bean всё равно существует,
 *       но реальная отправка упадёт на {@code AuthenticationFailedException};
 *       флаг {@code enabled} — защита от этого в dev.</li>
 *   <li>Кодировка UTF-8 настроена через {@code spring.mail.default-encoding} —
 *       без неё русские subject/body уходят в крякозябрах.</li>
 *   <li>Адрес получателя НИКОГДА не пишется в лог в открытом виде — только через
 *       {@link EmailMasker#mask(String)} (152-ФЗ, ст.5: минимально необходимый объём ПДн).
 *       Правило действует и для dry-run: в dev адрес такой же настоящий.</li>
 * </ul>
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final AppConfig.EmailConfig config;

    public EmailService(JavaMailSender mailSender, AppConfig appConfig) {
        this.mailSender = mailSender;
        this.config = appConfig.getEmail() != null
                ? appConfig.getEmail()
                : new AppConfig.EmailConfig();
        if (!this.config.isEnabled()) {
            log.info("EmailService disabled (app.email.enabled=false) — sends will be logged only");
        } else {
            log.info("EmailService enabled, from={}", this.config.getFrom());
        }
    }

    /**
     * Отправляет простое текстовое письмо (без HTML). Для писем с разметкой —
     * см. {@link #sendHtml(String, String, String, String)}.
     *
     * @param to      адрес получателя; null/blank → no-op + WARN
     * @param subject тема письма
     * @param body    тело письма (plain text)
     * @return {@code true} если SMTP принял сообщение (или dry-run), {@code false} если
     *         адрес пустой / SMTP вернул ошибку (например, suppression list, hard bounce).
     *         Контракт по-прежнему swallow'ит исключения — caller не получит throw, но
     *         может проверить return и применить fallback (например, DM в Telegram).
     */
    public boolean send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            log.warn("EmailService.send skipped: empty 'to' address");
            return false;
        }
        if (!config.isEnabled()) {
            log.info("EmailService [DRY-RUN] would send to={} subject={}", EmailMasker.mask(to), subject);
            // Dry-run = «как будто отправили»: иначе в dev-режиме каждый caller
            // дёргал бы fallback (DM в TG и т.п.) при тестах, что засоряло бы
            // вывод и пользовательский TG. На проде enabled=true, dry-run не работает.
            return true;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(config.getFrom());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email sent to={} subject={}", EmailMasker.mask(to), subject);
            return true;
        } catch (Exception e) {
            // Плейсхолдеров ровно 2, аргументов 3 → последний (e) остаётся throwable'ом
            // и печатается стектрейсом. Добавишь третий {} — стектрейс молча исчезнет.
            log.warn("EmailService.send failed to={} subject={}", EmailMasker.mask(to), subject, e);
            return false;
        }
    }

    /**
     * Отправляет HTML-письмо с plain-text fallback'ом для клиентов, не отображающих
     * HTML (или для пользователей с настройкой «только plain»). Использует
     * {@link MimeMessageHelper} с {@code multipart=true} — внутри multipart/alternative
     * с обеими частями. Кодировка UTF-8 проставляется явно — без неё кириллица
     * в subject уходит в крякозябрах независимо от {@code default-encoding}.
     * <p>
     * Контракт идентичен {@link #send}: dry-run при {@code enabled=false}, swallow
     * исключений, no-op при пустом адресе.
     *
     * @param to          адрес получателя
     * @param subject     тема письма (любая кодировка)
     * @param htmlBody    HTML-разметка письма
     * @param plainText   fallback plain-text вариант (тот же контент, без тегов).
     *                    Если null/blank — будет автогенерирован как «html-strip»
     *                    Spring'ом, что выглядит уродливо; лучше передавать явно.
     */
    public boolean sendHtml(String to, String subject, String htmlBody, String plainText) {
        if (to == null || to.isBlank()) {
            log.warn("EmailService.sendHtml skipped: empty 'to' address");
            return false;
        }
        if (!config.isEnabled()) {
            log.info("EmailService [DRY-RUN] would send (HTML) to={} subject={}", EmailMasker.mask(to), subject);
            return true;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(config.getFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            // setText(text, html) → multipart/alternative. Современные почтовики
            // показывают HTML, старые/text-only — увидят plainText.
            helper.setText(plainText != null && !plainText.isBlank() ? plainText : "", htmlBody);
            mailSender.send(message);
            log.info("Email (HTML) sent to={} subject={}", EmailMasker.mask(to), subject);
            return true;
        } catch (Exception e) {
            // См. комментарий в send(): e должен остаться ПОСЛЕДНИМ аргументом без своего {}.
            log.warn("EmailService.sendHtml failed to={} subject={}", EmailMasker.mask(to), subject, e);
            return false;
        }
    }
}
