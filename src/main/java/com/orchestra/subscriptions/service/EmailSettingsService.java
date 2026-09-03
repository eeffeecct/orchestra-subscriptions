package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.util.EmailMasker;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import com.orchestra.subscriptions.web.api.MagicLinkRateLimiter;
import com.orchestra.subscriptions.web.api.RequestRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * Управление email юзера (152-ФЗ opt-in) — общая логика для ДВУХ поверхностей:
 * Mini App ({@code WebAppController /api/me/email*}) и чат-бот (флоу «✉️ Email» в Кабинете,
 * добавлен когда у части юзеров перестал открываться Mini App WebView). Вынесено из
 * WebAppController 1-в-1: коды ошибок и тексты сохранены — их проверяют интеграционные
 * тесты Mini App-эндпоинтов, и их же бот показывает как есть.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSettingsService {

    /**
     * Простая валидация формата email. Намеренно не пытаемся реализовать полный RFC 5322 —
     * достаточно: 1+ символ + @ + домен + TLD 2+, длина ≤ 254 (лимит SMTP RFC 5321 §4.5.3.1).
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    private final CustomerRepository userRepository;
    private final RequestRateLimiter requestRateLimiter;
    private final MagicLinkRateLimiter magicLinkRateLimiter;
    private final MagicLinkService magicLinkService;
    private final NotificationService notificationService;
    private final AppConfig appConfig;

    /**
     * Результат операции. {@code error == null} — успех ({@code email}/{@code verified}
     * актуальны). Иначе {@code error} — машинный код (HTTP-статус выбирает вызывающая
     * сторона), {@code message} — готовый текст юзеру, {@code retryAfterSeconds}/{@code
     * rateLimitReason} — детали 429 для Mini App.
     */
    public record Outcome(String error, String message, String email, boolean verified,
                          Long retryAfterSeconds, String rateLimitReason) {
        public boolean ok() {
            return error == null;
        }

        static Outcome ok(String email, boolean verified, String message) {
            return new Outcome(null, message, email == null ? "" : email, verified, null, null);
        }

        static Outcome fail(String error, String message) {
            return new Outcome(error, message, "", false, null, null);
        }

        static Outcome rateLimited(String message, long retryAfterSeconds, String reason) {
            return new Outcome("rate_limited", message, "", false, retryAfterSeconds, reason);
        }
    }

    /**
     * Сохраняет/обновляет email (lowercase; смена адреса сбрасывает верификацию и
     * инкрементит session_version — server-side revocation web-ЛК cookie). Требует
     * {@code consent=true} для нового/изменённого адреса (152-ФЗ ст.9).
     */
    @Transactional
    public Outcome updateEmail(long telegramId, String rawEmail, boolean consent) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return Outcome.fail("invalid_format", "Введите email");
        }
        String email = rawEmail.trim().toLowerCase();
        if (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches()) {
            return Outcome.fail("invalid_format", "Похоже, в адресе ошибка. Проверьте написание.");
        }

        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return Outcome.fail("user_not_found", "Профиль не найден — откройте /start.");
        }

        // No-op: тот же email — отдаём текущее состояние без save (consent не переспрашиваем).
        if (email.equals(user.getEmail())) {
            return Outcome.ok(user.getEmail(), user.getEmailVerifiedAt() != null, null);
        }

        // 152-ФЗ: при первом сохранении/смене — явное согласие (last line of defence).
        if (!consent) {
            return Outcome.fail("consent_required",
                    "Нужно согласие на обработку email — поставьте галочку.");
        }

        // Анти-enumeration: лимит на запись режет массовый перебор адресов (12 смен/час).
        if (!requestRateLimiter.tryAcquire("email-set:" + telegramId, 12, Duration.ofHours(1))) {
            return Outcome.fail("rate_limited", "Слишком много изменений email. Попробуйте позже.");
        }

        // Конфликт: email уже привязан к другому юзеру (UNIQUE подстрахует на гонке).
        Customer existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null && !existing.getTelegramId().equals(telegramId)) {
            log.warn("Email collision: telegramId={} tried to claim email already held by telegramId={}",
                    telegramId, existing.getTelegramId());
            return Outcome.fail("email_taken",
                    "Этот email уже привязан к другому аккаунту. Напишите в поддержку.");
        }

        user.setEmail(email);
        user.setEmailVerifiedAt(null);
        user.setEmailConsentAt(LocalDateTime.now());
        Integer prevSv = user.getSessionVersion();
        user.setSessionVersion((prevSv != null ? prevSv : 0) + 1);
        userRepository.save(user);

        log.info("Email updated for telegramId={} email={}", telegramId, EmailMasker.mask(email));
        return Outcome.ok(email, false, null);
    }

    /** Удаляет email (отзыв согласия 152-ФЗ): зануляет адрес/верификацию/consent + session_version++. */
    @Transactional
    public Outcome deleteEmail(long telegramId) {
        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return Outcome.fail("user_not_found", "Профиль не найден — откройте /start.");
        }
        if (user.getEmail() == null) {
            // Идемпотентно: повторный delete на пустом email — успех с пустым state.
            return Outcome.ok("", false, null);
        }
        log.info("Email deleted for telegramId={}", telegramId);
        user.setEmail(null);
        user.setEmailVerifiedAt(null);
        user.setEmailConsentAt(null);
        Integer prevSv = user.getSessionVersion();
        user.setSessionVersion((prevSv != null ? prevSv : 0) + 1);
        userRepository.save(user);
        return Outcome.ok("", false, null);
    }

    /**
     * Шлёт magic-link для входа в web-ЛК на СОХРАНЁННЫЙ email юзера (адрес из БД, не из
     * запроса — иначе можно было бы получить ссылку на свой кабинет на чужой адрес).
     */
    public Outcome sendLoginLink(long telegramId) {
        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return Outcome.fail("user_not_found", "Профиль не найден — откройте /start.");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return Outcome.fail("no_email", "Сначала сохраните email в Профиле.");
        }

        MagicLinkRateLimiter.Decision decision = magicLinkRateLimiter.tryAcquire("tg:" + telegramId);
        if (!decision.allowed()) {
            String msg = "cooldown".equals(decision.reason())
                    ? "Подождите немного перед повторной отправкой."
                    : "Слишком много запросов. Попробуйте через час.";
            return Outcome.rateLimited(msg, decision.retryAfterSeconds(), decision.reason());
        }

        String token = magicLinkService.create(user);
        String base = SubscriptionLinks.stripTrailingSlash(appConfig.getTelegram().getPublicBaseUrl());
        String authUrl = (base != null ? base : "") + "/auth?token=" + token;
        boolean sent = notificationService.sendMagicLinkEmail(user.getEmail(), authUrl);

        if (!sent) {
            log.warn("Magic-link email delivery failed for telegramId={}", telegramId);
            return Outcome.fail("email_delivery_failed",
                    "Не получилось отправить письмо. Возможно, почтовый провайдер заблокировал Ваш адрес — попробуйте другой email.");
        }
        log.info("Magic-link sent for telegramId={}", telegramId);
        return Outcome.ok(user.getEmail(), user.getEmailVerifiedAt() != null,
                "Письмо отправлено на Ваш email. Откройте его в браузере, где хотите войти.");
    }
}
