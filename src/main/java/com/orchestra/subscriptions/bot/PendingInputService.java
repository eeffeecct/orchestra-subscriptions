package com.orchestra.subscriptions.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ожидаемый следующий текстовый ввод юзера в чате бота (промокод / имя устройства / email)
 * + применённый промокод для покупки. Бот у нас stateless по дизайну, но reply-флоу без
 * какого-то состояния не сделать — это минимальный in-memory вариант:
 * <ul>
 *   <li>Единственный инстанс приложения (docker-контейнер приложения) — распределённого стейта не нужно.</li>
 *   <li>TTL 10 минут: забытое ожидание не живёт вечно; рестарт бота просто сбрасывает
 *       незавершённые вводы (юзер повторит — ничего не теряется).</li>
 *   <li>Любая команда или кнопка нижнего меню сбрасывает ожидание (см. SubscriptionBot).</li>
 * </ul>
 */
@Slf4j
@Component
public class PendingInputService {

    /** Что бот ждёт от юзера следующим сообщением. */
    public enum Kind { PROMO, DEVICE_NAME, EMAIL }

    /** Ожидание ввода: тип + произвольный payload (напр. «planId:tierId» или deviceId). */
    public record PendingInput(Kind kind, String payload, Instant expiresAt) {
        public boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final Duration INPUT_TTL = Duration.ofMinutes(10);
    /** Применённый промокод живёт дольше ввода: юзер может думать между экранами. */
    private static final Duration PROMO_TTL = Duration.ofMinutes(30);

    private final Map<Long, PendingInput> pending = new ConcurrentHashMap<>();
    private final Map<Long, PendingInput> appliedPromo = new ConcurrentHashMap<>();

    public void expect(Long telegramId, Kind kind, String payload) {
        pending.put(telegramId, new PendingInput(kind, payload, Instant.now().plus(INPUT_TTL)));
    }

    /** Забирает (и снимает) ожидание ввода. Протухшее — как отсутствующее. */
    public Optional<PendingInput> consume(Long telegramId) {
        PendingInput in = pending.remove(telegramId);
        return in == null || in.expired() ? Optional.empty() : Optional.of(in);
    }

    /** Есть ли активное ожидание (без снятия) — для решения «текст = ввод или команда». */
    public boolean hasPending(Long telegramId) {
        PendingInput in = pending.get(telegramId);
        if (in != null && in.expired()) {
            pending.remove(telegramId);
            return false;
        }
        return in != null;
    }

    public void clear(Long telegramId) {
        pending.remove(telegramId);
    }

    // ---------- применённый промокод (бот-флоу покупки) ----------

    /** Запоминает валидированный промокод юзера — pay-хендлеры применят его к сумме. */
    public void applyPromo(Long telegramId, String code) {
        appliedPromo.put(telegramId, new PendingInput(Kind.PROMO, code, Instant.now().plus(PROMO_TTL)));
    }

    /** Применённый промокод (null — нет/протух). Не снимает — снимается явно clearPromo. */
    public String getAppliedPromo(Long telegramId) {
        PendingInput in = appliedPromo.get(telegramId);
        if (in == null) return null;
        if (in.expired()) {
            appliedPromo.remove(telegramId);
            return null;
        }
        return in.payload();
    }

    public void clearPromo(Long telegramId) {
        appliedPromo.remove(telegramId);
    }

    /** Уборка протухших записей — тот же паттерн, что у RequestRateLimiter.sweepIdle. */
    @Scheduled(fixedDelay = 600_000L)
    void sweepExpired() {
        pending.entrySet().removeIf(e -> e.getValue().expired());
        appliedPromo.entrySet().removeIf(e -> e.getValue().expired());
    }
}
