package com.orchestra.subscriptions.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Обслуживание зеркала подписок: перенос существующих юзеров при старте и ночная сверка.
 * <p>
 * Вынесено из {@link SubscriptionRegistryService} отдельным бином, чтобы сам сервис оставался
 * без планировщиков и без зависимости на уведомления — его зовут из платёжного пути, и лишние
 * связи там означают лишние шансы на циклы в Spring.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionRegistryMaintenance {

    private final SubscriptionRegistryService registry;
    private final NotificationService notificationService;
    private final com.orchestra.subscriptions.config.AppConfig appConfig;

    @Value("${app.subscriptions.dual-write-enabled:true}")
    private boolean dualWriteEnabled;

    /**
     * Разовый перенос при старте. Идемпотентен, поэтому безопасен на каждом деплое: заводит
     * зеркальные строки только тем, у кого их ещё нет.
     * <p>
     * {@code ApplicationReadyEvent}, а не {@code @PostConstruct}: к этому моменту Liquibase
     * уже отработал и таблицы точно существуют. Ошибка не должна мешать старту — сервис
     * обязан подняться и обслуживать людей даже если зеркало не наполнилось.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!dualWriteEnabled) {
            log.info("Зеркало подписок выключено (app.subscriptions.dual-write-enabled=false)");
            return;
        }
        try {
            int created = registry.backfillMissing();
            if (created > 0) {
                log.info("Зеркало подписок: перенесено {} юзеров", created);
            }
            // Здесь же — адресация старых записей журнала. Она идемпотентна и после первого
            // прохода находит только то, что адресовать нельзя в принципе, поэтому повтор на
            // каждом деплое безвреден и не требует отдельной кнопки.
            registry.linkOrphanLedgerRows();
        } catch (Exception e) {
            log.error("Бэкфилл зеркала подписок не выполнен — сервис работает как обычно, "
                    + "источник правды прежний (customers)", e);
        }
    }

    /**
     * Ночная сверка зеркала с {@code customers}. Пока источник правды — старая таблица,
     * поэтому расхождение означает пропущенную запись в зеркало, а не проблему у юзера;
     * но именно от этих данных на следующем этапе начнёт зависеть выдача доступа, поэтому
     * узнать о расхождении надо ДО того, как оно станет важным.
     */
    @Scheduled(cron = "0 40 4 * * *")
    public void nightlyDivergenceCheck() {
        if (!dualWriteEnabled) {
            return;
        }
        try {
            // Сначала чиним: зеркало — производные данные, и расхождение почти всегда
            // означает пропущенный вызов зеркалирования, а не порчу. Алерт остаётся только
            // на то, что починить не удалось, — иначе один забытый путь будил бы владельца
            // каждую ночь месяцами, и алерт перестали бы читать.
            List<Long> stillDivergent = registry.reconcileDivergent();
            if (stillDivergent.isEmpty()) {
                log.info("Сверка подписок: расхождений нет ({} зеркальных строк)",
                        registry.mirroredCount());
                return;
            }
            log.warn("Сверка подписок: не удалось починить {} — {}",
                    stillDivergent.size(), stillDivergent);
            alertOwner(stillDivergent);
        } catch (Exception e) {
            log.error("Ночная сверка подписок упала", e);
        }
    }

    private void alertOwner(List<Long> divergent) {
        Long ownerId = appConfig.getTelegram() != null ? appConfig.getTelegram().getOwnerId() : null;
        if (ownerId == null) {
            return;
        }
        try {
            String sample = divergent.stream().limit(10).map(String::valueOf)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            notificationService.sendPlainText(ownerId,
                    "⚠️ Зеркало подписок: " + divergent.size()
                            + " юзер(ов) НЕ удалось привести в соответствие.\n\nid: " + sample
                            + "\n\nПерезапись не помогла — это настоящая проблема, а не "
                            + "пропущенная запись.\n\n⚠️ Разбирать СРАЗУ: из таблицы подписок читаются "
                            + "кандидаты всех планировщиков и выдача подписки. У этих людей "
                            + "сейчас неверный срок или статус — могут не получить "
                            + "предупреждение об истечении, не отключиться по сроку или не "
                            + "пройти автопродление.\n\nДетали: /subs <id>, повтор: /subcheck");
        } catch (Exception e) {
            log.warn("Не смог отправить алерт о расхождении подписок", e);
        }
    }
}
