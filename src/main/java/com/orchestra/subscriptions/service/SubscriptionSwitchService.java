package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Переключение между подписками одного человека: текущая замораживается, выбранная
 * оттаивает. Ядро модели «несколько подписок, работает одна».
 * <p>
 * <b>Дни не сгорают.</b> При парковке остаток времени превращается в замороженный
 * {@code balance_seconds}, а дата окончания стирается — с этого момента подписку не видит ни
 * один запрос по датам, поэтому её дни физически не могут тикать. При разморозке остаток
 * снова превращается в дату от текущего момента. Округление при обеих конвертациях — в пользу
 * юзера (см. {@link SubscriptionTime}).
 * <p>
 * <b>Порядок записи в панель: сначала выключить старую, потом включить новую.</b> Две
 * одновременно живые подписки означали бы двойной лимит устройств и двойной лимит соединений
 * — то есть выдачу того, за что не платили. Худший исход обратного порядка — до минуты без
 * доступа на осознанном действии юзера, и его лечит реконсилер.
 * <p>
 * Полный разбор — {@code docs/multi-subscription-plan.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionSwitchService {

    /** Что считаем переключением при проверке суточного лимита. */
    private static final java.util.Set<SubscriptionLedger.Kind> SWITCH_KINDS =
            java.util.Set.of(SubscriptionLedger.Kind.PARK, SubscriptionLedger.Kind.RESUME);

    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository userRepository;
    private final SubscriptionLedgerService ledger;
    private final com.orchestra.subscriptions.repository.SubscriptionLedgerRepository ledgerRepository;
    private final SubscriptionRegistryService registry;
    private final AccessSyncService accessSync;
    private final MultiSubscriptionPolicy policy;
    private final EntityManager entityManager;

    /** Чем закончилось переключение — вызывающему (боту/админке) нужно это показать. */
    public enum Outcome {
        /** Переключились. */
        SWITCHED,
        /** Эта подписка и так текущая — двойное нажатие или устаревшая кнопка. */
        ALREADY_CURRENT,
        /** Подписки с таким id у этого человека нет. */
        NOT_FOUND,
        /** Аварийный тормоз владельца: переключения временно выключены. */
        PAUSED,
        /** Человек исчерпал суточный лимит переключений. */
        RATE_LIMITED,
        /**
         * На выбранной подписке не осталось оплаченного времени.
         * <p>
         * Отказ живёт ЗДЕСЬ, а не на экране: кнопка приходит из истории чата, где висит вечно,
         * и к моменту нажатия остаток мог обнулиться (подписка доработала своё и была
         * заморожена с нулём, или по её платежу прошёл возврат). Переключение на пустую
         * подписку заморозило бы работающую и оставило человека вообще без доступа, а экран
         * отрапортовал бы «включена, осталось 0 дн.». Проверка на экране показа это не ловит —
         * там она только про то, какую кнопку рисовать.
         */
        EMPTY
    }

    public record Result(Outcome outcome, Long parkedSubscriptionId, Long activatedSubscriptionId,
                         long activatedRemainingSeconds) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, null, 0L);
        }
    }

    /**
     * Переключение ПО ПРОСЬБЕ ЧЕЛОВЕКА — с проверкой аварийного тормоза и суточного лимита.
     * <p>
     * Отдельный метод, потому что гейты обязаны быть свойством операции, а не экрана. Пока
     * они жили в обработчике бота, второй канал (Mini App) обходил их одним своим появлением:
     * лимит, который снимается сменой окна, не лимит. Ровно тот же урок, что с включением
     * пустой подписки на этапе 5.4.
     * <p>
     * И ровно поэтому системные пути ({@link SubscriptionMintService} после оплаты, админская
     * {@code /switchsub}) зовут {@link #switchTo} напрямую: человек, исчерпавший лимит
     * переключений, обязан всё равно получить только что оплаченную подписку, иначе счётчик
     * кликов превращается в «заплатил и остался без доступа».
     */
    @Transactional
    public Result switchByUser(Long telegramId, Long targetId) {
        if (!policy.switchingEnabled()) {
            return Result.of(Outcome.PAUSED);
        }
        if (switchesToday(telegramId) >= policy.maxSwitchesPerDay()) {
            return Result.of(Outcome.RATE_LIMITED);
        }
        return switchTo(telegramId, targetId);
    }

    /** Сколько переключений человек уже сделал за сутки — по журналу, не по счётчику в памяти. */
    private long switchesToday(Long telegramId) {
        try {
            // PARK и RESUME пишутся парой на каждое переключение — делим, чтобы лимит
            // означал переключения, а не записи журнала.
            return ledgerRepository.countByTelegramIdAndKindInAndOccurredAtAfter(
                    telegramId, SWITCH_KINDS, LocalDateTime.now().minusDays(1)) / 2;
        } catch (Exception e) {
            // Журнал — наблюдение, а не право доступа: его сбой не должен запирать человека.
            log.warn("Не смог посчитать переключения за сутки для telegramId={}", telegramId, e);
            return 0;
        }
    }

    /**
     * Переключает человека на подписку {@code targetId}. Гейтов здесь нет — это системный
     * путь; всё, что инициировал человек, идёт через {@link #switchByUser}.
     * <p>
     * Своя транзакция: нарушение уникального индекса «одна текущая подписка» — это
     * {@code DataIntegrityViolationException}, который отравляет транзакцию Postgres и метит
     * внешнюю на откат. Вызывающий обязан ловить снаружи этого метода, перечитывать состояние
     * и перерисовывать экран, а не считать исключение частью нормального потока.
     */
    @Transactional
    public Result switchTo(Long telegramId, Long targetId) {
        // Часы читаем РОВНО ОДИН РАЗ и передаём дальше: если брать now() отдельно для парковки
        // и для разморозки, между ними пройдёт время, и оно пропадёт у юзера безвозвратно.
        LocalDateTime now = LocalDateTime.now();

        // Порядок замков: человек → подписки. Единый во всём проекте.
        userRepository.findByTelegramIdForUpdate(telegramId);

        Subscription target = subscriptionRepository.findOwnedForUpdate(targetId, telegramId).orElse(null);
        if (target == null) {
            return Result.of(Outcome.NOT_FOUND);
        }
        if (target.isCurrent()) {
            return Result.of(Outcome.ALREADY_CURRENT);
        }
        if (target.getBalanceSeconds() == null || target.getBalanceSeconds() <= 0) {
            // Пустая цель: включить её значит отключить доступ — см. джавадок Outcome.EMPTY.
            return Result.of(Outcome.EMPTY);
        }

        Subscription parked = subscriptionRepository.findCurrentForUpdate(telegramId).orElse(null);
        if (parked != null) {
            park(parked, now);
            // ЗАГРУЖЕННЫЙ flush, не убирать. «Одна текущая подписка» — ЧАСТИЧНЫЙ уникальный
            // индекс, а такие в Postgres нельзя объявить откладываемыми: уникальность
            // проверяется после каждого запроса. Hibernate не гарантирует, что UPDATE старой
            // подписки уйдёт раньше UPDATE новой, и при обратном порядке индекс сработал бы
            // на совершенно законном переключении.
            entityManager.flush();
        }

        long balance = resume(target, now);

        // customers продолжает зеркалить ТЕКУЩУЮ подписку — на неё смотрят места, ещё не
        // переехавшие на новый источник.
        registry.mirrorCurrentSubscription(telegramId, target);

        log.info("Switched telegramId={} to subscription id={} (seq={}), parked id={}",
                telegramId, target.getId(), target.getSeq(),
                parked != null ? parked.getId() : null);
        return new Result(Outcome.SWITCHED,
                parked != null ? parked.getId() : null, target.getId(), balance);
    }

    /** Замораживает подписку: остаток в секундах, даты нет, в панель — «выключить». */
    private void park(Subscription sub, LocalDateTime now) {
        long remaining = SubscriptionTime.ceilSeconds(
                sub.getExpiresAt() == null ? Duration.ZERO : Duration.between(now, sub.getExpiresAt()));
        sub.setCurrent(false);
        sub.setExpiresAt(null);
        sub.setBalanceSeconds(remaining);
        sub.setParkedAt(now);
        // Флаги уведомлений сбрасываем: когда подписка вернётся в строй, предупреждать о её
        // истечении надо заново, а не «уже отправляли полгода назад».
        sub.setNotified24h(false);
        sub.setNotified1h(false);
        sub.setWinbackSent(false);
        sub.setProviderIntent(AccessSyncService.INTENT_PARK);
        sub.setProviderIntentAt(now);
        sub.setProviderAttempts(0);
        subscriptionRepository.save(sub);
        // Парковка НЕ меняет количество оплаченного времени — только его форму хранения,
        // поэтому дельта нулевая. Отдельный вид события (а не ADJUST): разбор денежного
        // тикета «куда делись дни» начинается с поиска заморозок, и они обязаны отличаться
        // от ручных правок.
        ledger.record(sub.getTelegramId(), sub.getId(), SubscriptionLedger.Kind.PARK,
                0L, remaining, null, "подписка #" + sub.getSeq() + " заморожена");
    }

    /** Размораживает подписку: остаток снова становится датой от текущего момента. */
    private long resume(Subscription sub, LocalDateTime now) {
        long balance = sub.getBalanceSeconds() == null ? 0L : sub.getBalanceSeconds();
        sub.setCurrent(true);
        sub.setBalanceSeconds(null);
        sub.setExpiresAt(balance > 0 ? SubscriptionTime.expiryFrom(now, balance) : null);
        sub.setLastActivatedAt(now);
        // Если остатка меньше, чем окно предупреждения, — считаем предупреждение уже
        // отправленным: человек только что осознанно переключился и точный остаток видит в
        // подтверждении. Иначе он мгновенно получил бы «истекает через час».
        sub.setNotified24h(balance > 0 && balance <= 24 * 3600);
        sub.setNotified1h(balance > 0 && balance <= 3600);
        sub.setProviderIntent(AccessSyncService.INTENT_RESUME);
        sub.setProviderIntentAt(now);
        sub.setProviderAttempts(0);
        subscriptionRepository.save(sub);
        ledger.record(sub.getTelegramId(), sub.getId(), SubscriptionLedger.Kind.RESUME,
                0L, balance, null, "подписка #" + sub.getSeq() + " активирована");
        return balance;
    }

    /**
     * Досылает состояние в панель после коммита. Отдельным шагом и best-effort: панель может
     * быть недоступна, но переключение уже зафиксировано, а незакрытое намерение доведёт
     * реконсилер.
     */
    public void pushToPanelAfterCommit(Result result) {
        if (result.outcome() != Outcome.SWITCHED) {
            return;
        }
        // Сначала выключить старую, потом включить новую — см. джавадок класса.
        if (result.parkedSubscriptionId() != null) {
            accessSync.push(result.parkedSubscriptionId());
        }
        accessSync.push(result.activatedSubscriptionId());
    }
}
