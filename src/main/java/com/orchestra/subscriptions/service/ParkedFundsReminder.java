package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Напоминает про оплаченную, но так и не включённую подписку.
 * <p>
 * <b>Зачем.</b> Человек купил другую ступень, получил вторую подписку и забыл её включить —
 * деньги стоят, доступ у него прежний. Снаружи это выглядит как «заплатил и ничего не
 * произошло»: самый обидный исход, который есть у всей затеи с несколькими подписками, и
 * единственная защита от него — вовремя сказать вслух.
 * <p>
 * <b>Факт напоминания хранится на строке подписки.</b> Первая версия обходилась часовым
 * окном «возрасту оплаты ровно 24 часа», но окно ловится один раз только при строго часовом
 * тике, а {@code @Scheduled(fixedDelay)} стартует заново при каждом подъёме контекста — то
 * есть каждый деплой добавлял внеочередной прогон в тот же час и рассылал письма повторно.
 * Деплоев у нас бывает несколько за час, так что «редкий дубль» превращался в рассылку.
 * <p>
 * <b>Подписку, которой человек уже пользовался, не трогаем.</b> Если он её включал и потом
 * осознанно выключил ({@code last_activated_at} заполнен), напоминать не о чем: он знает, что
 * она есть, и заморозил её сам. Напоминание нужно ровно тому, кто оплатил и не понял, что
 * подписку надо включить.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParkedFundsReminder {

    /** Что считаем «оплатой в эту подписку». */
    private static final Set<SubscriptionLedger.Kind> PAYMENT_KINDS =
            Set.of(SubscriptionLedger.Kind.PURCHASE, SubscriptionLedger.Kind.AUTORENEW);

    /** Через сколько после оплаты напоминаем. */
    private static final int[] REMINDER_HOURS = {24, 72};

    /** Сколько дней молчания превращают забытые деньги в повод разобраться владельцу. */
    private static final int OWNER_ALERT_DAYS = 7;

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionLedgerRepository ledgerRepository;
    private final NotificationService notificationService;
    private final AppConfig appConfig;

    /**
     * Выключатель на случай, если напоминания окажутся навязчивыми: их текст уходит живым
     * людям, и возможность выключить одной переменной здесь важнее лишней настройки.
     */
    @Value("${app.scheduler.parked-funds-reminder-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelay = 60L * 60L * 1000L)
    public void remindAboutParkedFunds() {
        if (!enabled) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Subscription> parked =
                    subscriptionRepository.findByCurrentFalseAndBalanceSecondsGreaterThan(0L);
            if (parked.isEmpty()) {
                return;
            }
            int reminded = 0;
            int stale = 0;
            for (Subscription sub : parked) {
                if (sub.getLastActivatedAt() != null) {
                    // Человек ею пользовался и выключил сам — он про неё знает.
                    continue;
                }
                LocalDateTime paidAt = lastPaymentAt(sub);
                if (paidAt == null) {
                    // Дни пришли не оплатой (бонус, перенос) — напоминать не о чем.
                    continue;
                }
                long hours = Duration.between(paidAt, now).toHours();
                if (dueStage(sub, paidAt, hours) > 0) {
                    notificationService.sendParkedSubscriptionReminder(sub.getTelegramId(), sub);
                    sub.setParkedRemindedAt(now);
                    subscriptionRepository.save(sub);
                    reminded++;
                }
                if (hours >= OWNER_ALERT_DAYS * 24L) {
                    stale++;
                }
            }
            if (reminded > 0 || stale > 0) {
                log.info("Напоминания о замороженных деньгах: отправлено {}, «висит дольше {} дн.» — {}",
                        reminded, OWNER_ALERT_DAYS, stale);
            }
            reportToOwnerOncePerDay(now, stale);
        } catch (Exception e) {
            // Напоминание — наблюдение, а не доступ: его сбой не должен ронять планировщики.
            log.error("Прогон напоминаний о замороженных деньгах не удался", e);
        }
    }

    /**
     * Какое по счёту напоминание пора слать: 1 (через сутки), 2 (через трое) или 0.
     * <p>
     * Границы — «не раньше чем», а не «ровно в этот час»: пропущенный из-за простоя тик не
     * должен отменять напоминание навсегда. От повторов защищает отметка на строке, а не
     * узость окна.
     */
    private static int dueStage(Subscription sub, LocalDateTime paidAt, long hours) {
        LocalDateTime reminded = sub.getParkedRemindedAt();
        // Отметка старше оплаты — она про прошлую покупку в эту же подписку.
        boolean firstPending = reminded == null || reminded.isBefore(paidAt);
        if (hours >= REMINDER_HOURS[0] && firstPending) {
            return 1;
        }
        if (hours >= REMINDER_HOURS[1] && !firstPending
                && reminded.isBefore(paidAt.plusHours(REMINDER_HOURS[1]))) {
            return 2;
        }
        return 0;
    }

    private LocalDateTime lastPaymentAt(Subscription sub) {
        if (sub.getId() == null) {
            return null;
        }
        return ledgerRepository
                .findTopBySubscriptionIdAndKindInOrderByOccurredAtDesc(sub.getId(), PAYMENT_KINDS)
                .map(SubscriptionLedger::getOccurredAt)
                .orElse(null);
    }

    /**
     * Владельцу — счётчик подписок с забытыми деньгами, один раз в сутки. Чаще нельзя: алерт,
     * приходящий ежечасно, перестают читать ровно к тому моменту, когда он что-то значит.
     */
    private void reportToOwnerOncePerDay(LocalDateTime now, int stale) {
        if (stale <= 0 || now.getHour() != 12) {
            return;
        }
        Long ownerId = appConfig.getTelegram() != null ? appConfig.getTelegram().getOwnerId() : null;
        if (ownerId == null) {
            return;
        }
        notificationService.sendPlainText(ownerId, "❄️ Замороженные подписки с деньгами, "
                + "не включённые дольше " + OWNER_ALERT_DAYS + " дн.: " + stale
                + ". Люди заплатили и не пользуются — возможно, не поняли, что подписку надо включить.");
    }
}
