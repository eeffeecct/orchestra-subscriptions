package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import com.orchestra.subscriptions.util.TokenMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class AccessService {

    private final AccessProvider accessProvider;
    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final UserDeviceRepository userDeviceRepository;
    /** Строка подписки: у каждой свой аккаунт панели, свои устройства и своя ссылка. */
    private final SubscriptionRepository subscriptionRepository;
    private final AppConfig appConfig;
    /**
     * Lazy — NotificationService → SubscriptionBot → AccessService: циклическая зависимость
     * (бот дергает AccessService при /start и т.п.). {@code @Lazy} разрывает её на старте.
     */
    private final NotificationService notificationService;
    /**
     * Lazy по той же причине: DeviceTrackingService → DeviceNotificationService → бот →
     * AccessService. Нужен, чтобы понижение лимита устройств применялось сразу (см.
     * {@link #enforceDeviceLimit}).
     */
    private final DeviceTrackingService deviceTrackingService;
    /** Журнал изменений срока — только наблюдение, бизнес-логику не меняет (см. класс). */
    private final SubscriptionLedgerService ledger;
    /**
     * Зеркало подписочного состояния в таблице {@code subscriptions}. Пока только
     * наполняется: источник правды — {@code customers}, читатели переедут следующим этапом.
     */
    private final SubscriptionRegistryService subscriptionRegistry;

    public AccessService(AccessProvider accessProvider,
                      CustomerRepository userRepository,
                      PaymentRepository paymentRepository,
                      UserDeviceRepository userDeviceRepository,
                      SubscriptionRepository subscriptionRepository,
                      AppConfig appConfig,
                      @Lazy NotificationService notificationService,
                      @Lazy DeviceTrackingService deviceTrackingService,
                      SubscriptionLedgerService ledger,
                      SubscriptionRegistryService subscriptionRegistry) {
        this.accessProvider = accessProvider;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.userDeviceRepository = userDeviceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.appConfig = appConfig;
        this.notificationService = notificationService;
        this.deviceTrackingService = deviceTrackingService;
        this.ledger = ledger;
        this.subscriptionRegistry = subscriptionRegistry;
    }


    /**
     * Применяет понижение лимита устройств к уже зарегистрированным устройствам.
     * Тонкая обёртка над {@link DeviceTrackingService#enforceLimit} — чтобы вызывающим
     * ({@code PaymentProcessingService}) не тянуть ещё одну зависимость с риском
     * циклов: AccessService у них уже инжектится.
     *
     * @param newLimit новый лимит; {@code null}/{@code <= 0} — без лимита, ничего не делаем
     */
    public int enforceDeviceLimit(Long telegramId, Integer newLimit) {
        if (newLimit == null || newLimit <= 0) {
            return 0;
        }
        return deviceTrackingService.enforceLimit(telegramId, newLimit);
    }

    /**
     * То же, но в пределах одной подписки: понижение лимита у НЕ работающей подписки не должно
     * снимать со слотов устройства той, которой человек пользуется прямо сейчас.
     */
    public int enforceDeviceLimit(Subscription subscription, Integer newLimit) {
        if (subscription == null || newLimit == null || newLimit <= 0) {
            return 0;
        }
        return deviceTrackingService.enforceLimit(SubscriptionView.of(subscription, null), newLimit);
    }

    /**
     * Обновить зеркало подписки после изменения состояния юзера. Тонкая обёртка для тех, кто
     * правит {@code customers} в обход {@code AccessService} ({@code PaymentProcessingService},
     * {@code AutoRenewService}) — им уже инжектится этот сервис, и тянуть ещё одну зависимость
     * значит плодить риск циклов в Spring.
     * <p>
     * Звать обязан КАЖДЫЙ, кто меняет зеркалируемое поле: срок, статус, лимит устройств,
     * ступень, ссылку/токен, настройки автопродления. Пропуск не ломает выдачу доступа
     * (правда живёт в {@code customers}), но отравляет сверку — а она и есть весь смысл
     * этого этапа.
     */
    public void mirrorSubscription(Long telegramId) {
        subscriptionRegistry.mirrorAfterCommit(telegramId);
    }

    /** Платёж считается «деньги хоть раз приходили» (включая возвращённые) — такой юзер уже
     *  не триал. Совпадает с детектом в {@code SubscriptionExpiryNotifier.isTrialUser}. */
    private static final Set<Payment.Status> PAID_STATUSES =
            Set.of(Payment.Status.SUCCEEDED, Payment.Status.REFUNDED);

    /** Длительность бесплатного триала. Меняли 24h → 7d 2026-05-24 (юзеру за день
     *  тяжело распробовать сервис — не успевает решить покупать ли). */
    private static final int TRIAL_DAYS = 7;

    /**
     * Создать пробный доступ на {@link #TRIAL_DAYS} дней. Доступно один раз на telegramId.
     * <p>
     * AccessProvider и наша БД получают одинаковый срок: иначе при истечении одного из
     * двух — пользователь увидит что доступ не работает, но в Mini App статус
     * показывает «активна», или наоборот.
     */
    @Transactional
    public Customer createTrialAccess(Long telegramId, String telegramUsername) {
        if (userRepository.existsByTelegramId(telegramId)) {
            throw new IllegalStateException("Trial already used");
        }

        String providerUsername = buildProviderUsername("trial", telegramId, telegramUsername);

        // Лимит устройств триала (app.trial-device-limit, 0 = без лимита). ip_limit в AccessProvider —
        // страховка на уровне соединений, devices+1 (смена сети коротко даёт второй IP).
        // Без лимита зовём старую 3-арг сигнатуру — поведение (и моки в тестах) не меняются.
        int trialDevices = appConfig.getTrialDeviceLimit();
        AccessProvider.AccessAccount accessAccount = trialDevices > 0
                ? accessProvider.createUser(providerUsername, TRIAL_DAYS, 0, trialDevices + 1)
                : accessProvider.createUser(providerUsername, TRIAL_DAYS, 0);

        Customer customer = Customer.builder()
                .telegramId(telegramId)
                .telegramUsername(telegramUsername)
                .providerUsername(providerUsername)
                .subscriptionLink(accessAccount.getSubscriptionUrl())
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(TRIAL_DAYS))
                .deviceLimit(trialDevices > 0 ? trialDevices : null)
                // Явные ворота триала. Сегодня «строка в customers существует» и означает
                // «триал израсходован», но это перестанет работать, как только человек сможет
                // держать несколько подписок: строка человека останется одна, а подписок будет
                // много, и признак «подписки нет» вернулся бы вместе с бесплатной неделей.
                .trialUsedAt(LocalDateTime.now())
                .build();

        Customer saved = userRepository.save(customer);
        ledger.record(telegramId, SubscriptionLedger.Kind.TRIAL,
                SubscriptionTime.daysToSeconds(TRIAL_DAYS),
                SubscriptionTime.remainingSeconds(saved.getExpiresAt(), LocalDateTime.now()),
                null, "триал " + TRIAL_DAYS + " дн.");
        subscriptionRegistry.mirrorAfterCommit(telegramId);
        return saved;
    }

    /**
     * Активировать платную подписку после успешной оплаты в ЮKassa.
     * <p>
     * Поведение в зависимости от текущего состояния пользователя:
     * <ul>
     *   <li>Нет записи — создаём новый tg_* аккаунт в AccessProvider, expiresAt = now + days.</li>
     *   <li>Активный триал (trial_*) — переиспользуем тот же AccessProvider-аккаунт,
     *       ЗАМЕНЯЕМ expiresAt на now + days (оставшиеся дни триала «поглощаются»).</li>
     *   <li>Любой другой существующий пользователь (платный активный, disabled, expired,
     *       или с расхождением БД↔AccessProvider) — СТЕКАЕМ дни поверх фактического остатка.
     *       За фактический остаток берётся максимум из
     *       {@code (provider.expire, db.expires_at, now)} — так мы никогда не теряем
     *       оставшееся у юзера время, даже если одна из двух систем хранит
     *       устаревшее значение (например, после миграции с Tribute).</li>
     * </ul>
     */
    @Transactional
    public Customer activatePaidSubscription(Long telegramId, String telegramUsername, int days) {
        return activatePaidSubscription(telegramId, telegramUsername, days, null);
    }

    /**
     * Начислить дни БЕЗ покупки тарифа — реф-бонус, подарок владельца, админское продление.
     * Механика ровно та же, что у оплаты (стекинг поверх остатка, ступень устройств не
     * трогается), отличается только причина в журнале сроков: сам {@code AccessService}
     * отличить бонус от покупки не может, а в {@code /ledger} это главное.
     * <p>
     * Отдельное имя, а не перегрузка: {@code activatePaidSubscription(id, name, days, null)}
     * стал бы неоднозначным между ступенью и причиной, и компилятор ловил бы это только
     * при точном совпадении типов — слишком легко промахнуться молча.
     */
    @Transactional
    public Customer addDays(Long telegramId, String telegramUsername, int days,
                           SubscriptionLedger.Kind reason) {
        return activatePaidSubscription(telegramId, telegramUsername, days, null, reason);
    }

    /**
     * То же, но с выбранной ступенью устройств ({@code app.device-tiers}): назначает
     * {@code device_limit}/{@code device_tier_id} юзеру и per-user {@code ip_limit} в AccessProvider.
     * {@code tier == null} — legacy-путь (реф-бонусы, /extend, гранты): лимит устройств
     * юзера НЕ трогаем, ip_limit глобальный.
     */
    @Transactional
    public Customer activatePaidSubscription(Long telegramId, String telegramUsername, int days,
                                            AppConfig.DeviceTier tier) {
        return activatePaidSubscription(telegramId, telegramUsername, days, tier,
                SubscriptionLedger.Kind.PURCHASE);
    }

    /** Полная форма: ступень устройств + причина для журнала сроков. */
    @Transactional
    public Customer activatePaidSubscription(Long telegramId, String telegramUsername, int days,
                                            AppConfig.DeviceTier tier,
                                            SubscriptionLedger.Kind ledgerKind) {
        Optional<Customer> existing = userRepository.findByTelegramId(telegramId);
        LocalDateTime now = LocalDateTime.now();
        Integer tierIpLimit = tier != null ? tier.effectiveProviderDeviceLimit() : null;

        if (existing.isEmpty()) {
            String providerUsername = buildProviderUsername("tg", telegramId, telegramUsername);
            AccessProvider.AccessAccount accessAccount;
            try {
                // Без ступени зовём старую 3-арг сигнатуру — legacy-путь байт-в-байт как раньше.
                accessAccount = tierIpLimit == null
                        ? accessProvider.createUser(providerUsername, days, 0)
                        : accessProvider.createUser(providerUsername, days, 0, tierIpLimit);
            } catch (Exception e) {
                // Транзакция откатится, webhook/autorenew сделают retry — но владельцу надо знать,
                // что панель недоступна, иначе проблема останется незамеченной до жалобы юзера.
                notifyOwnerAccessSyncFailureSafely(
                        "activatePaidSubscription/createUser", telegramId, providerUsername, e);
                throw e;
            }

            Customer customer = Customer.builder()
                    .telegramId(telegramId)
                    .telegramUsername(telegramUsername)
                    .providerUsername(providerUsername)
                    .subscriptionLink(accessAccount.getSubscriptionUrl())
                    .status(Customer.Status.ACTIVE)
                    .expiresAt(now.plusDays(days))
                    .deviceLimit(tier != null ? tier.getDevices() : null)
                    .deviceTierId(tier != null ? tier.getId() : null)
                    .build();
            log.info("Created paid access for telegram user {} ({}), {} days, tier={}",
                    telegramId, telegramUsername, days, tier != null ? tier.getId() : "-");
            Customer saved = userRepository.save(customer);
            ledger.record(telegramId, ledgerKind, SubscriptionTime.daysToSeconds(days),
                    SubscriptionTime.remainingSeconds(saved.getExpiresAt(), now), null,
                    "новый аккаунт, " + days + " дн." + (tier != null ? ", " + tier.getId() : ""));
            subscriptionRegistry.mirrorAfterCommit(telegramId);
            return saved;
        }

        Customer user = existing.get();
        // Снимок срока ДО изменения — из него журнал считает фактическую дельту.
        LocalDateTime expiryBefore = user.getExpiresAt();
        // Триал = (имя начинается с trial_) И (ни одной SUCCEEDED/REFUNDED-оплаты).
        // Префикс provider_username сам по себе ненадёжен: после первой оплаты он остаётся
        // trial_* навсегда. Старая эвристика «префикс + остаток <= (TRIAL_DAYS+1) дней»
        // ложно срабатывала на платных юзеров с trial_*-именем в последние ~8 дней —
        // их остаток «поглощался» при автопродлении / манульной покупке / реф-бонусе /
        // admin /extend. Payment-history check совпадает с детектом в
        // SubscriptionExpiryNotifier.isTrialUser и /api/me.isTrial.
        boolean hasTrialPrefix = user.getProviderUsername() != null && user.getProviderUsername().startsWith("trial_");
        boolean isTrial = hasTrialPrefix
                && !paymentRepository.existsByTelegramIdAndStatusIn(telegramId, PAID_STATUSES);

        LocalDateTime newExpiry;
        if (isTrial) {
            // Триал → платный: оставшиеся дни триала «растворяются», срок = now + days
            newExpiry = now.plusDays(days);
        } else if (user.getStatus() == Customer.Status.DISABLED) {
            // DISABLED → fresh start с {@code now}, НЕ наследуем старый expires_at и НЕ
            // спрашиваем AccessProvider. Иначе бы получили "deadweight"-дни в двух кейсах:
            //   1. Refund: applyRefundEffects → disableAccess сбрасывает status, но не
            //      expires_at в БД и не expire в AccessProvider. Без этой ветки следующая оплата
            //      делала бы newExpiry = old_expires_at + days (например, refund в день
            //      покупки месячного тарифа → +30 дней мимо денег пользователя).
            //   2. Естественное истечение → admin вручную disable: expires_at в прошлом
            //      или совпадает с now, max() корректен и без этой ветки, но явная
            //      проверка status=DISABLED делает поведение предсказуемым.
            // Юзер заплатил → получает ровно days с момента активации, не больше.
            newExpiry = now.plusDays(days);
        } else {
            // ACTIVE: фактический остаток = max(provider.expire, db.expires_at, now).
            // AccessProvider — источник правды, БД — кеш, now — нижняя граница (чтобы не уходить в прошлое).
            LocalDateTime effectiveCurrent = now;
            if (user.getExpiresAt() != null && user.getExpiresAt().isAfter(effectiveCurrent)) {
                effectiveCurrent = user.getExpiresAt();
            }
            LocalDateTime providerExpiry = fetchProviderExpiry(user.getProviderUsername());
            if (providerExpiry != null && providerExpiry.isAfter(effectiveCurrent)) {
                effectiveCurrent = providerExpiry;
            }
            newExpiry = effectiveCurrent.plusDays(days);
        }

        try {
            // Точный момент, не «целых суток от сейчас»: округление резало юзеру дробную
            // часть суток в AccessProvider (доступ умирал раньше, чем показывал бот/Mini App).
            // ip_limit: явная ступень, иначе выводим из device_limit юзера — иначе продление
            // без ступени (реф-бонус, грант, /extend) сбрасывало семейный лимит (см. resolveIpLimit).
            accessProvider.enableUserUntil(user.getProviderUsername(), toInstant(newExpiry),
                    resolveIpLimit(user, tier));
        } catch (Exception e) {
            notifyOwnerAccessSyncFailureSafely(
                    "activatePaidSubscription/enableUser", telegramId, user.getProviderUsername(), e);
            throw e;
        }
        user.setStatus(Customer.Status.ACTIVE);
        user.setExpiresAt(newExpiry);
        user.setNotified24h(false);
        user.setNotified1h(false);
        user.setWinbackSent(false);
        if (tier != null) {
            // Смена ступени (включая даунгрейд «семейная → 1 устройство») применяется сразу:
            // лимит в БД + перевод лишних уже подключённых устройств в BLOCKED. Именно это
            // обещают экран покупки и Mini App («лишние будут отключены»), и до 2026-08-12
            // обещание не выполнялось: enforce не вызывался, а track() сверял лимит только
            // для НОВЫХ устройств — даунгрейд не ограничивал вообще ничего.
            user.setDeviceLimit(tier.getDevices());
            user.setDeviceTierId(tier.getId());
            deviceTrackingService.enforceLimit(telegramId, tier.getDevices());
        }

        // Починка subscription_link: если он относительный (/sub/...) — дописываем публичный префикс.
        // Защита от легаси-записей и кейсов, где AccessProvider вернул неполный URL.
        fixSubscriptionLinkIfNeeded(user);

        log.info("Activated paid subscription for telegram user {}: isTrial={}, newExpiry={}",
                telegramId, isTrial, newExpiry);
        // Дельту считаем от СТАРОГО срока: при поглощении триала («растворяются» его дни)
        // и при стекинге поверх остатка она отличается от days×86400, и в журнале должно
        // быть видно именно фактическое движение, а не номинал тарифа.
        ledger.recordExpiryChange(telegramId, ledgerKind, expiryBefore, newExpiry, null,
                days + " дн." + (tier != null ? ", " + tier.getId() : "")
                        + (isTrial ? " (поглощён триал)" : ""));
        Customer persisted = userRepository.save(user);
        subscriptionRegistry.mirrorAfterCommit(telegramId);
        return persisted;
    }

    /**
     * Получает текущий expire из AccessProvider для существующего пользователя.
     * Возвращает null, если пользователя нет или expire не задан.
     */
    private LocalDateTime fetchProviderExpiry(String providerUsername) {
        try {
            AccessProvider.AccessAccount mUser = accessProvider.getUser(providerUsername).orElse(null);
            if (mUser == null || mUser.getExpire() == null || mUser.getExpire() <= 0) {
                return null;
            }
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(mUser.getExpire()), ZoneId.systemDefault());
        } catch (Exception e) {
            log.warn("Failed to read AccessProvider expiry for {}, falling back to DB value", providerUsername, e);
            return null;
        }
    }

    /**
     * {@code expires_at} (timezone-naïve, в зоне JVM — в проде UTC) → момент для AccessProvider.
     * Строго обратна {@link #fetchProviderExpiry}, поэтому «записали в AccessProvider → прочитали
     * обратно» даёт ровно то же значение, и {@code max(provider, db)} при продлении не
     * накапливает дрейф.
     */
    private static Instant toInstant(LocalDateTime localExpiry) {
        return localExpiry.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Какой {@code ip_limit} слать в AccessProvider, когда вызывающий не назвал ступень явно.
     * <p>
     * {@link AccessProvider#enableUserUntil} переписывает {@code ip_limit} на КАЖДОМ вызове, а
     * {@code null} там означает «глобальный {@code app.provider.ip-limit}» (5). Поэтому любой
     * путь без ступени — реф-бонус, грант, админский {@code /extend}, урезание срока при
     * возврате — молча ронял семейную подписку с её 11 одновременных IP до 5: {@code
     * device_limit} в БД оставался правильным, а AccessProvider начинал резать шестое соединение.
     * Обратная сторона тоже вредная: юзер на «1 устройство» получал 5 вместо 2, т.е. лимит
     * перставал работать.
     * <p>
     * Чиним, выводя лимит из фактического состояния юзера: {@code device_limit} — это
     * актуальное число слотов (ступень покупки плюс докупленные слоты), а конвенция ip_limit
     * по всему коду — {@code devices + 1} (смена сети коротко даёт второй IP). Явная ступень
     * приоритетнее: при покупке {@code device_limit} в объекте юзера ещё старый.
     * <p>
     * {@code null} на выходе = «нет данных о лимите» (legacy-юзер до фичи ступеней) — там
     * по-прежнему уезжает глобальный дефолт, как и было задумано.
     */
    private static Integer resolveIpLimit(Customer user, AppConfig.DeviceTier explicitTier) {
        if (explicitTier != null) {
            return explicitTier.effectiveProviderDeviceLimit();
        }
        Integer deviceLimit = user == null ? null : user.getDeviceLimit();
        return deviceLimit != null && deviceLimit > 0 ? deviceLimit + 1 : null;
    }

    /**
     * Если subscription_link относительный (/sub/...) — дописывает публичный префикс из конфига.
     * Защита от легаси-записей и кейсов, где AccessProvider вернул неполный URL при createUser.
     */
    private void fixSubscriptionLinkIfNeeded(Customer user) {
        String link = user.getSubscriptionLink();
        if (link != null && link.startsWith("/")) {
            String prefix = appConfig.getProvider().getSubscriptionUrlPrefix();
            if (prefix != null && !prefix.isBlank()) {
                user.setSubscriptionLink(prefix + link);
                // Ссылку целиком в лог не пишем: её хвост — подписочный токен, единственный
                // аутентификатор /sub/{token}. Префикс не секрет (он из конфига), токен —
                // только маской, как в AccessDeliveryController.
                log.info("Fixed relative subscription link for user {} → prefix={}, token={}",
                        user.getTelegramId(), prefix,
                        TokenMasker.mask(SubscriptionLinks.extractToken(link)));
            }
        }
    }

    /**
     * Явно установить expires_at на указанную дату + синхронизировать AccessProvider. Используется
     * при возвратах, когда нужно УМЕНЬШИТЬ срок подписки (не добавлять дни).
     * <p>
     * Не трогает другие поля (notified_*, status, autorenew_*). Если {@code newExpiry} уже
     * в прошлом — ничего не делает, вызывающая сторона должна была позвать
     * {@link #disableAccess} вместо этого.
     */
    @Transactional
    public void setExpiry(Long telegramId, LocalDateTime newExpiry) {
        LocalDateTime now = LocalDateTime.now();
        if (newExpiry.isBefore(now)) {
            log.debug("setExpiry with past date {} for telegramId={} — no-op", newExpiry, telegramId);
            return;
        }
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            LocalDateTime expiryBefore = user.getExpiresAt();
            user.setExpiresAt(newExpiry);
            userRepository.save(user);
            // setExpiry зовут только чтобы УМЕНЬШИТЬ срок при возврате денег — отсюда REFUND.
            ledger.recordExpiryChange(telegramId, SubscriptionLedger.Kind.REFUND,
                    expiryBefore, newExpiry, null, "срок урезан при возврате");
            try {
                // ip_limit из состояния юзера, а не null: null здесь означал «глобальный 5» и
                // урезание срока при возврате попутно сбрасывало семейный лимит (см. resolveIpLimit).
                accessProvider.enableUserUntil(user.getProviderUsername(), toInstant(newExpiry),
                        resolveIpLimit(user, null));
            } catch (Exception e) {
                log.warn("setExpiry: failed to sync AccessProvider for telegramId={}, DB updated anyway",
                        telegramId, e);
                notifyOwnerAccessSyncFailureSafely(
                        "setExpiry", telegramId, user.getProviderUsername(), e);
            }
            subscriptionRegistry.mirrorAfterCommit(telegramId);
            log.info("setExpiry for telegramId={} → {}", telegramId, newExpiry);
        });
    }

    /**
     * Отключить доступ. Вызывается планировщиком при истечении срока, а также
     * из refund-flow, /disable админ-команды и подобных.
     * <p>
     * Порядок важен: сначала помечаем DISABLED в БД (commit'ится в конце метода
     * через @Transactional), потом синкаем AccessProvider. Если AccessProvider упал — БД остаётся
     * в консистентном состоянии «неактивна», {@code /status} в боте и admin-card
     * показывают правильно, владелец получает алерт через
     * {@code notifyOwnerAccessSyncFailure} и руками синкает.
     * <p>
     * Раньше порядок был обратный (сначала AccessProvider, потом БД): любой timeout/error
     * AccessProvider оставлял БД в ACTIVE, тогда как туннель в AccessProvider мог быть уже
     * отключён — юзер видел «Активна» в боте, но доступ не работал.
     */
    @Transactional
    public void disableAccess(Long telegramId) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            LocalDateTime expiryBefore = user.getExpiresAt();
            user.setStatus(Customer.Status.DISABLED);
            userRepository.save(user);
            // Отключение НЕ трогает expires_at, поэтому дельта считается по остатку: если
            // отключили досрочно (возврат/админ) — в журнале видно, сколько времени сгорело;
            // если срок вышел сам — остаток уже 0 и дельта нулевая.
            long lost = SubscriptionTime.remainingSeconds(expiryBefore, LocalDateTime.now());
            ledger.record(telegramId, SubscriptionLedger.Kind.EXPIRE, -lost, 0L, null,
                    lost > 0 ? "доступ отключён досрочно" : "срок истёк");
            subscriptionRegistry.mirrorAfterCommit(telegramId);
            try {
                accessProvider.disableUser(user.getProviderUsername());
                log.info("Disabled access for telegram user {}", telegramId);
            } catch (Exception e) {
                log.error("Failed to disable account in AccessProvider for user {} (DB updated to DISABLED anyway)",
                        telegramId, e);
                notifyOwnerAccessSyncFailureSafely(
                        "disableAccess", telegramId, user.getProviderUsername(), e);
            }
        });
    }

    /**
     * Safe-wrapper над notifyOwnerAccessSyncFailure: проглатывает исключения, чтобы
     * неуспех алерта (например, Telegram API временно недоступен) не маскировал
     * исходное исключение AccessProvider-вызова.
     */
    private void notifyOwnerAccessSyncFailureSafely(String context, Long telegramId,
                                                     String providerUsername, Exception cause) {
        try {
            notificationService.notifyOwnerAccessSyncFailure(
                    context, telegramId, providerUsername, cause.getMessage());
        } catch (Exception notifyError) {
            log.warn("Failed to notify owner about AccessProvider sync failure ({})",
                    context, notifyError);
        }
    }

    /**
     * Строит уникальное имя для AccessProvider-юзера. Всегда включает {@code telegramId} в суффикс —
     * без него можно было получить collision: если @bob отказался от username, а потом @bob_2
     * взял его → второй createUser падал бы с 409 в AccessProvider (а у нас в БД provider_username UNIQUE).
     * Telegram username — необязательный человекочитаемый компонент, чтобы admin-карточка
     * читалась легче.
     * <p>
     * Существующие юзеры (созданные до этой правки в формате {@code tg_<username>}) сохраняют
     * своё имя — {@link #activatePaidSubscription} никогда не пересчитывает имя для
     * существующего юзера, читает из БД.
     */
    private String buildProviderUsername(String prefix, Long telegramId, String telegramUsername) {
        if (telegramUsername != null && !telegramUsername.isBlank()) {
            String sanitized = telegramUsername.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            return prefix + "_" + sanitized + "_" + telegramId;
        }
        return prefix + "_" + telegramId;
    }

    public Optional<Customer> getUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }

    /**
     * Текущий used_traffic AccessProvider-юзера в байтах. Возвращает {@code null} если AccessProvider
     * недоступен, юзер не найден, или панель не вернула значение.
     * <p>
     * Сейчас из прод-кода не вызывается: исторически использовался для refund-снапшота
     * (убран вместе с self-service возвратами). Оставлен как переиспользуемый доступ к
     * used_traffic — например, для будущей статистики потребления. Не бросает наружу:
     * failure всегда {@code null}.
     */
    public Long getCurrentUsedTrafficBytes(String providerUsername) {
        if (providerUsername == null || providerUsername.isBlank()) {
            return null;
        }
        try {
            AccessProvider.AccessAccount u = accessProvider.getUser(providerUsername).orElse(null);
            return (u != null) ? u.getUsedTraffic() : null;
        } catch (Exception e) {
            log.warn("Failed to read AccessProvider used_traffic for {}", providerUsername, e);
            return null;
        }
    }

    /**
     * Получить subscription link для пользователя
     */
    public Optional<String> getSubscriptionLink(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .map(Customer::getSubscriptionLink);
    }

    /**
     * Докупленный слот «+1 устройство»: инкрементит {@code device_limit} и синкает AccessProvider
     * {@code ip_limit} (по конвенции devices+1) без изменения срока. Ошибка AccessProvider НЕ
     * фейлит платёж — лимит в БД уже применён (детект устройств работает от него), ip_limit
     * догонит при следующем продлении; владельцу уходит алерт.
     *
     * @return юзер с новым лимитом
     * @throws IllegalStateException если юзера нет
     */
    @Transactional
    public Customer addDeviceSlot(Long telegramId) {
        Customer user = userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден: " + telegramId));
        int newLimit = (user.getDeviceLimit() != null ? user.getDeviceLimit() : 0) + 1;
        user.setDeviceLimit(newLimit);
        userRepository.save(user);

        LocalDateTime now = LocalDateTime.now();
        boolean active = user.getStatus() == Customer.Status.ACTIVE
                && user.getExpiresAt() != null && user.getExpiresAt().isAfter(now);
        if (active) {
            // Срок не меняем — переотправляем тот же момент из БД (enableUserUntil всегда
            // переписывает expire, поэтому округление тут срезало/дарило бы юзеру часы).
            try {
                accessProvider.enableUserUntil(
                        user.getProviderUsername(), toInstant(user.getExpiresAt()), newLimit + 1);
            } catch (Exception e) {
                log.warn("addDeviceSlot: failed to sync AccessProvider ip_limit for telegramId={}, "
                        + "device_limit updated anyway", telegramId, e);
                notifyOwnerAccessSyncFailureSafely(
                        "addDeviceSlot", telegramId, user.getProviderUsername(), e);
            }
        }
        subscriptionRegistry.mirrorAfterCommit(telegramId);
        log.info("Added device slot for telegramId={}: device_limit={}", telegramId, newLimit);
        return user;
    }

    /**
     * «Выйти со всех устройств»: перевыпускает подписочный токен в AccessProvider
     * ({@code revoke_sub}), сохраняет новую ссылку в БД ({@code subscription_token}
     * пересчитается JPA-хуком) и очищает список устройств — все старые ссылки/HWID-записи
     * обнуляются, юзер добавляет новую ссылку на тех устройствах, которые оставляет.
     * Для утечки/перепродажи ссылки: старый URL сразу отвечает 404.
     *
     * @return юзер с обновлённой ссылкой
     * @throws IllegalStateException если юзера нет или AccessProvider не ответил
     */
    @Transactional
    public Customer rotateSubscriptionLink(Long telegramId) {
        return rotateSubscriptionLink(telegramId, currentSubscriptionId(telegramId));
    }

    /**
     * Смена ссылки КОНКРЕТНОЙ подписки.
     * <p>
     * <b>Устройства чистятся только у неё.</b> Раньше чистились все устройства человека
     * ({@code deleteByTelegramId}) — при одной подписке это было одно и то же, а при
     * нескольких смена ссылки одной подписки выкидывала бы из слотов устройства другой,
     * работающей: человек нажал «выйти со всех устройств» на спящей подписке и потерял бы
     * доступ на телефоне, которым пользуется.
     *
     * @param subscriptionId подписка, чью ссылку меняем; {@code null} — работающая сейчас
     */
    @Transactional
    public Customer rotateSubscriptionLink(Long telegramId, Long subscriptionId) {
        Customer user = userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден: " + telegramId));
        Subscription sub = subscriptionId == null ? null
                : subscriptionRepository.findByIdAndTelegramId(subscriptionId, telegramId).orElse(null);
        // Аккаунт панели берём у подписки: у каждой он свой, и отзыв токена не той подписки
        // оборвал бы доступ там, где человек ничего не менял.
        String providerUsername = sub != null && sub.getProviderUsername() != null
                ? sub.getProviderUsername() : user.getProviderUsername();

        AccessProvider.AccessAccount rotated;
        try {
            rotated = accessProvider.revokeSubscription(providerUsername);
        } catch (Exception e) {
            notifyOwnerAccessSyncFailureSafely(
                    "rotateSubscriptionLink/revokeSubscription", telegramId, providerUsername, e);
            throw e;
        }

        String link = withPublicPrefix(rotated.getSubscriptionUrl());
        if (sub != null) {
            sub.setSubscriptionLink(link);
            // У Subscription нет JPA-хука, который держит токен в согласии со ссылкой
            // (у Customer он есть) — проставляем явно, иначе выдача по ссылке не найдёт строку.
            sub.setSubscriptionToken(com.orchestra.subscriptions.util.SubscriptionLinks.extractToken(link));
            subscriptionRepository.save(sub);
        }
        // customers описывает РАБОТАЮЩУЮ подписку — трогаем его только если сменили её ссылку.
        boolean rotatedCurrent = sub == null || sub.isCurrent();
        if (rotatedCurrent) {
            user.setSubscriptionLink(rotated.getSubscriptionUrl());
            // AccessProvider мог вернуть относительный /sub/... — дополняем префиксом, как при createUser.
            fixSubscriptionLinkIfNeeded(user);
            userRepository.save(user);
        }

        // Старый токен мёртв — HWID-записи бессмысленны: устройства с новой ссылкой
        // перерегистрируются сами (и юзер снова получит DM «устройство подключено»).
        if (sub != null) {
            userDeviceRepository.deleteBySubscriptionId(sub.getId());
            // Плюс «ничьи» строки — учёт мог не определить подписку и записать устройство без
            // неё. Слот они не занимают, но остались бы в списке «Мои устройства» после того,
            // как человек нажал «выйти со всех устройств». Чистим только вместе с работающей
            // подпиской: к ней такие строки и относились.
            if (rotatedCurrent) {
                userDeviceRepository.deleteByTelegramIdAndSubscriptionIdIsNull(telegramId);
            }
        } else {
            userDeviceRepository.deleteByTelegramId(telegramId);
        }
        // Ссылка и токен зеркалируются: без этого зеркало навсегда сохранило бы отозванный
        // токен, на который AccessProvider уже отвечает 404, и сверка бы этого не заметила.
        subscriptionRegistry.mirrorAfterCommit(telegramId);
        log.info("Rotated subscription link for telegramId={} (подписка id={})",
                telegramId, sub != null ? sub.getId() : null);
        return user;
    }

    /** Работающая сейчас подписка человека; {@code null} — зеркала ещё нет. */
    private Long currentSubscriptionId(Long telegramId) {
        return subscriptionRepository.findByTelegramIdAndCurrentTrue(telegramId)
                .map(Subscription::getId)
                .orElse(null);
    }

    /** Дополняет относительный {@code /sub/...} публичным префиксом из конфига. */
    private String withPublicPrefix(String link) {
        if (link == null || !link.startsWith("/")) {
            return link;
        }
        String prefix = appConfig.getProvider().getSubscriptionUrlPrefix();
        return prefix != null && !prefix.isBlank() ? prefix + link : link;
    }
}
