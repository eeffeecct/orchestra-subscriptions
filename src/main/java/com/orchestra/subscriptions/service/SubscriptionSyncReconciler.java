package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Доводит до панели AccessProvider намерения, которые не удалось отправить сразу.
 * <p>
 * <b>Зачем это нужно.</b> Переключение подписок фиксируется в базе, а панель обновляется
 * следом, отдельным вызовом — иначе недоступная панель откатывала бы уже принятое решение.
 * Значит между коммитом и панелью есть окно: упасть можно и внутри него. Намерение
 * записывается в ТОЙ ЖЕ транзакции, что и состояние, поэтому падение в окне не теряется —
 * незакрытое намерение просто дождётся этого прогона.
 * <p>
 * Результат: панель всегда сходится к состоянию базы, а не наоборот. Повторная отправка
 * безвредна — {@link AccessSyncService} ставит абсолютное состояние, а не инкремент.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionSyncReconciler {

    /**
     * После скольких неудач подряд перестаём шуметь в лог на каждом прогоне. Попытки при этом
     * НЕ прекращаются: подписка без синхронизации — это либо человек без доступа, либо
     * наоборот доступ без оплаты, и сдаваться тут нельзя.
     */
    private static final int QUIET_AFTER_ATTEMPTS = 5;

    private final SubscriptionRepository subscriptionRepository;
    private final AccessSyncService accessSync;

    @Value("${app.subscriptions.sync-enabled:true}")
    private boolean enabled;

    /** Раз в минуту: окно между коммитом и панелью маленькое, закрывать его надо быстро. */
    @Scheduled(fixedDelay = 60_000L)
    public void reconcile() {
        if (!enabled) {
            return;
        }
        try {
            List<Subscription> pending =
                    subscriptionRepository.findByProviderIntentIsNotNullOrderByProviderIntentAtAsc();
            if (pending.isEmpty()) {
                return;
            }
            for (Subscription sub : pending) {
                if (sub.getProviderAttempts() < QUIET_AFTER_ATTEMPTS) {
                    log.info("Досылаю в панель подписку id={} (намерение {}, попытка {})",
                            sub.getId(), sub.getProviderIntent(), sub.getProviderAttempts() + 1);
                }
                accessSync.push(sub.getId());
            }
        } catch (Exception e) {
            log.error("Реконсилер синхронизации подписок упал — повтор через минуту", e);
        }
    }
}
