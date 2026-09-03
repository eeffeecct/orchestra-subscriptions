package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.MagicLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface MagicLinkRepository extends JpaRepository<MagicLink, Long> {

    /** Лукап по токену из URL ?token=... Используется в GET /auth. */
    Optional<MagicLink> findByToken(String token);

    /**
     * Удаляет давно протухшие токены — освобождает место и убирает PII (email)
     * из БД через разумный срок. Срабатывает раз в час из {@link com.orchestra.subscriptions.service.MagicLinkService}.
     * Окно «expires_at < cutoff» даёт гистерезис: токен, которому только что
     * вышел срок, ещё лежит ~сутки на случай если @Scheduled запускался прямо
     * в момент истечения.
     */
    @Modifying
    @Query("DELETE FROM MagicLink m WHERE m.expiresAt < :cutoff")
    int deleteAllExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
