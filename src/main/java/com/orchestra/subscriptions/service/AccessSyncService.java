package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Приводит состояние аккаунта в панели AccessProvider в соответствие со строкой подписки.
 * <p>
 * <b>Желаемое состояние выводится ТОЛЬКО из свежего чтения строки</b>, а не из снимка,
 * переданного вызывающим. Поэтому повторная отправка безвредна, порядок доставки не важен, а
 * реконсилер и обычный путь не могут «переспорить» друг друга устаревшими данными.
 * <p>
 * Оба вызова панели — это установка абсолютного состояния (включить до такой-то даты /
 * выключить), а не инкремент, поэтому повтор сходится к одному и тому же результату.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessSyncService {

    public static final String INTENT_PARK = "PARK";
    public static final String INTENT_RESUME = "RESUME";

    private final SubscriptionRepository subscriptionRepository;
    private final AccessProvider accessProvider;
    private final AppConfig appConfig;

    /**
     * Досылает в панель состояние подписки и закрывает намерение.
     * <p>
     * Своя транзакция и проглатывание ошибок: это доставка «наружу», и её сбой не должен
     * ронять бизнес-операцию, которая уже зафиксирована в базе. Незакрытое намерение подберёт
     * реконсилер.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void push(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null || sub.getProviderUsername() == null) {
            return;
        }
        try {
            if (shouldBeLive(sub, LocalDateTime.now())) {
                accessProvider.enableUserUntil(sub.getProviderUsername(),
                        sub.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant(),
                        resolveIpLimit(sub));
            } else {
                accessProvider.disableUser(sub.getProviderUsername());
            }
            sub.setProviderIntent(null);
            sub.setProviderSyncedAt(LocalDateTime.now());
            sub.setProviderAttempts(0);
            subscriptionRepository.save(sub);
        } catch (Exception e) {
            sub.setProviderAttempts(sub.getProviderAttempts() + 1);
            subscriptionRepository.save(sub);
            log.warn("AccessProvider sync failed for subscription id={} (попытка {}) — доведёт реконсилер",
                    subscriptionId, sub.getProviderAttempts(), e);
        }
    }

    /**
     * Должен ли аккаунт быть живым в панели: только текущая подписка, только активная,
     * только пока не вышел срок. Припаркованная не живая по определению — у неё нет даты.
     */
    private boolean shouldBeLive(Subscription sub, LocalDateTime now) {
        return sub.isCurrent()
                && sub.getState() == Customer.Status.ACTIVE
                && sub.getExpiresAt() != null
                && sub.getExpiresAt().isAfter(now);
    }

    /**
     * Лимит одновременных IP: по конвенции устройства + 1. Правило перенесено дословно из
     * {@code AccessService.resolveIpLimit} — оно там появилось потому, что пути без явной
     * ступени схлопывали семейный лимит до глобального.
     */
    private Integer resolveIpLimit(Subscription sub) {
        if (sub.getDeviceTierId() != null) {
            AppConfig.DeviceTier tier =
                    appConfig.findDeviceTierOptional(sub.getDeviceTierId()).orElse(null);
            if (tier != null) {
                return tier.effectiveProviderDeviceLimit();
            }
        }
        return sub.getDeviceLimit() != null && sub.getDeviceLimit() > 0
                ? sub.getDeviceLimit() + 1
                : null;
    }
}
