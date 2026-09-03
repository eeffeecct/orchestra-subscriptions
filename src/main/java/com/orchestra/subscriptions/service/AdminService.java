package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Фасад для админских команд бота: /stats, /user, /extend, /extend_all, /disable.
 * Все write-операции делегируются в {@link AccessService}, чтобы переиспользовать
 * ту же логику stacking дней и синхронизации с AccessProvider, что и при обычных оплатах.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final AccessService accessService;
    private final AppConfig appConfig;
    private final PendingGrantService pendingGrantService;
    private final AccessProvider accessProvider;
    private final ServerStatusService serverStatusService;

    /**
     * Сколько месяцев когортной истории показывать в /metrics. 6 — компромисс:
     * хватает чтобы увидеть тренд retention'а, но не превращает вывод в простыню.
     */
    private static final int COHORT_MONTHS_DEFAULT = 6;

    /**
     * Собирает снапшот метрик для вывода в /stats.
     * Одним вызовом делает N+1 count'ов (все дешёвые на индексах), плюс один list-запрос
     * по платежам последних 30 дней для агрегации выручки по провайдерам.
     */
    public StatsSnapshot buildStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last7d = now.minusDays(7);
        LocalDateTime last30d = now.minusDays(30);

        long total = userRepository.count();
        long active = userRepository.countByStatusAndExpiresAtAfter(Customer.Status.ACTIVE, now);
        long disabled = userRepository.countByStatus(Customer.Status.DISABLED);
        long trialOnly = userRepository.countByStatusAndProviderUsernameStartingWith(Customer.Status.ACTIVE, "trial_");
        long paidUnique = paymentRepository.countDistinctPayersByStatus(Payment.Status.SUCCEEDED);
        long newIn7d = userRepository.countByCreatedAtAfter(last7d);
        long newIn30d = userRepository.countByCreatedAtAfter(last30d);

        List<Payment> recentSuccessful = paymentRepository.findByStatusAndPaidAtAfter(
                Payment.Status.SUCCEEDED, last30d);
        long revenue30dKopeks = 0;
        Map<Payment.Provider, Long> revenueByProvider = new EnumMap<>(Payment.Provider.class);
        for (Payment p : recentSuccessful) {
            revenue30dKopeks += p.getAmountKopeks();
            Payment.Provider provider = p.getProvider() == null ? Payment.Provider.YOOKASSA : p.getProvider();
            revenueByProvider.merge(provider, p.getAmountKopeks(), Long::sum);
        }

        long trialsTaken = userRepository.countByProviderUsernameStartingWith("trial_");
        long trialsConverted = userRepository.countTrialUsersWhoPaid();
        long paidExpired30d = userRepository.countPaidUsersExpiredBetween(last30d, now);

        return new StatsSnapshot(
                total, active, disabled, trialOnly, paidUnique,
                newIn7d, newIn30d,
                revenue30dKopeks, revenueByProvider,
                recentSuccessful.size(),
                trialsTaken, trialsConverted,
                paidExpired30d
        );
    }

    /**
     * Собирает бизнес-метрики subscription-сервиса для /metrics:
     * MRR, ARPU, churn rate, LTV, trial conversion, plan mix, cohort retention.
     * <p>
     * MRR считается как сумма по всем активным платным юзерам {@code amount * 30 / days_in_plan}
     * по их ПОСЛЕДНЕМУ SUCCEEDED-платежу — то есть учитываем фактический тариф каждого
     * подписчика, а не плоскую цену. Юзер с покупкой 12-мес даёт MRR ≈ 100₽/мес даже если
     * заплатил 1200₽ единоразово.
     * <p>
     * Churn = доля платных подписок, истёкших за последние 30 дней без продления, среди
     * (активная база сейчас + истёкшие). LTV ≈ ARPU / monthly_churn — undefined при нулевом
     * churn'е (показывается как «—»).
     */
    public MetricsSnapshot buildMetrics() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last30d = now.minusDays(30);

        // ---- MRR + распределение по тарифам ----
        List<Long> activePayingIds = userRepository.findActivePayingTelegramIds(now);
        long activePayingCount = activePayingIds.size();

        long mrrKopeks = 0;
        Map<String, Long> activeByPlan = new LinkedHashMap<>();
        // Инициализируем все тарифы из конфига нулями — стабильный порядок вывода и видно
        // тарифы, которые никто не покупает (= кандидаты на удаление/реценишн).
        if (appConfig.getPlans() != null) {
            for (AppConfig.Plan plan : appConfig.getPlans()) {
                activeByPlan.put(plan.getId(), 0L);
            }
        }

        if (!activePayingIds.isEmpty()) {
            // За один запрос вытаскиваем все SUCCEEDED-платежи активных юзеров. Для каждого
            // юзера берём первый встреченный (он самый свежий благодаря ORDER BY paid_at DESC).
            List<Payment> succeededForActive = paymentRepository
                    .findByStatusAndTelegramIdInOrderByPaidAtDesc(Payment.Status.SUCCEEDED, activePayingIds);
            Map<Long, Payment> latestByUser = new HashMap<>();
            for (Payment p : succeededForActive) {
                latestByUser.putIfAbsent(p.getTelegramId(), p);
            }
            for (Payment p : latestByUser.values()) {
                AppConfig.Plan plan = findPlanOrNull(p.getPlanId());
                if (plan == null || plan.getDays() == 0) {
                    // Старый/удалённый тариф — пропускаем из MRR, но всё равно считаем юзера
                    // в общем activePayingCount (он же реально активен платящий).
                    continue;
                }
                mrrKopeks += p.getAmountKopeks() * 30L / plan.getDays();
                activeByPlan.merge(p.getPlanId(), 1L, Long::sum);
            }
        }
        long arpuKopeks = activePayingCount == 0 ? 0L : mrrKopeks / activePayingCount;

        // ---- Churn + LTV ----
        long paidExpired30d = userRepository.countPaidUsersExpiredBetween(last30d, now);
        long churnDenominator = paidExpired30d + activePayingCount;
        double churnRate30dPct = churnDenominator == 0 ? 0.0 : 100.0 * paidExpired30d / churnDenominator;

        long ltvKopeks = 0L;
        boolean ltvDefined = false;
        if (paidExpired30d > 0 && churnRate30dPct > 0 && arpuKopeks > 0) {
            // LTV = ARPU / monthly_churn. Если churn 0 — формально LTV → ∞, но это значит
            // «недостаточно данных», показываем undefined, а не астрономическое число.
            ltvKopeks = (long) (arpuKopeks / (churnRate30dPct / 100.0));
            ltvDefined = true;
        }

        // ---- Trial → платный конверсия ----
        long trialsTaken = userRepository.countByProviderUsernameStartingWith("trial_");
        long trialsConverted = userRepository.countTrialUsersWhoPaid();
        double trialConversionPct = trialsTaken == 0 ? 0.0 : 100.0 * trialsConverted / trialsTaken;

        // ---- Cohort retention ----
        List<CohortRow> cohorts = computeCohorts(now, COHORT_MONTHS_DEFAULT, new HashSet<>(activePayingIds));

        return new MetricsSnapshot(
                mrrKopeks,
                activePayingCount,
                arpuKopeks,
                churnRate30dPct,
                paidExpired30d,
                ltvKopeks,
                ltvDefined,
                trialsTaken,
                trialsConverted,
                trialConversionPct,
                activeByPlan,
                cohorts);
    }

    /**
     * Когортный retention по месяцу первой SUCCEEDED-оплаты. Для каждого месяца из последних
     * {@code monthsBack} считаем размер когорты (юзеры с первой оплатой в этом месяце) и долю
     * тех, кто ВСЁ ЕЩЁ платит сейчас (есть в {@code activePayingIds}).
     */
    private List<CohortRow> computeCohorts(LocalDateTime now, int monthsBack, Set<Long> activePayingIds) {
        List<Object[]> firstPayments = paymentRepository.findFirstSuccessfulPaymentDatePerUser();
        Map<YearMonth, List<Long>> cohortsByMonth = new HashMap<>();
        for (Object[] row : firstPayments) {
            Long telegramId = (Long) row[0];
            LocalDateTime firstPaidAt = (LocalDateTime) row[1];
            if (firstPaidAt == null) continue;
            YearMonth month = YearMonth.from(firstPaidAt);
            cohortsByMonth.computeIfAbsent(month, k -> new ArrayList<>()).add(telegramId);
        }

        YearMonth currentMonth = YearMonth.from(now);
        List<CohortRow> rows = new ArrayList<>(monthsBack);
        // От старого к новому — юзер читает сверху вниз и видит «как менялся retention»
        for (int i = monthsBack - 1; i >= 0; i--) {
            YearMonth m = currentMonth.minusMonths(i);
            List<Long> cohort = cohortsByMonth.getOrDefault(m, List.of());
            long stillPaying = cohort.stream().filter(activePayingIds::contains).count();
            double retention = cohort.isEmpty() ? 0.0 : 100.0 * stillPaying / cohort.size();
            rows.add(new CohortRow(m.toString(), cohort.size(), stillPaying, retention));
        }
        return rows;
    }

    private AppConfig.Plan findPlanOrNull(String planId) {
        if (appConfig.getPlans() == null || planId == null) return null;
        return appConfig.getPlans().stream()
                .filter(p -> planId.equals(p.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Карточка юзера + последние 5 платежей для /user {telegramId}.
     * Возвращает пустое значение, если юзера нет в БД.
     */
    public Optional<UserCard> findUser(Long telegramId) {
        Optional<Customer> user = userRepository.findByTelegramId(telegramId);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        List<Payment> recentPayments = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(telegramId)
                .stream()
                .limit(5)
                .toList();
        return Optional.of(new UserCard(user.get(), recentPayments));
    }

    /**
     * Продлевает подписку на указанное число дней через тот же путь, что и платная оплата.
     * {@link AccessService#activatePaidSubscription} уже умеет правильно stack'ать дни поверх
     * существующего остатка, синхронизировать AccessProvider и оживлять DISABLED-юзеров.
     *
     * @throws IllegalArgumentException если юзер не найден в БД
     */
    public Customer extendSubscription(Long telegramId, int days) {
        Customer existing = userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + telegramId));
        log.info("Admin extending subscription for telegramId={} by {} days", telegramId, days);
        return accessService.addDays(telegramId, existing.getTelegramUsername(), days,
                com.orchestra.subscriptions.model.SubscriptionLedger.Kind.ADMIN_EXTEND);
    }

    /**
     * Удобный wrapper для админ-команды: принимает идентификатор в любой из форм:
     * <ul>
     *   <li>числовой telegramId — {@code 100200300}</li>
     *   <li>username с @ — {@code @eeffeecct}</li>
     *   <li>username без @ — {@code eeffeecct}</li>
     * </ul>
     * Резолвит через {@link CustomerRepository#findByTelegramUsernameIgnoreCase} для
     * username-форм. Юзер должен УЖЕ быть в БД (хотя бы раз открыл бот) — Telegram Bot
     * API не даёт ботам резолвить незнакомые @username в id.
     *
     * @throws IllegalArgumentException если идентификатор пустой или юзер не найден
     */
    public Customer extendSubscriptionBySubject(String identifier, int days) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Пустой идентификатор");
        }
        String trimmed = identifier.trim();
        // Пытаемся как число — самый частый случай для админа
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return extendSubscription(Long.parseLong(trimmed), days);
        }
        // Иначе — username (с @ или без)
        String username = stripAt(trimmed);
        Customer existing = userRepository.findByTelegramUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Пользователь не найден: @" + username
                        + " (юзер должен хотя бы раз открыть бот)"));
        return extendSubscription(existing.getTelegramId(), days);
    }

    /** Срезает ведущий «@» у Telegram-username, если он есть. */
    private static String stripAt(String username) {
        return username.startsWith("@") ? username.substring(1) : username;
    }

    /**
     * Резолв «идентификатор из админ-команды → юзер»: число = telegramId, иначе username
     * (с {@code @} или без). Общий кусок {@link #extendSubscriptionBySubject}, вынесен для
     * read-only команд ({@code /devices}), которым исключение на «не найден» не нужно.
     */
    public Optional<Customer> findUserBySubject(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        String trimmed = identifier.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return userRepository.findByTelegramId(Long.parseLong(trimmed));
        }
        return userRepository.findByTelegramUsernameIgnoreCase(stripAt(trimmed));
    }

    /**
     * Выдаёт подарочные дни победителю конкурса. Универсальная замена {@code /extend}
     * для админов: умеет работать даже если юзер ещё не в БД.
     * <ul>
     *   <li>Если юзер найден по id/username — мгновенно продлевает (как {@link #extendSubscriptionBySubject}).</li>
     *   <li>Если юзер не найден И идентификатор — это username (не numeric) — создаёт
     *       отложенный {@code PendingGrant}. Активация произойдёт автоматически при
     *       первом /start или открытии Mini App ({@code PendingGrantService.applyIfAny}).</li>
     *   <li>Если юзер не найден И идентификатор — numeric ID — кидает исключение.
     *       Резолвить незнакомый numeric ID без участия юзера нельзя; админ скорее
     *       всего опечатался.</li>
     * </ul>
     *
     * @param actorTelegramId id админа (для аудита в pending_grants.granted_by_telegram_id)
     */
    public GrantResult grantSubscription(String identifier, int days, Long actorTelegramId) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Пустой идентификатор");
        }
        String trimmed = identifier.trim();
        boolean isNumeric = trimmed.chars().allMatch(Character::isDigit);
        try {
            Customer user = extendSubscriptionBySubject(trimmed, days);
            return GrantResult.applied(user.getTelegramId(), user.getExpiresAt());
        } catch (IllegalArgumentException notFound) {
            if (isNumeric) {
                // Numeric id, но юзера нет — нельзя зарезервировать (мы не знаем username,
                // на который привязать). Пробрасываем исходную ошибку.
                throw notFound;
            }
            // Username, юзера нет — создаём отложенный грант. Username чистится от @ внутри createGrant.
            String username = stripAt(trimmed);
            pendingGrantService.createGrant(username, days, actorTelegramId);
            return GrantResult.pending(username);
        }
    }

    /**
     * Результат {@link #grantSubscription}. Либо {@code applied} (юзер был в БД,
     * extend выполнен прямо сейчас) — тогда заполнены telegramId+expiresAt; либо
     * {@code pending} (юзера нет, грант зарезервирован) — тогда заполнен username.
     */
    public record GrantResult(boolean applied, Long telegramId, LocalDateTime expiresAt, String pendingUsername) {
        public static GrantResult applied(Long telegramId, LocalDateTime expiresAt) {
            return new GrantResult(true, telegramId, expiresAt, null);
        }
        public static GrantResult pending(String username) {
            return new GrantResult(false, null, null, username);
        }
    }

    /**
     * Массово продлевает всех активных платящих юзеров на N дней. Используется для
     * выдачи компенсации после инцидента (например, +3 дня всем за downtime).
     * <p>
     * Идёт по тому же пути, что и единичный {@link #extendSubscription} — для каждого
     * юзера {@link AccessService#activatePaidSubscription} стекает дни поверх остатка
     * и синхронизирует AccessProvider. Поэтому БД и AccessProvider остаются консистентными.
     * <p>
     * Не падает на единичных ошибках: каждый extend в своём try-catch, собирает
     * счётчики, возвращает результат. Если один юзер не нашёлся в AccessProvider — остальные
     * продлятся как обычно.
     * <p>
     * Внимание: выполняется последовательно (HTTP roundtrip в AccessProvider на каждого).
     * При базе в 1k+ может занять минуты — handler в боте должен предупредить админа.
     */
    public BulkExtendResult extendAllActivePayingUsers(int days) {
        if (days <= 0 || days > 3650) {
            throw new IllegalArgumentException("Дни должны быть в диапазоне 1..3650");
        }
        List<Long> ids = userRepository.findActivePayingTelegramIds(LocalDateTime.now());
        log.info("Bulk extend: starting for {} active paying users, +{} days each", ids.size(), days);
        int succeeded = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                extendSubscription(id, days);
                succeeded++;
            } catch (Exception e) {
                failed++;
                log.error("Bulk extend failed for telegramId={}: {}", id, e.getMessage());
            }
        }
        log.info("Bulk extend: done — total={} succeeded={} failed={}", ids.size(), succeeded, failed);
        return new BulkExtendResult(ids.size(), succeeded, failed);
    }

    /**
     * Сколько активных платящих юзеров СЕЙЧАС — для preview в {@code /extend_all} перед CONFIRM.
     * Чтобы админ видел, скольким юзерам уйдёт продление, до того как нажмёт «выполнить».
     */
    public int countActivePayingUsers() {
        return userRepository.findActivePayingTelegramIds(LocalDateTime.now()).size();
    }

    /** Результат массового продления для отчёта в {@code /extend_all}. */
    public record BulkExtendResult(int total, int succeeded, int failed) {}

    /**
     * Отключает доступ юзера (AccessProvider + БД). No-op если юзера нет.
     * Возвращает true, если что-то отключили (юзер существовал).
     */
    public boolean disableSubscription(Long telegramId) {
        Optional<Customer> existing = userRepository.findByTelegramId(telegramId);
        if (existing.isEmpty()) {
            return false;
        }
        log.info("Admin disabling subscription for telegramId={}", telegramId);
        accessService.disableAccess(telegramId);
        return true;
    }

    /**
     * Снапшот состояния флота для owner-команды {@code /nodes}: то, что видит панель AccessProvider
     * ({@code connected/connecting/error/disabled}), <b>плюс</b> прямая TCP-проба {@code :443}
     * на адрес каждой ноды. Смысл — поймать расхождение «панель считает ноду connected, а :443
     * снаружи закрыт»: это классический признак per-IP DPI на маршруте до ноды (ICMP чист,
     * SYN дропается), при котором {@code diag-node.sh}/панель ещё «зелёные», а юзеры уже не
     * подключаются. Дерево решений — {@code docs/node-runbook.md} Часть 2.
     * <p>
     * Пустой список = панель недоступна ИЛИ нод нет (см. {@link AccessProvider#getNodes()}) —
     * хендлер покажет соответствующее сообщение.
     */
    public List<NodeStatus> getNodeStatuses() {
        List<AccessProvider.AccessNode> nodes = accessProvider.getNodes();
        List<NodeStatus> result = new ArrayList<>(nodes.size());
        for (AccessProvider.AccessNode n : nodes) {
            boolean port443Open = n.getAddress() != null
                    && serverStatusService.isPortOpen(n.getAddress(), 443);
            result.add(new NodeStatus(
                    n.getName(), n.getStatus(), n.getAddress(), port443Open, n.getMessage(),
                    n.getCoreVersion()));
        }
        return result;
    }

    /**
     * Строка вывода {@code /nodes}: имя ноды (с флагом-эмодзи, как в панели), статус глазами
     * панели, адрес, результат внешней TCP-пробы {@code :443} и последнее сообщение панели.
     */
    public record NodeStatus(
            String name,
            String status,
            String address,
            boolean port443Open,
            String message,
            /**
             * Версия ядра на узле глазами провайдера. Отдельная от статуса величина, и
             * диагностически она не менее важна: отставшее ядро на узле ломает подключение для
             * свежих клиентов при полностью «зелёной» ноде — панель показывает connected,
             * :443 открыт, а соединение не встаёт. {@code null} — панель версию не отдала.
             */
            String coreVersion
    ) {}

    /**
     * Снапшот метрик бота. Все денежные поля — в копейках (рублёвый эквивалент
     * для Stars-платежей тоже хранится в копейках в Payment.amountKopeks).
     */
    public record StatsSnapshot(
            long totalUsers,
            long activeUsers,
            long disabledUsers,
            long trialOnlyUsers,
            long paidUniqueUsers,
            long newUsersLast7d,
            long newUsersLast30d,
            long revenue30dKopeks,
            Map<Payment.Provider, Long> revenue30dByProviderKopeks,
            long successfulPayments30d,
            long trialsTakenAllTime,
            long trialsConvertedAllTime,
            long paidUsersExpiredLast30d
    ) {
        /** Доля юзеров с триалом, дошедших до оплаты. 0 если триалов ещё не было. */
        public double trialConversionPct() {
            return trialsTakenAllTime == 0 ? 0.0 :
                    100.0 * trialsConvertedAllTime / trialsTakenAllTime;
        }

        /** Средний чек за 30д в копейках. 0 если платежей не было. */
        public long averagePaymentKopeks30d() {
            return successfulPayments30d == 0 ? 0 : revenue30dKopeks / successfulPayments30d;
        }
    }

    /** Карточка юзера для /user. */
    public record UserCard(Customer user, List<Payment> recentPayments) {}

    /**
     * Снапшот subscription-метрик для /metrics. Все денежные поля — в копейках, нормализованные
     * к месячному эквиваленту (MRR, ARPU, LTV).
     */
    public record MetricsSnapshot(
            /** Monthly Recurring Revenue в копейках. Сумма по активным платным юзерам:
             *  amount_kopeks их последнего SUCCEEDED-платежа × 30 / days_in_plan. */
            long mrrKopeks,
            /** Юзеры status=ACTIVE с не-истёкшим expires_at, у которых хотя бы одна
             *  SUCCEEDED-оплата в истории (исключая чисто-триальных). */
            long activePayingUsers,
            /** Average Revenue Per User в копейках/месяц = mrr / activePaying. */
            long arpuKopeks,
            /** Доля платных подписок, истёкших за 30 дней без продления, в процентах
             *  (0..100). Знаменатель — активная платная база + те же истёкшие. */
            double churnRate30dPct,
            /** Сколько платных подписок истекло в последние 30 дней (числитель churn'а). */
            long paidUsersExpiredLast30d,
            /** Lifetime Value в копейках = ARPU / monthly_churn. 0 если {@link #ltvDefined} = false. */
            long ltvKopeks,
            /** Можно ли посчитать LTV. False, если churn=0 или нет платных подписчиков —
             *  в этих случаях знаменатель формулы вырождается. */
            boolean ltvDefined,
            long trialsTakenAllTime,
            long trialsConvertedAllTime,
            double trialConversionPct,
            /** Активные платные подписки по планам (plan_id → count). Все планы из конфига
             *  представлены, даже если 0 — чтобы пустые тарифы тоже было видно. */
            Map<String, Long> activePayingByPlan,
            /** Когорты последних 6 месяцев, от старых к новым. */
            List<CohortRow> cohorts
    ) {}

    /**
     * Когорта = группа юзеров, впервые оплативших в одном месяце. Retention% — доля,
     * которая ВСЁ ЕЩЁ платит сейчас (status=ACTIVE + есть SUCCEEDED-история + не истекла).
     */
    public record CohortRow(
            /** ISO формат YYYY-MM, например "2026-04". */
            String monthLabel,
            /** Сколько юзеров впервые заплатили в этом месяце. */
            long cohortSize,
            /** Сколько из них всё ещё платят сейчас. */
            long stillPayingNow,
            /** stillPayingNow / cohortSize × 100, в процентах. 0 если cohortSize=0. */
            double retentionPct
    ) {}
}
