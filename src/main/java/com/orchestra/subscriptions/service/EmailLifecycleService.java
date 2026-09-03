package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.util.EmailMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle-менеджмент email юзеров — авто-release неподтверждённых после
 * {@link #UNVERIFIED_RETENTION}.
 *
 * <h2>Зачем</h2>
 * Корневой кейс «typo-claim»: юзер A опечатался и привязал email юзера B
 * (например, перепутал букву в чужом домене). Magic-link улетел реальному B,
 * но A никогда не подтвердит — у него этого письма нет. Без cleanup'а B
 * пытается claim'нуть свой собственный адрес → получает 409 «email уже
 * занят» от {@code WebAppController.updateEmail}, и навсегда заблокирован
 * без человеческого вмешательства.
 * <p>
 * Через 30 дней без верификации мы:
 * <ul>
 *   <li>Зануляем у юзера A три поля: {@code email}, {@code email_verified_at},
 *       {@code email_consent_at}.</li>
 *   <li>Шлём DM в Telegram юзеру A с пояснением (нейтрально: возможно, он сам
 *       добавил и забыл, а возможно — typo'нулся).</li>
 *   <li>UNIQUE-constraint на email теперь не мешает B claim'нуть его адрес.</li>
 * </ul>
 *
 * <h2>Cutoff = 30 дней — обоснование</h2>
 * 30 дней — компромисс. Слишком короткое окно (3-7 дней) ловит реальных юзеров,
 * которые добавили email перед отпуском или открыли его на устройстве без
 * Telegram'а. Слишком длинное (90+ дней) — typo-блокировка ощутимо больше
 * проблема. 30 дней покрывает разумные «забыл подтвердить» сценарии и при этом
 * не делает реального владельца email'а заложником чужой опечатки на полгода.
 *
 * <h2>Backup / safety</h2>
 * <ul>
 *   <li>Идемпотентно — повторный запуск не дублирует release (зануленный email
 *       больше не попадает в выборку).</li>
 *   <li>Каждый release коммитится своей транзакцией ({@code save} в Spring Data
 *       транзакционен), а блокирующие Telegram-DM шлются ОТДЕЛЬНЫМ проходом после
 *       коммита — так DB-коннект не держится на время сетевых вызовов, и сбой
 *       одного DM не откатывает уже освобождённые записи.</li>
 *   <li>DM swallow'ит исключения внутри NotificationService — fail TG не
 *       откатывает release (DM это nice-to-have, не контракт).</li>
 *   <li>Cron-сдвиг от ровного часа (03:23) — не конкурирует с другими
 *       @Scheduled (см. {@code MagicLinkService.cleanupExpired} в xx:17).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailLifecycleService {

    /** Через сколько без верификации email считается «застрявшим» и освобождается. */
    public static final Duration UNVERIFIED_RETENTION = Duration.ofDays(30);

    private final CustomerRepository userRepository;
    private final NotificationService notificationService;

    /**
     * Запуск раз в сутки в 03:23 server-time (UTC если контейнер не настроен иначе).
     * Точное время не критично — операция идемпотентна, и 30-дневный horizon
     * не чувствителен к ±несколько часов.
     */
    @Scheduled(cron = "0 23 3 * * *")
    public void releaseStaleUnverifiedEmails() {
        LocalDateTime cutoff = LocalDateTime.now().minus(UNVERIFIED_RETENTION);
        List<Customer> candidates = userRepository.findStaleUnverifiedEmails(cutoff);
        if (candidates.isEmpty()) {
            log.debug("EmailLifecycle: no stale unverified emails (cutoff={})", cutoff);
            return;
        }

        log.info("EmailLifecycle: releasing {} stale unverified emails (consent older than {})",
                candidates.size(), cutoff);

        // Сначала освобождаем email'ы — каждый save() в своей короткой транзакции (Spring Data).
        // НЕ держим транзакцию открытой на время блокирующих Telegram-вызовов.
        List<Long> released = new ArrayList<>(candidates.size());
        for (Customer user : candidates) {
            String oldEmailMasked = EmailMasker.mask(user.getEmail());
            user.setEmail(null);
            user.setEmailVerifiedAt(null);
            user.setEmailConsentAt(null);
            userRepository.save(user);

            log.info("EmailLifecycle: released email for telegramId={} oldEmail={}",
                    user.getTelegramId(), oldEmailMasked);
            released.add(user.getTelegramId());
        }

        // DM шлём отдельным проходом, после коммита записей и вне транзакции. Best-effort —
        // NotificationService swallow'ит TelegramApiException. Сбой одного DM не откатывает
        // уже освобождённые адреса (раньше единый @Transactional делал это всё-или-ничего).
        for (Long telegramId : released) {
            notificationService.sendEmailAutoReleasedDm(telegramId);
        }
    }
}
