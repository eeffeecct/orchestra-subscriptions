package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.PendingGrant;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PendingGrantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Отложенные подарочные подписки (победителям конкурса).
 * <p>
 * Поток: админ через {@code /grant @username 30} вызывает {@link #createGrant};
 * запись попадает в {@code pending_grants}. Когда юзер впервые открывает бот или
 * Mini App, хук вызывает {@link #applyIfAny}, и грант применяется: создаётся/продлевается
 * подписка через {@link AccessService#activatePaidSubscription} (тот же путь, что у платной
 * оплаты — корректно стекает дни поверх триала/disabled/активного), грант помечается
 * как использованный, юзеру уходит DM с поздравлением.
 * <p>
 * Привязка к {@code username}, а не к {@code telegram_id}: админ в канале знает только
 * @username, а Bot API не отдаёт telegram_id незнакомых юзеров. Передать «приз»
 * нельзя — он на ник, не на код; кто бы ни был под этим @ник'ом в момент захода —
 * тот и получит. Если юзер сменил ник до того как зашёл — приз достанется новому
 * владельцу ника (это известный edge case, на 41 подписчика в канале практически нерелевантный).
 * <p>
 * Срок годности: невостребованный приз живёт {@code app.retention.pending-grant-days}
 * (по умолчанию 30 дней), после чего не срабатывает и удаляется
 * {@link RetentionCleanupService}. Без срока запись хранила бы ник человека вечно и вечно
 * оставалась бы готовой сработать на того, кто займёт этот ник хоть через два года.
 */
@Slf4j
@Service
public class PendingGrantService {

    private final PendingGrantRepository pendingGrantRepository;
    private final AccessService accessService;
    /** Lazy, потому что NotificationService инжектит SubscriptionBot, который в свою очередь
     *  инжектит AdminService — а через него и нас. Без Lazy ловим circular dependency. */
    private final NotificationService notificationService;

    /**
     * Сколько дней живёт невостребованный приз. Дальше он не срабатывает и удаляется
     * плановой чисткой ({@link RetentionCleanupService}).
     */
    @Value("${app.retention.pending-grant-days:30}")
    private int pendingGrantRetentionDays;

    public PendingGrantService(PendingGrantRepository pendingGrantRepository,
                               AccessService accessService,
                               @Lazy NotificationService notificationService) {
        this.pendingGrantRepository = pendingGrantRepository;
        this.accessService = accessService;
        this.notificationService = notificationService;
    }

    /**
     * Создать отложенный грант. Username нормализуется к lower-case (см. контракт
     * {@link PendingGrantRepository#findUnusedByUsername(String)}). Возвращает сохранённую сущность —
     * админ-команда использует её id в подтверждающем сообщении.
     */
    @Transactional
    public PendingGrant createGrant(String username, int days, Long grantedByTelegramId) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (days <= 0 || days > 3650) {
            throw new IllegalArgumentException("days must be 1..3650");
        }
        String normalized = username.toLowerCase().replaceFirst("^@", "");
        LocalDateTime now = LocalDateTime.now();
        // Срок годности: приз ждёт человека, а не висит вечно. Минимум сутки — чтобы кривое
        // значение в конфиге не создавало гранты, просроченные в момент создания.
        int ttlDays = Math.max(1, pendingGrantRetentionDays);
        PendingGrant grant = PendingGrant.builder()
                .username(normalized)
                .days(days)
                .grantedAt(now)
                .expiresAt(now.plusDays(ttlDays))
                .grantedByTelegramId(grantedByTelegramId)
                .used(false)
                .build();
        PendingGrant saved = pendingGrantRepository.save(grant);
        log.info("Pending grant created: id={} username=@{} days={} by={} expiresAt={}",
                saved.getId(), normalized, days, grantedByTelegramId, saved.getExpiresAt());
        return saved;
    }

    /**
     * Применить все действующие неиспользованные гранты для данного юзера за один вызов.
     * Просроченные (см. {@code expires_at}) не применяются.
     * <p>
     * Идемпотентен: повторный вызов с тем же telegramId/username безопасен — все
     * не-used гранты применятся ровно один раз, последующие вызовы найдут пустой
     * список. Хук вызывается из двух мест ({@code /start} в боте и {@code /api/me}
     * в Mini App), поэтому свойство критично.
     * <p>
     * Если несколько грантов накопилось (например, 2 приза в одном розыгрыше),
     * каждый применяется отдельным вызовом {@code activatePaidSubscription} в порядке
     * {@code granted_at} — это даёт корректное stacking (дни складываются поверх друг
     * друга). DM отправляется один — суммарным числом дней; альтернатива (DM на каждый
     * грант) спамит юзера и плохо смотрится.
     *
     * @return суммарное число применённых дней, или 0 если грантов не было
     */
    @Transactional
    public int applyIfAny(Long telegramId, String username) {
        if (telegramId == null || username == null || username.isBlank()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        // Под row-lock: хук зовётся и из /start, и из /api/me — при первом открытии продукта
        // они стартуют почти одновременно, и без блокировки оба потока видели грант
        // неиспользованным и начисляли дни дважды (см. findUnusedByUsernameForUpdate).
        // Просроченные гранты выборка не отдаёт — их удалит плановая чистка.
        List<PendingGrant> grants = pendingGrantRepository.findUnusedByUsernameForUpdate(username, now);
        if (grants.isEmpty()) {
            return 0;
        }

        int totalDays = 0;
        Customer lastResult = null;
        for (PendingGrant grant : grants) {
            try {
                lastResult = accessService.addDays(telegramId, username, grant.getDays(),
                        com.orchestra.subscriptions.model.SubscriptionLedger.Kind.GRANT);
                grant.setUsed(true);
                grant.setUsedAt(now);
                grant.setUsedByTelegramId(telegramId);
                pendingGrantRepository.save(grant);
                totalDays += grant.getDays();
                log.info("Pending grant applied: id={} telegramId={} @{} days={}",
                        grant.getId(), telegramId, username, grant.getDays());
            } catch (Exception e) {
                // AccessProvider упал / транзакция откатилась. Грант оставляем не-used —
                // следующий /start или /api/me попробует ещё раз. Owner-алерт уже
                // вылетел внутри activatePaidSubscription через notifyOwnerAccessSyncFailure.
                log.error("Failed to apply pending grant id={} telegramId={} @{}",
                        grant.getId(), telegramId, username, e);
                break;
            }
        }

        if (totalDays > 0 && lastResult != null) {
            try {
                notificationService.sendGrantApplied(telegramId, totalDays, lastResult.getExpiresAt());
            } catch (Exception e) {
                // DM упал (юзер заблочил бот, например) — не откатываем сам грант.
                log.warn("Failed to send grant DM to telegramId={}: {}", telegramId, e.getMessage());
            }
        }

        return totalDays;
    }
}
