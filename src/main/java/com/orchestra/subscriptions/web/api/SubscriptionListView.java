package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.service.SubscriptionTime;
import com.orchestra.subscriptions.util.SubscriptionFormat;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Список подписок человека для веб-клиентов — одно место на Mini App и web-ЛК.
 * <p>
 * Ровно та же причина, по которой у бота один {@link SubscriptionFormat} на админский и
 * юзерский экраны: разъехавшись, два интерфейса начнут описывать одно состояние разными
 * словами, и разбор жалобы «почему в приложении написано так, а на сайте эдак» станет
 * невозможным. Особенно это касается слова «заморожена» — человек читает его как «мои дни
 * пропали», и оба экрана обязаны отвечать одинаково.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionListView {

    private final SubscriptionRepository subscriptionRepository;
    private final AppConfig appConfig;
    /** Какую из подписок продлят сами — второй вопрос человека после «какая работает». */
    private final com.orchestra.subscriptions.service.AutoRenewInfoService autoRenewInfo;

    /**
     * Все подписки человека, от первой к последней.
     * <p>
     * Отдаются ВСЕГДА, независимо от флага {@code app.multi-subscription.enabled}: флаг
     * запрещает заводить новую подписку, а не смотреть на уже существующие — у человека там
     * могут лежать замороженные деньги. Прятать список — значит прятать их.
     */
    public List<Map<String, Object>> forUser(com.orchestra.subscriptions.model.Customer user) {
        long telegramId = user.getTelegramId();
        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId);
        LocalDateTime now = LocalDateTime.now();
        // Автопродление всегда продлевает РАБОТАЮЩУЮ подписку (createIntoCurrent). Считаем
        // один раз на список: снимок один и тот же для всех строк.
        boolean autoRenews = autoRenewInfo.forUser(user).willCharge();
        List<Map<String, Object>> out = new ArrayList<>(subs.size());
        for (Subscription s : subs) {
            boolean frozen = !s.isCurrent();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("seq", s.getSeq());
            item.put("tierLabel", tierLabel(s));
            // Сырой id ступени нужен фронту, чтобы не предлагать «новую подписку» той же
            // ступени: сервер такую покупку отклонит (две неотличимые подписки бесполезны),
            // и без этого поля человек упирался бы в отказ уже после выбора способа оплаты.
            item.put("tierId", s.getDeviceTierId());
            item.put("devices", s.getDeviceLimit() == null ? 0 : s.getDeviceLimit());
            item.put("daysLeft", daysLeft(s, now));
            item.put("frozen", frozen);
            item.put("current", s.isCurrent());
            // live отделяет «работает» от «текущая, но срок вышел»: статуса «истекла» в
            // системе нет, и без этого флага фронт написал бы «работает» поверх подписки,
            // которая не пускает уже неделю.
            item.put("live", SubscriptionFormat.live(s, now));
            // «Эту продлят автоматически». Замороженные не продлеваются никогда, поэтому
            // пометка возможна только у текущей: на любой другой она обещала бы списание,
            // которого не будет.
            item.put("renewing", autoRenews && s.isCurrent());
            item.put("expiresAtEpoch", s.getExpiresAt() == null ? 0
                    : s.getExpiresAt().toEpochSecond(ZoneOffset.UTC));
            // Ссылка замороженной подписки мертва до включения. Отдать её — значит отправить
            // человека вставлять в приложение заведомо нерабочий конфиг и потом разбираться,
            // почему «доступ не работает». Здесь и в боте правило одно (SubscriptionFormat).
            String link = frozen ? null : accessLink(s.getSubscriptionLink());
            item.put("link", link == null || link.isEmpty() ? null : link);
            out.add(item);
        }
        return out;
    }

    /** Сколько дней осталось: у работающей — до даты, у замороженной — сколько сохранено. */
    private static long daysLeft(Subscription s, LocalDateTime now) {
        if (s.isCurrent()) {
            return SubscriptionTime.displayDays(
                    SubscriptionTime.remainingSeconds(s.getExpiresAt(), now));
        }
        return SubscriptionTime.displayDays(
                s.getBalanceSeconds() == null ? 0L : s.getBalanceSeconds());
    }

    /** Человекочитаемое имя ступени; ступень удалили из конфига — покажем её id. */
    private String tierLabel(Subscription s) {
        if (s.getDeviceTierId() == null) {
            return null;
        }
        return appConfig.findDeviceTierOptional(s.getDeviceTierId())
                .map(AppConfig.DeviceTier::getName)
                .orElse(s.getDeviceTierId());
    }

    /**
     * клиентское приложение-ссылка со встроенным split-routing'ом. Сырую AccessProvider-ссылку человеку не отдаём
     * нигде (решение владельца 2026-07-15).
     */
    private String accessLink(String providerLink) {
        return SubscriptionLinks.accessLink(providerLink, appConfig.getTelegram().getPublicBaseUrl());
    }
}
