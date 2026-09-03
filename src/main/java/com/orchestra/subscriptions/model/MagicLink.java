package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Одноразовый токен входа в web-ЛК. Создаётся при запросе magic-link, погашается
 * при первом клике (used_at = now()). Полная семантика — см. миграцию 017.
 */
@Entity
@Table(name = "magic_links")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagicLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic-lock против race на double-consume. Если два запроса
     * одновременно консьюмят один токен — второй получит
     * {@code OptimisticLockingFailureException} на save и упадёт на
     * {@link com.orchestra.subscriptions.service.MagicLinkService.InvalidMagicLinkException}.
     */
    @Version
    private Long version;

    /** base64url(32 байта secure random). UNIQUE по миграции 017. */
    @Column(name = "token", unique = true, nullable = false, length = 64)
    private String token;

    /** Telegram ID юзера, которому принадлежит токен. Не FK — независимо от customers. */
    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    /**
     * Снапшот email на момент создания токена. Если юзер сменит email до клика —
     * токен относится к старому адресу. Это намеренно: иначе можно было бы
     * перехватить чужой адрес через смену.
     */
    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Обычно createdAt + 15 минут. Если now() > expiresAt — consume отвергается. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** NULL = не использован. После первого consume ставится now() и повторно нельзя. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
