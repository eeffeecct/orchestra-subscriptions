package com.orchestra.subscriptions.web.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate-limiter для отправки magic-link'ов: защита от случайных
 * двойных кликов («не чаще раза в минуту») и от targeted abuse («не больше
 * N в час»).
 * <p>
 * Хранит {@code Deque<Instant>} попыток на каждый ключ. Ключей три вида, по одному
 * на канал запроса: {@code tg:<telegram_id>} (Mini App), {@code ip:<адрес>}
 * (публичный /auth/request — email там ещё не известен) и {@code email:<sha256>}
 * (адресный лимит уже при отправке письма; сам адрес в память не кладём).
 * При вызове чистит устаревшие отметки и проверяет лимиты.
 * <p>
 * <b>In-memory без Redis</b> — у нас один Spring-инстанс, кластера нет.
 * При рестарте rate-limit обнуляется, что считаем приемлемым (рестарт
 * редкий, abuse продолжать через рестарт всё равно неудобно атакующему).
 * <p>
 * Memory upper-bound: ~100 байт на ключ × 1000 уникальных ключей = ~100КБ.
 * Рост ограничен периодической чисткой простаивающих ключей — см. {@link #sweepIdle()}.
 */
@Slf4j
@Component
public class MagicLinkRateLimiter {

    /** Минимальный интервал между двумя последовательными запросами. */
    public static final Duration COOLDOWN = Duration.ofMinutes(1);

    /** Окно для часового лимита. */
    public static final Duration WINDOW = Duration.ofHours(1);

    /** Максимум запросов в окне {@link #WINDOW}. */
    public static final int MAX_PER_WINDOW = 5;

    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    /**
     * Проверяет можно ли отправить magic-link. Если да — фиксирует попытку
     * и возвращает {@code Decision.allow()}. Если нет — возвращает причину.
     */
    public synchronized Decision tryAcquire(String key) {
        Instant now = Instant.now();
        Deque<Instant> deque = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());

        // Чистим хвост: всё что старше WINDOW.
        while (!deque.isEmpty() && deque.peekFirst().isBefore(now.minus(WINDOW))) {
            deque.pollFirst();
        }

        // Cooldown: последний запрос был менее COOLDOWN назад.
        Instant last = deque.peekLast();
        if (last != null && last.plus(COOLDOWN).isAfter(now)) {
            long secondsLeft = COOLDOWN.minus(Duration.between(last, now)).toSeconds() + 1;
            return Decision.deny("cooldown", secondsLeft);
        }

        // Часовой лимит.
        if (deque.size() >= MAX_PER_WINDOW) {
            // retry-after до момента когда самая старая попытка выпадет из окна
            Instant firstOldest = deque.peekFirst();
            long secondsLeft = Duration.between(now, firstOldest.plus(WINDOW)).toSeconds() + 1;
            return Decision.deny("max_per_hour", Math.max(secondsLeft, 1));
        }

        deque.addLast(now);
        return Decision.allow();
    }

    /**
     * Периодическая чистка: удаляет ключи, у которых не осталось попыток в окне.
     * Без неё {@code attempts} рос бы неограниченно — каждый новый ключ (в т.ч.
     * подделанный IP) оставлял вечную запись → memory-DoS. Раз в 10 минут держит
     * map ограниченным числом ключей, активных в пределах {@link #WINDOW}.
     */
    @Scheduled(fixedDelay = 600_000L)
    public synchronized void sweepIdle() {
        Instant cutoff = Instant.now().minus(WINDOW);
        attempts.entrySet().removeIf(e -> {
            Deque<Instant> d = e.getValue();
            while (!d.isEmpty() && d.peekFirst().isBefore(cutoff)) {
                d.pollFirst();
            }
            return d.isEmpty();
        });
    }

    /** Только для тестов: очистить состояние. */
    public synchronized void resetForTesting() {
        attempts.clear();
    }

    /** Результат проверки лимита. */
    public record Decision(boolean allowed, String reason, long retryAfterSeconds) {
        static Decision allow() { return new Decision(true, null, 0); }
        static Decision deny(String reason, long retryAfter) {
            return new Decision(false, reason, retryAfter);
        }
    }
}
