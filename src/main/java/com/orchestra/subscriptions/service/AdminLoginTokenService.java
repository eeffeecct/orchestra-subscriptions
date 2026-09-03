package com.orchestra.subscriptions.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Одноразовые ссылки входа в админ-панель. Владелец пишет боту {@code /admin}, получает
 * ссылку и открывает её в браузере — это и есть весь вход. Пароля нет намеренно: второй
 * секрет пришлось бы где-то хранить и когда-нибудь ротировать, а владение Telegram-аккаунтом
 * владельца мы и так вынуждены считать доверенным (через бота отдаются все админ-команды).
 * <p>
 * <b>Хранение в памяти.</b> Токен живёт 10 минут и не переживает рестарт — это правильный
 * срок жизни для «сейчас открою панель». Таблица в БД дала бы долгоживущие записи с полным
 * доступом, которые нужно чистить.
 * <p>
 * <b>Почему токен не гасится первым же открытием.</b> Ссылку видит Telegram-клиент, а он
 * (и антивирус, и предпросмотр) умеет ходить по ссылкам раньше человека. Одноразовый токен
 * в этих условиях регулярно сгорал бы до первого клика, и вход выглядел бы сломанным.
 * Поэтому окно 10 минут, внутри него ссылка рабочая, а выдача новой гасит предыдущую —
 * действующая ссылка всегда ровно одна.
 */
@Slf4j
@Service
public class AdminLoginTokenService {

    /** Сколько ссылка действительна. Больше не нужно: владелец открывает её сразу. */
    public static final Duration TTL = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(long telegramId, Instant expiresAt) {}

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    /**
     * Выпускает новую ссылку для владельца и гасит все его предыдущие.
     *
     * @return opaque-токен для {@code /account/admin/enter?token=…}
     */
    public String issue(long telegramId) {
        tokens.values().removeIf(e -> e.telegramId() == telegramId);
        purgeExpired();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, new Entry(telegramId, Instant.now().plus(TTL)));
        log.info("Admin panel login link issued for telegramId={}", telegramId);
        return token;
    }

    /** Кому принадлежит действующий токен. Пусто — токена нет или он просрочен. */
    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Entry entry = tokens.get(token);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(entry.telegramId());
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tokens.values().removeIf(e -> e.expiresAt().isBefore(now));
    }
}
