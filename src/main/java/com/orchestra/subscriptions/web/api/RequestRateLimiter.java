package com.orchestra.subscriptions.web.api;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Общий in-memory rate-limiter со скользящим окном, параметризуемый на каждый вызов
 * ({@code maxPerWindow} + {@code window}).
 * <p>
 * В отличие от {@link MagicLinkRateLimiter} (жёстко зашитые лимиты + 1-минутный cooldown,
 * заточенные под отправку magic-link) — используется там, где нужны другие пороги:
 * публичный {@code /sub/*} (частый polling клиентских приложений) и анти-enumeration по email.
 * <p>
 * Периодический {@link #sweepIdle()} не даёт map расти неограниченно — ключи без живых
 * попыток удаляются.
 */
@Component
public class RequestRateLimiter {

    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /**
     * @return {@code true} — запрос разрешён и зафиксирован; {@code false} — лимит превышен.
     */
    public synchronized boolean tryAcquire(String key, int maxPerWindow, Duration window) {
        Instant now = Instant.now();
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
        if (deque.size() >= maxPerWindow) {
            return false;
        }
        deque.addLast(now);
        return true;
    }

    /**
     * Чистка простаивающих ключей. Cutoff — 1 час: безопасно, т.к. все вызовы используют
     * окно ≤ 1ч, значит у удаляемых (idle &gt; 1ч) ключей не может остаться живых попыток.
     */
    @Scheduled(fixedDelay = 600_000L)
    public synchronized void sweepIdle() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        hits.entrySet().removeIf(e -> {
            Deque<Instant> d = e.getValue();
            while (!d.isEmpty() && d.peekFirst().isBefore(cutoff)) {
                d.pollFirst();
            }
            return d.isEmpty();
        });
    }

    /** Только для тестов: очистить состояние (бин — синглтон, переживает между тест-методами). */
    public synchronized void resetForTesting() {
        hits.clear();
    }
}
