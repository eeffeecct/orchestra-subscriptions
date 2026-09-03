package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.NotificationService;
import com.orchestra.subscriptions.service.AccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Периодически проверяет истёкшие подписки (триал и платные) — отключает аккаунты
 * в AccessProvider, переводит их в DISABLED и уведомляет пользователей.
 * <p>
 * После отказа от Tribute-канала это единственное место, где платные подписки
 * реально выключаются по истечении срока.
 * <p>
 * ⚠ <b>Каждый флаг «уже уведомлён» обязан быть отзеркалён в {@code subscriptions}.</b>
 * Кандидатов планировщик читает оттуда (с этапа 3б, в проде с 12.08.2026), а сохраняет их
 * сюда, в {@code customers}. Пропущенное зеркалирование не ломает ничего видимого в коде —
 * оно превращает разовое письмо в рассылку каждые пять минут до самого истечения подписки.
 * Ровно это и случилось с {@code notified_24h}: человек получал одно и то же предупреждение
 * ~200 раз. Добавляешь сюда новый флаг-отметку — добавляй и {@code mirrorAfterCommit}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryNotifier {

    /** Нижняя граница winback-окна — за сколько дней до now искать expires_at. */
    private static final int WINBACK_WINDOW_MIN_DAYS = 2;
    /** Верхняя граница winback-окна. */
    private static final int WINBACK_WINDOW_MAX_DAYS = 4;
    /** За сколько дней до now искать refund'ы при фильтре "юзер сам ушёл". */
    private static final int WINBACK_REFUND_EXCLUSION_DAYS = 30;

    /**
     * Статусы платежа, которые означают «деньги хоть раз приходили». REFUNDED тоже
     * считаем «платил» — юзер прошёл через успешную оплату и потом сам её вернул,
     * это не триал.
     */
    private static final Set<Payment.Status> PAID_STATUSES =
            Set.of(Payment.Status.SUCCEEDED, Payment.Status.REFUNDED);

    private final CustomerRepository userRepository;
    /** Кого считать кандидатом — читается через общий фасад: источник переключается флагом. */
    private final com.orchestra.subscriptions.service.SubscriptionLookupService subscriptionLookup;
    private final PaymentRepository paymentRepository;
    private final AccessService accessService;
    private final NotificationService notificationService;
    /**
     * Зеркало подписки. Нужно ИМЕННО здесь: флаги «уведомлён» пишутся на строку человека
     * ({@code customers}), а кандидатов планировщик читает из {@code subscriptions} — и без
     * зеркалирования отметка не доезжает до той строки, по которой идёт выборка.
     */
    private final com.orchestra.subscriptions.service.SubscriptionRegistryService subscriptionRegistry;

    /**
     * Feature-флаг для фонового scheduling. В prod = true. В тестах — false, чтобы
     * @Scheduled не фирился одновременно с ручными вызовами из @Test (race condition).
     */
    @Value("${app.scheduler.expiry-enabled:true}")
    private boolean schedulerEnabled;

    /** @Scheduled-обёртка с флагом. Сама работа — в {@link #processExpiredSubscriptions}. */
    @Scheduled(fixedDelay = 5 * 60 * 1000) // каждые 5 минут
    public void scheduledTick() {
        if (!schedulerEnabled) {
            return;
        }
        processExpiredSubscriptions();
    }

    public void processExpiredSubscriptions() {
        LocalDateTime now = LocalDateTime.now();

        // Уведомления за 24 часа — теперь и для триала (раньше триал был 24h, поэтому
        // уведомление было бессмысленно; сейчас триал 7d, и за день до окончания
        // юзеру самое то напомнить «время купить»).
        List<Customer> expiring24h = subscriptionLookup
                .expiringWithout24hNotice(now, now.plusHours(24));
        for (Customer user : expiring24h) {
            notificationService.sendExpiryWarning24h(user.getTelegramId());
            user.setNotified24h(true);
            userRepository.save(user);
            // Без этой строки человек получает одно и то же предупреждение КАЖДЫЕ 5 МИНУТ до
            // самого истечения: выборка кандидатов идёт по subscriptions.notified_24h, а
            // сохранение выше правит только customers. Поймано на проде 13.08.2026.
            subscriptionRegistry.mirrorAfterCommit(user.getTelegramId());
            log.info("Sent 24h expiry warning: telegramId={} isTrial={}",
                    user.getTelegramId(), isTrialUser(user));
        }

        // Уведомления за 1 час — также включаем триал по той же причине.
        List<Customer> expiring1h = subscriptionLookup
                .expiringWithout1hNotice(now, now.plusHours(1));
        for (Customer user : expiring1h) {
            notificationService.sendExpiryWarning1h(user.getTelegramId());
            user.setNotified1h(true);
            userRepository.save(user);
            subscriptionRegistry.mirrorAfterCommit(user.getTelegramId());
            log.info("Sent 1h expiry warning: telegramId={} isTrial={}",
                    user.getTelegramId(), isTrialUser(user));
        }

        // Отключение истёкших
        List<Customer> expired = subscriptionLookup.expired(now);

        for (Customer user : expired) {
            boolean isTrial = isTrialUser(user);

            accessService.disableAccess(user.getTelegramId());

            String text = isTrial
                    ? "Пробный период закончился.\n\n"
                            + "Чтобы продолжить пользоваться сервисом, оформите подписку: /start"
                    : "Подписка закончилась.\n\n"
                            + "Чтобы продлить, нажмите /start и выберите тариф.";
            notificationService.sendPlainText(user.getTelegramId(), text);

            log.info("Expired subscription processed: telegramId={}, isTrial={}",
                    user.getTelegramId(), isTrial);
        }

        processWinbacks(now);
    }

    /**
     * Триал = у юзера НЕТ ни одной успешной (или возвращённой) оплаты. Префикс
     * {@code provider_username} здесь намеренно не используется: при апгрейде триала в
     * платную подписку AccessProvider-аккаунт переиспользуется без переименования
     * (см. {@link com.orchestra.subscriptions.service.AccessService#activatePaidSubscription}),
     * поэтому имя {@code trial_*} остаётся у платящего юзера навсегда. До этого
     * фикса все «бывшие триалы» получали «Пробный период закончился» вместо
     * «Подписка закончилась» и пропускали 24h/1h-уведомления.
     */
    private boolean isTrialUser(Customer user) {
        return !paymentRepository.existsByTelegramIdAndStatusIn(
                user.getTelegramId(), PAID_STATUSES);
    }

    /**
     * Post-expiry winback: один раз через 2-4 дня после истечения — нейтральное напоминание.
     * <p>
     * Фильтры:
     * <ul>
     *   <li>status=DISABLED (триалы с истекшим status=ACTIVE сюда не попадают — но для них
     *       есть отдельное сообщение в processExpiredSubscriptions выше, в тот же тик они
     *       получают и disable и уведомление)</li>
     *   <li>expires_at в окне [now-4d, now-2d]</li>
     *   <li>winback_sent=false — чтобы отправить ровно один раз</li>
     *   <li>Нет refund'ов за последние 30 дней — юзеры, которые сами вернули деньги,
     *       не должны получать «вернитесь»</li>
     *   <li>Не триальные юзеры (определяются по отсутствию SUCCEEDED/REFUNDED-оплат) —
     *       триал это одноразовая проба, не подписка</li>
     * </ul>
     */
    private void processWinbacks(LocalDateTime now) {
        LocalDateTime from = now.minusDays(WINBACK_WINDOW_MAX_DAYS);
        LocalDateTime to = now.minusDays(WINBACK_WINDOW_MIN_DAYS);
        List<Customer> candidates = subscriptionLookup.winbackCandidates(from, to);
        if (candidates.isEmpty()) {
            return;
        }

        LocalDateTime refundCutoff = now.minusDays(WINBACK_REFUND_EXCLUSION_DAYS);
        for (Customer user : candidates) {
            // Триалы не возвращаем — им отдельное сообщение уже шло в момент истечения.
            if (isTrialUser(user)) {
                user.setWinbackSent(true);
                userRepository.save(user);
                subscriptionRegistry.mirrorAfterCommit(user.getTelegramId());
                continue;
            }
            boolean recentlyRefunded = paymentRepository.existsByTelegramIdAndStatusAndRefundedAtAfter(
                    user.getTelegramId(), Payment.Status.REFUNDED, refundCutoff);
            if (recentlyRefunded) {
                // Юзер осознанно ушёл через refund. Ставим флаг, чтобы не проверять повторно,
                // но ничего не отправляем. При следующей оплате флаг сбросится.
                user.setWinbackSent(true);
                userRepository.save(user);
                subscriptionRegistry.mirrorAfterCommit(user.getTelegramId());
                log.debug("Winback skipped (recent refund) for telegramId={}", user.getTelegramId());
                continue;
            }
            notificationService.sendWinback(user.getTelegramId());
            user.setWinbackSent(true);
            userRepository.save(user);
            subscriptionRegistry.mirrorAfterCommit(user.getTelegramId());
            log.info("Sent winback to telegramId={} (expired at {})",
                    user.getTelegramId(), user.getExpiresAt());
        }
    }
}
