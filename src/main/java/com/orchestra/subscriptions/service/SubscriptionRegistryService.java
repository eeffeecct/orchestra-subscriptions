package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Ведёт таблицу {@code subscriptions} как ЗЕРКАЛО подписочного состояния {@code customers}.
 * <p>
 * <b>Где мы на пути к нескольким подпискам.</b> Это второй этап: таблица наполняется и
 * поддерживается в актуальном состоянии, но источником правды остаётся {@code customers} —
 * читатели (напоминания, автопродление, выдача подписки, экраны) переедут следующим этапом.
 * Такой порядок даёт возможность неделю смотреть на реальные данные и убедиться, что зеркало
 * сходится, ДО того как от него начнёт зависеть выдача доступа. Полный план —
 * {@code docs/multi-subscription-plan.md}.
 * <p>
 * <b>Ничего не ломает по построению.</b> Любая ошибка здесь проглатывается: зеркало, которое
 * уронило бы активацию оплаты, было бы хуже отсутствия зеркала. Плюс своя транзакция
 * ({@code REQUIRES_NEW}) — сбой записи в зеркало не помечает платёжную транзакцию
 * rollback-only.
 * <p>
 * Расхождения ловит ночная сверка и {@code /subcheck}: пока правда в старой таблице, любое
 * расхождение означает пропущенную запись в зеркало, а не проблему у юзера.
 */
@Slf4j
@Service
public class SubscriptionRegistryService {

    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final UserDeviceRepository deviceRepository;
    private final SubscriptionLedgerRepository ledgerRepository;
    /**
     * Своя транзакция для записи зеркала. Нужен именно шаблон, а не {@code @Transactional}:
     * запись идёт из afterCommit-хука, где вызов собственного метода не прошёл бы через
     * Spring-прокси и остался бы вообще без транзакции.
     */
    private final TransactionTemplate mirrorTxTemplate;

    public SubscriptionRegistryService(SubscriptionRepository subscriptionRepository,
                                       CustomerRepository userRepository,
                                       PaymentRepository paymentRepository,
                                       UserDeviceRepository deviceRepository,
                                       SubscriptionLedgerRepository ledgerRepository,
                                       PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.deviceRepository = deviceRepository;
        this.ledgerRepository = ledgerRepository;
        this.mirrorTxTemplate = new TransactionTemplate(transactionManager);
        this.mirrorTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Выключатель зеркалирования. {@code false} — таблица перестаёт обновляться и остаётся
     * инертной; старые колонки при этом никогда не были устаревшими, поэтому откат
     * безопасен в любой момент.
     */
    @Value("${app.subscriptions.dual-write-enabled:true}")
    private boolean dualWriteEnabled;

    /**
     * Обновить зеркало ПОСЛЕ коммита вызывающей транзакции.
     * <p>
     * Именно после, а не внутри — по двум причинам. Во-первых, зеркалу нужно
     * ЗАФИКСИРОВАННОЕ состояние: своя транзакция не видит чужих незакоммиченных изменений и
     * записала бы старые значения, то есть зеркало расходилось бы ровно в тот момент, ради
     * которого пишется. Во-вторых, откатившаяся оплата не должна оставлять в зеркале следов
     * того, чего не произошло. Тот же приём уже используется для реферальных бонусов.
     */
    public void mirrorAfterCommit(Long telegramId) {
        if (!dualWriteEnabled || telegramId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Вызов вне транзакции (тесты, ручной прогон) — зеркалим сразу.
            mirrorNow(telegramId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mirrorNow(telegramId);
            }
        });
    }

    /** Собственно запись зеркала — в своей транзакции, с проглатыванием ошибок. */
    private void mirrorNow(Long telegramId) {
        try {
            mirrorTxTemplate.executeWithoutResult(status ->
                    userRepository.findByTelegramId(telegramId).ifPresent(this::mirrorInternal));
        } catch (Exception e) {
            log.warn("Зеркало подписки не обновлено для telegramId={} — расхождение поймает "
                    + "ночная сверка (/subcheck)", telegramId, e);
        }
    }

    /**
     * ОБРАТНОЕ зеркалирование: из подписки в {@code customers}. Нужно после переключения —
     * текущей стала другая подписка, и старая таблица обязана отражать именно её, потому что
     * на неё ещё смотрят места, не переехавшие на новый источник.
     * <p>
     * Идёт в транзакции вызывающего (переключения), а не после коммита: здесь зеркало —
     * часть той же смены состояния, и разъезд между ними даже на секунду означал бы, что
     * часть сервиса показывает одну подписку, а часть другую.
     * <p>
     * <b>Настройки автопродления едут в ОБРАТНУЮ сторону — из человека в подписку.</b> Они
     * свойство ЧЕЛОВЕКА (карта, выбранный тариф) и живут в {@code customers}, а кандидатов на
     * списание планировщик выбирает по строке ПОДПИСКИ
     * ({@code SubscriptionRepository.findAutorenewCandidateTelegramIds}). Если не перенести,
     * подписка, ставшая текущей, остаётся с {@code autorenew_enabled = false} — экран
     * автопродления (он читает человека) говорит «включено», а списания не будет никогда:
     * человек досидит до нуля и потеряет доступ, считая, что продление оплачено.
     * ⛔ Копировать в другую сторону (из подписки в человека) нельзя: у только что
     * размороженной подписки эти поля пустые, и переключение молча выключило бы человеку
     * автопродление вместе с привязкой тарифа.
     */
    public void mirrorCurrentSubscription(Long telegramId, Subscription current) {
        if (!dualWriteEnabled || telegramId == null || current == null) {
            return;
        }
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            user.setProviderUsername(current.getProviderUsername());
            user.setSubscriptionLink(current.getSubscriptionLink());
            user.setStatus(current.getState());
            user.setExpiresAt(current.getExpiresAt());
            user.setDeviceLimit(current.getDeviceLimit());
            user.setDeviceTierId(current.getDeviceTierId());
            user.setNotified24h(current.isNotified24h());
            user.setNotified1h(current.isNotified1h());
            user.setWinbackSent(current.isWinbackSent());
            userRepository.save(user);

            // См. джавадок: эти четыре — из человека в подписку, иначе автопродление
            // перестаёт видеть кандидата после первого же переключения.
            current.setAutorenewEnabled(user.isAutorenewEnabled());
            current.setAutorenewPlanId(user.getAutorenewPlanId());
            current.setAutorenewDeviceTierId(user.getAutorenewDeviceTierId());
            current.setAutorenewLastFailureAt(user.getAutorenewLastFailureAt());
            subscriptionRepository.save(current);
        });
    }

    private void mirrorInternal(Customer user) {
        Subscription sub = subscriptionRepository
                .findByTelegramIdAndCurrentTrue(user.getTelegramId())
                .orElseGet(() -> newCurrentSubscription(user));
        applyUserState(sub, user);
        subscriptionRepository.save(sub);
    }

    /** Пустая строка под текущую подписку человека — номер берём из аллокатора, не из count(*). */
    private Subscription newCurrentSubscription(Customer user) {
        return Subscription.builder()
                .telegramId(user.getTelegramId())
                .seq(allocateSeq(user))
                .current(true)
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt() : LocalDateTime.now())
                .lastActivatedAt(LocalDateTime.now())
                .providerAttempts(0)
                .build();
    }

    /**
     * Следующий номер подписки. Пока подписка одна — это всегда 1; аллокатор существует,
     * чтобы к моменту появления второй подписки номер уже не выводился из количества строк.
     */
    private int allocateSeq(Customer user) {
        Integer seq = user.getSubscriptionSeq();
        return seq == null || seq < 1 ? 1 : seq;
    }

    /** Переносит подписочное состояние человека в зеркальную строку. */
    private void applyUserState(Subscription sub, Customer user) {
        sub.setProviderUsername(user.getProviderUsername());
        sub.setSubscriptionLink(user.getSubscriptionLink());
        sub.setSubscriptionToken(user.getSubscriptionToken());
        sub.setState(user.getStatus());
        // Текущая подписка живёт по дате; остаток в секундах — свойство ПРИПАРКОВАННОЙ,
        // и держать оба поля заполненными запрещено CHECK-ограничением в БД.
        sub.setExpiresAt(user.getExpiresAt());
        sub.setBalanceSeconds(null);
        sub.setDeviceLimit(user.getDeviceLimit());
        sub.setDeviceTierId(user.getDeviceTierId());
        sub.setAutorenewEnabled(user.isAutorenewEnabled());
        sub.setAutorenewPlanId(user.getAutorenewPlanId());
        sub.setAutorenewDeviceTierId(user.getAutorenewDeviceTierId());
        sub.setAutorenewLastFailureAt(user.getAutorenewLastFailureAt());
        sub.setNotified24h(user.isNotified24h());
        sub.setNotified1h(user.isNotified1h());
        sub.setWinbackSent(user.isWinbackSent());
        if (sub.getOrigin() == null) {
            sub.setOrigin(detectOrigin(user));
        }
    }

    /**
     * Откуда подписка. Детект двухчастный НАМЕРЕННО: префикс {@code trial_} остаётся в
     * имени навсегда даже после оплаты, поэтому одного его мало — платный юзер получил бы
     * {@code TRIAL}, и его следующая покупка «поглотила» бы остаток (ровно тот баг, ради
     * которого в {@code AccessService} и появилась проверка по истории платежей).
     */
    private Subscription.Origin detectOrigin(Customer user) {
        boolean trialPrefix = user.getProviderUsername() != null
                && user.getProviderUsername().startsWith("trial_");
        if (!trialPrefix) {
            return Subscription.Origin.PAID;
        }
        boolean everPaid = paymentRepository.existsByTelegramIdAndStatusIn(
                user.getTelegramId(), List.of(Payment.Status.SUCCEEDED, Payment.Status.REFUNDED));
        return everPaid ? Subscription.Origin.PAID : Subscription.Origin.TRIAL;
    }

    // ========================
    // Бэкфилл
    // ========================

    /**
     * Заводит зеркальные строки всем, у кого их ещё нет, и проставляет ссылки на подписку
     * в смежных таблицах. Идемпотентно: повторный запуск ничего не делает.
     * <p>
     * <b>Почему на Java, а не SQL-миграцией.</b> Тесты этого репозитория стартуют с
     * {@code ddl-auto: create-drop} и Liquibase НЕ выполняют вовсе — SQL-бэкфилл нельзя было
     * бы проверить ни одним тестом, а это перенос боевых данных 84 живых юзеров. Здесь же он
     * покрыт тестами, пишет в лог, что именно сделал, и перезапускается по {@code /subcheck}.
     *
     * @return сколько зеркальных строк создано
     */
    public int backfillMissing() {
        List<Long> pending = subscriptionRepository.findTelegramIdsWithoutSubscription();
        if (pending.isEmpty()) {
            return 0;
        }
        log.info("Бэкфилл подписок: {} юзеров без зеркальной строки", pending.size());
        int created = 0;
        for (Long telegramId : pending) {
            try {
                // КАЖДЫЙ юзер — в своей транзакции. Общая транзакция на весь цикл выглядела бы
                // безопасной из-за catch внутри, но это ложное спокойствие: нарушение
                // ограничения на одной строке помечает транзакцию rollback-only, и перенос
                // разваливался бы целиком уже на коммите — а пойманное исключение создавало бы
                // видимость, что проблемного юзера просто пропустили.
                if (Boolean.TRUE.equals(mirrorTxTemplate.execute(status -> backfillOne(telegramId)))) {
                    created++;
                }
            } catch (Exception e) {
                log.error("Бэкфилл подписки не удался для telegramId={} — остальные переносим",
                        telegramId, e);
            }
        }
        log.info("Бэкфилл подписок завершён: создано {} строк", created);
        return created;
    }

    /** Перенос одного человека. {@code false} — переносить было нечего. */
    private boolean backfillOne(Long telegramId) {
        Optional<Customer> userOpt = userRepository.findByTelegramId(telegramId);
        if (userOpt.isEmpty()) {
            return false;
        }
        Customer user = userOpt.get();
        // Повторная проверка внутри транзакции: между выборкой списка и этим моментом
        // зеркало мог создать обычный платёжный путь.
        if (subscriptionRepository.findByTelegramIdAndCurrentTrue(telegramId).isPresent()) {
            return false;
        }
        Subscription sub = newCurrentSubscription(user);
        applyUserState(sub, user);
        Subscription saved = subscriptionRepository.save(sub);
        linkRelatedRows(telegramId, saved.getId());
        seedPersonColumns(user);
        return true;
    }

    /** Проставляет subscription_id в платежах, устройствах и журнале сроков этого человека. */
    private void linkRelatedRows(Long telegramId, Long subscriptionId) {
        paymentRepository.findByTelegramIdOrderByCreatedAtDesc(telegramId).stream()
                .filter(p -> p.getSubscriptionId() == null)
                .forEach(p -> {
                    p.setSubscriptionId(subscriptionId);
                    p.setTargetKind("EXTEND");
                    paymentRepository.save(p);
                });
        deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(telegramId).stream()
                .filter(d -> d.getSubscriptionId() == null)
                .forEach(d -> {
                    d.setSubscriptionId(subscriptionId);
                    deviceRepository.save(d);
                });
        ledgerRepository.findByTelegramIdOrderByOccurredAtDesc(
                        telegramId, org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(l -> l.getSubscriptionId() == null)
                .forEach(l -> {
                    l.setSubscriptionId(subscriptionId);
                    ledgerRepository.save(l);
                });
    }

    /** Итог адресации старых записей журнала: сколько удалось, сколько осталось без адреса. */
    public record LedgerLinkResult(int linked, int skipped) {
        public int total() {
            return linked + skipped;
        }
    }

    /**
     * Разовая адресация СТАРЫХ записей журнала сроков, оставшихся без {@code subscription_id}.
     * <p>
     * Автоматически адрес проставляется только с PR #199; всё, что записано раньше, осталось
     * без него, а {@link #linkRelatedRows} чинит такие записи лишь в момент ПЕРВОГО переноса
     * человека — уже перенесённым он не поможет. Для человека с одной подпиской это ничего не
     * меняет, а вот у человека с двумя история читается как перемешанный список, и главный
     * вопрос разбора («куда делись дни ЭТОЙ подписки») по нему не разобрать.
     * <p>
     * <b>Адрес не угадывается.</b> Запись адресуется, только если у человека РОВНО ОДНА
     * подписка и она существовала на момент события: тогда других подписок у него не было и
     * нет, и ошибиться нечем. Всё остальное пропускается сознательно — в истории есть события
     * подписок, которые с тех пор удалены, и приписать их уцелевшей значило бы записать в
     * денежный журнал неправду. Пропущенное видно в ответе, чинится глазами через {@code /ledger}.
     * <p>
     * Идемпотентно: адресованные записи в выборку больше не попадают.
     *
     * @return сколько записей адресовано и сколько осталось без адреса
     */
    public LedgerLinkResult linkOrphanLedgerRows() {
        List<SubscriptionLedger> orphans;
        try {
            orphans = ledgerRepository.findBySubscriptionIdIsNullOrderByOccurredAtAsc();
        } catch (Exception e) {
            log.warn("Не смог прочитать безадресные записи журнала", e);
            return new LedgerLinkResult(0, 0);
        }
        if (orphans.isEmpty()) {
            return new LedgerLinkResult(0, 0);
        }
        int linked = 0;
        int skipped = 0;
        for (SubscriptionLedger orphan : orphans) {
            try {
                // Своя транзакция на запись — как в бэкфилле зеркала: одна проблемная строка
                // не должна отравлять транзакцию и обрывать перенос остальных.
                if (Boolean.TRUE.equals(mirrorTxTemplate.execute(status -> linkOneLedgerRow(orphan.getId())))) {
                    linked++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                skipped++;
                log.warn("Не смог адресовать запись журнала id={}", orphan.getId(), e);
            }
        }
        log.info("Журнал сроков: адресовано {} записей, осталось без адреса {}", linked, skipped);
        return new LedgerLinkResult(linked, skipped);
    }

    /** Одна запись. {@code false} — адрес определить нельзя, запись оставлена как есть. */
    private boolean linkOneLedgerRow(Long ledgerRowId) {
        SubscriptionLedger row = ledgerRepository.findById(ledgerRowId).orElse(null);
        if (row == null || row.getSubscriptionId() != null || row.getTelegramId() == null) {
            return false;
        }
        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(row.getTelegramId());
        if (subs.size() != 1) {
            // Ноль — человека ещё не перенесли; больше одной — какая из них та самая,
            // из записи не следует.
            return false;
        }
        Subscription only = subs.get(0);
        if (only.getCreatedAt() == null || only.getCreatedAt().isAfter(row.getOccurredAt())) {
            // Подписка появилась ПОСЛЕ события — значит событие было не про неё.
            return false;
        }
        row.setSubscriptionId(only.getId());
        ledgerRepository.save(row);
        return true;
    }

    /**
     * Проставляет человеку колонки-ворота: аллокатор номеров и дату израсходованного триала.
     * {@code trial_used_at} = дата создания: сегодня «строка существует» и означает
     * «триал использован», и это поведение надо перенести один-в-один.
     */
    private void seedPersonColumns(Customer user) {
        boolean changed = false;
        if (user.getSubscriptionSeq() == null) {
            user.setSubscriptionSeq(1);
            changed = true;
        }
        if (user.getTrialUsedAt() == null) {
            user.setTrialUsedAt(user.getCreatedAt() != null ? user.getCreatedAt() : LocalDateTime.now());
            changed = true;
        }
        if (changed) {
            userRepository.save(user);
        }
    }

    // ========================
    // Сверка
    // ========================

    /** Люди, у которых зеркало разошлось с {@code customers}. Пусто — всё сходится. */
    @Transactional(readOnly = true)
    public List<Long> findDivergent() {
        try {
            return subscriptionRepository.findDivergentTelegramIds();
        } catch (Exception e) {
            log.warn("Сверка подписок не выполнена", e);
            return List.of();
        }
    }

    /**
     * Находит расхождения, ПЕРЕЗАПИСЫВАЕТ зеркало по данным {@code customers} и возвращает
     * тех, у кого расхождение осталось.
     * <p>
     * Почему сверка чинит, а не только жалуется: зеркало — производные данные, источник правды
     * известен, поэтому «разошлось» почти всегда означает пропущенный вызов зеркалирования на
     * каком-то пути, а не порчу данных. Если бы сверка только слала алерт, один забытый путь
     * (например, докупка устройства) будил бы владельца КАЖДУЮ ночь месяцами — и алерт
     * перестал бы читаться ровно к тому моменту, когда от зеркала начнёт зависеть доступ.
     * Теперь ночной алерт означает «починить не удалось», то есть настоящую проблему.
     *
     * @return telegram_id, которые не удалось привести в соответствие
     */
    public List<Long> reconcileDivergent() {
        List<Long> divergent = findDivergent();
        if (divergent.isEmpty()) {
            return List.of();
        }
        log.info("Сверка подписок: расхождений {} — перезаписываю зеркало", divergent.size());
        for (Long telegramId : divergent) {
            mirrorNow(telegramId);
        }
        List<Long> stillDivergent = findDivergent();
        if (!stillDivergent.isEmpty()) {
            log.warn("Сверка подписок: после перезаписи осталось {} расхождений", stillDivergent.size());
        }
        return stillDivergent;
    }

    /** Сколько людей уже имеют зеркальную строку — для {@code /subcheck}. */
    @Transactional(readOnly = true)
    public long mirroredCount() {
        return subscriptionRepository.count();
    }

    /** Подписки человека для админ-карточки и будущего переключателя. */
    @Transactional(readOnly = true)
    public List<Subscription> listFor(Long telegramId) {
        return subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId);
    }
}
