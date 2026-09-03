package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.SubscriptionLedger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SubscriptionLedgerRepository extends JpaRepository<SubscriptionLedger, Long> {

    /** История юзера, свежие сверху — для админ-команды {@code /ledger} и разбора жалоб. */
    List<SubscriptionLedger> findByTelegramIdOrderByOccurredAtDesc(Long telegramId, Pageable pageable);

    /** Последняя оплата, зачисленная в эту подписку — от неё считается срок напоминания. */
    java.util.Optional<SubscriptionLedger> findTopBySubscriptionIdAndKindInOrderByOccurredAtDesc(
            Long subscriptionId, java.util.Collection<SubscriptionLedger.Kind> kinds);

    /**
     * Сколько раз человек переключал подписки за период. Считается по журналу, а не по
     * счётчику в памяти: счётчик обнуляется рестартом контейнера, то есть лимит переключений
     * снимался бы каждым деплоем — а он существует, чтобы никто не раскачивал состояние панели.
     */
    long countByTelegramIdAndKindInAndOccurredAtAfter(Long telegramId,
                                                      java.util.Collection<SubscriptionLedger.Kind> kinds,
                                                      LocalDateTime after);

    /**
     * Записи без адреса подписки — история, сделанная до появления колонки (PR #199).
     * Читается разовым бэкфиллом {@code SubscriptionRegistryService.linkOrphanLedgerRows}.
     */
    List<SubscriptionLedger> findBySubscriptionIdIsNullOrderByOccurredAtAsc();

    /** Журнал для админ-панели. {@code telegramId = 0} — весь журнал, без фильтра по человеку. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT l FROM SubscriptionLedger l WHERE (:telegramId = 0L OR l.telegramId = :telegramId)")
    org.springframework.data.domain.Page<SubscriptionLedger> adminSearch(
            @org.springframework.data.repository.query.Param("telegramId") long telegramId,
            Pageable pageable);
}
