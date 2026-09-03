package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * Заводит ВТОРУЮ (третью, …) подписку человеку — единственный путь, которым в системе
 * появляется новая подписка поверх уже существующей.
 * <p>
 * <b>Новая подписка рождается замороженной.</b> Не потому, что так проще, а потому, что
 * человек в этот момент пользуется другой: включить новую сразу значило бы молча оборвать
 * работающий доступ на действии, которое человек воспринимает как «продлил». Дни новой подписки
 * лежат в {@code balance_seconds} и не тикают, включает её человек сам. Исключение одно —
 * живой текущей подписки нет (первая оплата после истечения, все подписки заморожены): тогда
 * оставить человека без доступа после оплаты было бы худшим исходом, и мы включаем новую сами.
 * <p>
 * <b>Аккаунт в панели создаётся активным — это неизбежно</b> (у AccessProvider нет «создать
 * выключенным»), поэтому строка сразу получает намерение {@code PARK}, и сразу после коммита
 * аккаунт выключается. Без намерения человек на несколько минут получил бы два живых аккаунта,
 * то есть удвоенный лимит устройств: ровно то, за что не платили. Падение между коммитом и
 * вызовом панели не теряется — незакрытое намерение доведёт {@link SubscriptionSyncReconciler}.
 * <p>
 * <b>Идемпотентность.</b> {@code payments.subscription_id} проставляется В ТОЙ ЖЕ транзакции,
 * что и вставка строки. Поэтому повторная обработка того же платежа (передоставка вебхука,
 * реконсилер) видит заполненную цель и уходит в ветку «зачислить в существующую» — второй
 * аккаунт в панели не появляется, номер подписки не сгорает.
 * <p>
 * Полный план — {@code docs/multi-subscription-stage5-plan.md}.
 */
@Slf4j
@Service
public class SubscriptionMintService {

    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final AccessProvider accessProvider;
    private final AccessSyncService accessSync;
    private final SubscriptionSwitchService switchService;
    private final SubscriptionLedgerService ledger;
    private final NotificationService notificationService;
    private final MultiSubscriptionPolicy policy;
    /**
     * Своя транзакция для работы ПОСЛЕ коммита денежной. Нужен именно шаблон, а не
     * {@code @Transactional}: в момент {@code afterCommit} транзакция уже закоммичена, но её
     * ресурсы ещё привязаны к потоку, и обычный {@code REQUIRED} молча ПРИСОЕДИНИЛСЯ БЫ к
     * завершённой транзакции — записи прошли бы через Hibernate и не закоммитились никогда.
     * Тот же приём и по той же причине используется в {@code SubscriptionRegistryService}.
     */
    private final TransactionTemplate settleTxTemplate;

    public SubscriptionMintService(SubscriptionRepository subscriptionRepository,
                                   CustomerRepository userRepository,
                                   PaymentRepository paymentRepository,
                                   AccessProvider accessProvider,
                                   AccessSyncService accessSync,
                                   SubscriptionSwitchService switchService,
                                   SubscriptionLedgerService ledger,
                                   NotificationService notificationService,
                                   MultiSubscriptionPolicy policy,
                                   PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.accessProvider = accessProvider;
        this.accessSync = accessSync;
        this.switchService = switchService;
        this.ledger = ledger;
        this.notificationService = notificationService;
        this.policy = policy;
        this.settleTxTemplate = new TransactionTemplate(transactionManager);
        this.settleTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Создаёт новую замороженную подписку и зачисляет в неё оплаченные дни.
     * <p>
     * Транзакции своей НЕТ намеренно: метод обязан ехать внутри платёжной транзакции
     * вызывающего. Иначе строка подписки и метка цели на платеже коммитились бы порознь, и
     * падение между ними оставило бы подписку без платежа (или платёж без подписки) — то есть
     * ровно тот класс «деньги приняли, учёт не сошёлся», который этот этап и закрывает.
     *
     * @param payment оплаченный платёж; ему проставляется {@code subscription_id}
     * @param days    сколько дней куплено
     * @param tier    ступень устройств покупки; {@code null} — legacy-покупка без ступени
     * @return созданная подписка (заморожена, {@code current = false})
     */
    @Transactional
    public Subscription mint(Payment payment, int days, AppConfig.DeviceTier tier) {
        Long telegramId = payment.getTelegramId();
        LocalDateTime now = LocalDateTime.now();

        // Порядок замков в проекте един: платёж → человек → подписки. Блокировка человека
        // сериализует выдачу номеров: два одновременных платежа не получат один и тот же seq.
        Customer user = userRepository.findByTelegramIdForUpdate(telegramId).orElse(null);
        int seq = allocateSeq(user, telegramId);

        warnIfOverLimit(telegramId, payment);

        String providerUsername = buildProviderUsername(telegramId, payment.getTelegramUsername(), seq);
        // Имя уже занято другой подпиской — значит аллокатор номеров дал повтор. Молча
        // продолжать нельзя: createUser поймал бы 409 и «усыновил» чужой аккаунт, слив две
        // подписки в один аккаунт панели (общий срок, общий лимит устройств, общая ссылка).
        subscriptionRepository.findByProviderUsername(providerUsername).ifPresent(existing -> {
            throw new IllegalStateException("Имя аккаунта " + providerUsername
                    + " уже принадлежит подписке id=" + existing.getId()
                    + " — номер подписки выдан повторно, новая подписка не создана");
        });

        Integer ipLimit = tier != null ? tier.effectiveProviderDeviceLimit() : null;
        AccessProvider.AccessAccount account;
        try {
            // Без ступени зовём 3-арг перегрузку — тот же путь, что у первой покупки.
            account = ipLimit == null
                    ? accessProvider.createUser(providerUsername, days, 0)
                    : accessProvider.createUser(providerUsername, days, 0, ipLimit);
        } catch (Exception e) {
            // Транзакция откатится, платёж останется PENDING — реконсилер повторит. Владельцу
            // сообщаем: без этого недоступная панель осталась бы незамеченной до жалобы.
            log.error("Не удалось создать аккаунт {} в панели для новой подписки telegramId={}",
                    providerUsername, telegramId, e);
            notifyOwnerSafely(telegramId, payment.getId(),
                    "панель не создала аккаунт " + providerUsername + " для новой подписки: "
                            + e.getMessage());
            throw e;
        }

        long paidSeconds = SubscriptionTime.daysToSeconds(days);
        Subscription sub = Subscription.builder()
                .telegramId(telegramId)
                .seq(seq)
                .providerUsername(providerUsername)
                .subscriptionLink(account.getSubscriptionUrl())
                // У Subscription нет JPA-хука, который держит токен в согласии со ссылкой
                // (у Customer он есть) — здесь токен проставляется явно, иначе выдача подписки
                // по ссылке не нашла бы эту строку после включения.
                .subscriptionToken(SubscriptionLinks.extractToken(account.getSubscriptionUrl()))
                .current(false)
                .state(Customer.Status.ACTIVE)
                .expiresAt(null)
                .balanceSeconds(paidSeconds)
                .deviceLimit(tier != null ? tier.getDevices() : null)
                .deviceTierId(tier != null ? tier.getId() : null)
                .origin(Subscription.Origin.PAID)
                .providerIntent(AccessSyncService.INTENT_PARK)
                .providerIntentAt(now)
                .providerAttempts(0)
                .createdAt(now)
                .parkedAt(now)
                .build();
        Subscription saved = subscriptionRepository.save(sub);

        // В ТОЙ ЖЕ транзакции — см. джавадок класса про идемпотентность. {@code target_kind}
        // намеренно остаётся 'NEW': это исторический факт («этим платежом открыли подписку»),
        // и он нужен и аналитике, и разбору. Повторная обработка различает ветки по
        // заполненному subscription_id, а не по этой метке.
        payment.setSubscriptionId(saved.getId());
        paymentRepository.save(payment);

        ledger.record(telegramId, saved.getId(),
                payment.isAutorenew() ? SubscriptionLedger.Kind.AUTORENEW : SubscriptionLedger.Kind.PURCHASE,
                paidSeconds, paidSeconds, payment.getId(),
                "новая подписка #" + seq + ", " + days + " дн."
                        + (tier != null ? ", " + tier.getId() : "") + " (заморожена)");

        log.info("Создана подписка id={} (#{}) для telegramId={}: {} дн. заморожено, аккаунт {}",
                saved.getId(), seq, telegramId, days, providerUsername);

        settleAfterCommit(telegramId, saved.getId());
        return saved;
    }

    /**
     * Номер новой подписки. Берём максимум из счётчика человека и наибольшего уже выданного
     * номера — счётчик мог остаться незаполненным (его ведёт бэкфилл), а повторный номер
     * означает повторное имя аккаунта в панели. Счётчик тут же догоняется.
     */
    private int allocateSeq(Customer user, Long telegramId) {
        int fromCounter = user != null && user.getSubscriptionSeq() != null
                ? user.getSubscriptionSeq() : 0;
        int next = Math.max(fromCounter, subscriptionRepository.findMaxSeq(telegramId)) + 1;
        if (user != null) {
            user.setSubscriptionSeq(next);
            userRepository.save(user);
        }
        return next;
    }

    /**
     * Имя аккаунта в панели. При {@code seq == 1} даёт сегодняшнее имя — то есть первая
     * подписка человека выглядит ровно так, как выглядела всегда, и миграции имён не нужно.
     * Повторяет санитизацию {@code AccessService.buildProviderUsername}: имя из Telegram приходит от
     * человека и в имя аккаунта панели попадать сырым не должно.
     */
    static String buildProviderUsername(Long telegramId, String telegramUsername, int seq) {
        String base = telegramUsername != null && !telegramUsername.isBlank()
                ? "tg_" + telegramUsername.toLowerCase().replaceAll("[^a-z0-9_]", "_") + "_" + telegramId
                : "tg_" + telegramId;
        return seq <= 1 ? base : base + "_s" + seq;
    }

    /**
     * Потолок подписок проверяется на чекауте ({@code PaymentTargetResolver}), здесь — только
     * наблюдение. Отказать на активации нельзя: деньги уже приняты, и «платёж не проведён,
     * потому что у вас много подписок» — худший из возможных ответов.
     */
    private void warnIfOverLimit(Long telegramId, Payment payment) {
        long existing = subscriptionRepository.countByTelegramId(telegramId);
        if (existing >= policy.maxPerUser()) {
            log.warn("Новая подписка сверх лимита: у telegramId={} уже {} подписок при потолке {}",
                    telegramId, existing, policy.maxPerUser());
            notifyOwnerSafely(telegramId, payment.getId(),
                    "создана подписка сверх потолка (" + existing + " при лимите "
                            + policy.maxPerUser() + ")");
        }
    }

    /**
     * Доводит подписку до нужного состояния ПОСЛЕ коммита денежной транзакции: если у человека
     * ничего не работает — включает её, иначе досылает в панель «выключено».
     * <p>
     * Зовётся и после создания новой подписки, и после зачисления денег в уже существующую
     * замороженную: правило «человек, только что заплативший, не остаётся без доступа» одно
     * на оба случая.
     * <p>
     * Именно после коммита: включение ({@code switchTo}) пишет в частичный уникальный индекс
     * «одна текущая подписка», а его нарушение — {@code DataIntegrityViolationException},
     * который в Postgres отравляет транзакцию целиком. Внутри платёжной транзакции это
     * оставило бы платёж PENDING до реконсилера при полностью успешной оплате.
     */
    public void settleAfterCommit(Long telegramId, Long subscriptionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            settle(telegramId, subscriptionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                settle(telegramId, subscriptionId);
            }
        });
    }

    private void settle(Long telegramId, Long subscriptionId) {
        try {
            // Своя транзакция — иначе записи переключения не закоммитятся (см. поле шаблона).
            SubscriptionSwitchService.Result result = settleTxTemplate.execute(status ->
                    hasLiveCurrent(telegramId)
                            ? null
                            : switchService.switchTo(telegramId, subscriptionId));
            if (result == null) {
                // Человек продолжает пользоваться текущей подпиской — новая ждёт включения.
                accessSync.push(subscriptionId);
                return;
            }
            switchService.pushToPanelAfterCommit(result);
            log.info("Живой текущей подписки не было — включил новую id={} для telegramId={} ({})",
                    subscriptionId, telegramId, result.outcome());
        } catch (Exception e) {
            // Fail closed: не смогли включить — гасим аккаунт в панели. Человек в этот момент
            // без доступа, но не с двумя живыми аккаунтами; владелец получает алерт, реконсилер
            // доведёт намерение. Дни при этом целы — они лежат на строке подписки.
            log.error("Не удалось включить новую подписку id={} для telegramId={} — "
                    + "оставляю замороженной", subscriptionId, telegramId, e);
            notifyOwnerSafely(telegramId, null,
                    "новая подписка id=" + subscriptionId + " создана, но не включилась автоматически "
                            + "(" + e.getMessage() + ") — дни на месте, нужно включить вручную");
            accessSync.push(subscriptionId);
        }
    }

    /** Есть ли у человека подписка, которая прямо сейчас даёт доступ. */
    private boolean hasLiveCurrent(Long telegramId) {
        return subscriptionRepository.findByTelegramIdAndCurrentTrue(telegramId)
                .filter(s -> s.getState() == Customer.Status.ACTIVE)
                .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isAfter(LocalDateTime.now()))
                .isPresent();
    }

    private void notifyOwnerSafely(Long telegramId, Long paymentId, String reason) {
        try {
            notificationService.notifyOwnerPaymentTargetAnomaly(telegramId, paymentId, reason);
        } catch (Exception e) {
            log.warn("Не удалось сообщить владельцу: {}", reason, e);
        }
    }
}
