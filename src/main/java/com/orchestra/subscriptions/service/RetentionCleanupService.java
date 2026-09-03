package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.repository.PendingGrantRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Сроки хранения: раз в сутки убирает из БД то, что больше не нужно ни продукту, ни человеку.
 *
 * <h2>Что чистим и зачем</h2>
 * <ul>
 *   <li><b>{@code user_devices}</b> — отпечатки устройств (модель, ОС, версия, User-Agent,
 *       HWID). Это персональные данные, и до появления этой задачи они не удалялись НИЧЕМ:
 *       «сменить ссылку» стирает устройства по требованию человека, вытеснение по потолку
 *       {@code MAX_TRACKED_DEVICES} — только когда строк стало 20, а неактивная запись
 *       у обычного юзера лежала вечно.</li>
 *   <li><b>{@code pending_grants}</b> — невостребованные подарочные дни, привязанные к
 *       @username. Просроченные больше не сработают (их отсекают выборки), значит хранить
 *       чужой ник дальше незачем.</li>
 * </ul>
 *
 * <h2>Почему две отдельные @Scheduled-задачи, а не одна</h2>
 * Обе транзакционны, а транзакция включается через прокси — вызов «одна задача дёргает два
 * своих метода» проксирование обходит, и {@code @Transactional} на внутренних методах не
 * сработал бы вовсе. Плюс изоляция: упавшая чистка устройств не должна отменять чистку
 * грантов. Планировщик зовёт оба метода как бины, поэтому транзакции на месте.
 *
 * <h2>Выключение</h2>
 * {@code app.scheduler.retention-cleanup-enabled: false} — задача не делает ничего.
 * В тестах выключена (см. {@code src/test/resources/application.yml}): удаление по времени,
 * тикающее посреди suite'а, — это ровно тот источник flaky-падений, из-за которого туда же
 * вынесли {@code SubscriptionSyncReconciler}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

    private final UserDeviceRepository deviceRepository;
    private final PendingGrantRepository pendingGrantRepository;
    private final AppConfig appConfig;

    @Value("${app.scheduler.retention-cleanup-enabled:true}")
    private boolean enabled;

    /**
     * Сколько дней храним отпечаток устройства после последнего появления.
     * <p>
     * По умолчанию 180 дней — заведомо БОЛЬШЕ окна {@code app.device-inactivity-days} (30).
     * Это не вкусовщина: пока строка внутри окна активности, она занимает слот и участвует
     * в лимите устройств, а удалять такую — значит незаметно для человека отдавать слот и
     * терять его имя устройства. Полгода тишины — это уже точно не «телефон в отпуске».
     */
    @Value("${app.retention.device-days:180}")
    private int deviceRetentionDays;

    /**
     * Устройства: удаляем строки, не появлявшиеся дольше срока хранения.
     * <p>
     * 04:47 — время выбрано в стороне от других планировщиков (03:23 email-lifecycle,
     * 04:40 сверка зеркала подписок, xx:17 magic-links).
     */
    @Scheduled(cron = "0 47 4 * * *")
    @Transactional
    public void cleanupStaleDevices() {
        if (!enabled) {
            return;
        }
        int inactivityDays = Math.max(1, appConfig.getDeviceInactivityDays());
        if (deviceRetentionDays <= inactivityDays) {
            // Защита от опечатки в конфиге: с таким сроком чистка удаляла бы строки, на
            // которых ещё держится лимит устройств. Лучше не чистить вовсе и сказать об этом.
            log.warn("Чистка устройств пропущена: app.retention.device-days={} не больше "
                            + "app.device-inactivity-days={} — удаление задело бы записи, "
                            + "которые ещё нужны логике лимита устройств",
                    deviceRetentionDays, inactivityDays);
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(deviceRetentionDays);
        int removed = deviceRepository.deleteAllLastSeenBefore(cutoff);
        // INFO всегда, даже на нуле: молчаливое удаление ПДн неотличимо от поломки, а одна
        // строка в сутки — это цена того, чтобы по логу было видно, что задача жива.
        log.info("Срок хранения: удалено {} отпечатков устройств (last_seen_at старше {}, "
                + "срок {} дн.)", removed, cutoff, deviceRetentionDays);
    }

    /**
     * Подарочные подписки: удаляем невостребованные гранты, у которых вышел срок годности.
     * Использованные не трогаем — они уже никого не активируют и остаются как аудит
     * «кому достался приз» (см. {@code PendingGrantRepository.deleteUnusedExpiredBefore}).
     * <p>
     * Отдельно от устройств и на минуту позже — чтобы в логе было видно, какая именно
     * чистка отработала, а какая упала.
     */
    @Scheduled(cron = "0 48 4 * * *")
    @Transactional
    public void cleanupExpiredGrants() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int removed = pendingGrantRepository.deleteUnusedExpiredBefore(now);
        log.info("Срок хранения: удалено {} просроченных подарочных грантов (expires_at раньше {})",
                removed, now);
    }
}
