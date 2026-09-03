package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.PendingGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PendingGrantRepository extends JpaRepository<PendingGrant, Long> {
    /**
     * Все действующие неиспользованные гранты для данного юзера, в порядке выдачи. У одного
     * юзера может накопиться несколько грантов (например, два приза в одном розыгрыше) —
     * applyIfAny применит их все за один проход и каждому отдельно сделает extend.
     * <p>
     * Username сравнивается case-insensitive (LOWER на обеих сторонах), потому что
     * Telegram возвращает username в регистре, выбранном юзером, и регистр между
     * вызовами не гарантируется.
     * <p>
     * Просроченные ({@code expires_at <= now}) не возвращаются: приз с истёкшим сроком не
     * должен срабатывать ни у того, кому его выдали, ни у того, кто позже займёт этот ник.
     * Момент времени передаётся параметром, а не берётся из БД ({@code CURRENT_TIMESTAMP}) —
     * {@code expires_at} пишется часами JVM, читаться он обязан теми же часами.
     */
    @Query(
            "SELECT g FROM PendingGrant g WHERE LOWER(g.username) = LOWER(:username) " +
            "AND g.used = false AND g.expiresAt > :now ORDER BY g.grantedAt ASC")
    List<PendingGrant> findUnusedByUsername(@Param("username") String username,
                                            @Param("now") LocalDateTime now);

    /** Короткая форма «на сейчас» — для вызывающих, которым не нужен свой момент времени. */
    default List<PendingGrant> findUnusedByUsername(String username) {
        return findUnusedByUsername(username, LocalDateTime.now());
    }

    /**
     * То же, но с row-lock: применение гранта («выдать дни» + «пометить использованным») —
     * не атомарная операция, а хук {@code applyIfAny} висит сразу на двух входах ({@code /start}
     * в боте и {@code /api/me} в Mini App), которые реально срабатывают почти одновременно при
     * первом открытии. Без блокировки оба потока читали {@code used = false} и начисляли дни
     * дважды. Второй поток теперь ждёт коммита первого, после чего Postgres перепроверяет
     * условие {@code used = false} на заблокированной строке и она из выборки выпадает.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT g FROM PendingGrant g WHERE LOWER(g.username) = LOWER(:username) " +
            "AND g.used = false AND g.expiresAt > :now ORDER BY g.grantedAt ASC")
    List<PendingGrant> findUnusedByUsernameForUpdate(@Param("username") String username,
                                                     @Param("now") LocalDateTime now);

    /** Короткая форма «на сейчас» — см. {@link #findUnusedByUsername(String)}. */
    default List<PendingGrant> findUnusedByUsernameForUpdate(String username) {
        return findUnusedByUsernameForUpdate(username, LocalDateTime.now());
    }

    /**
     * Чистка по сроку хранения: удаляет невостребованные гранты, у которых вышел срок.
     * Вызывается раз в сутки из {@link com.orchestra.subscriptions.service.RetentionCleanupService}.
     * <p>
     * Использованные гранты НЕ трогаем: они уже никого не активируют (фильтр
     * {@code used = false}), зато отвечают на вопрос «кому и когда достался приз» —
     * гранты выдаёт админ руками, их единицы, и аудит тут дороже экономии строк.
     */
    @Modifying
    @Query("DELETE FROM PendingGrant g WHERE g.used = false AND g.expiresAt < :cutoff")
    int deleteUnusedExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
