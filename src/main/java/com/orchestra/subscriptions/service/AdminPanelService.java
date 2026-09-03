package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.MagicLink;
import com.orchestra.subscriptions.model.OfferAcceptance;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.PendingGrant;
import com.orchestra.subscriptions.model.PromoCode;
import com.orchestra.subscriptions.model.PromoUsage;
import com.orchestra.subscriptions.model.Referral;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.MagicLinkRepository;
import com.orchestra.subscriptions.repository.OfferAcceptanceRepository;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.PendingGrantRepository;
import com.orchestra.subscriptions.repository.PromoCodeRepository;
import com.orchestra.subscriptions.repository.PromoUsageRepository;
import com.orchestra.subscriptions.repository.ReferralRepository;
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Данные для веб-админки: постраничные срезы таблиц и карточка человека.
 * <p>
 * <b>Только чтение.</b> Ни один метод здесь ничего не меняет — ни в БД, ни в панели AccessProvider,
 * ни в Telegram. Действия над живыми людьми (продлить, отключить, выдать, разослать) остаются
 * в боте, где они уже обвешаны подтверждениями и логами. Панель показывает состояние, и это
 * граница, которую стоит держать: случайный клик в таблице на 500 строк не должен уметь
 * ничего испортить.
 * <p>
 * <b>Откуда «последний раз в сети».</b> Единственный персистентный след активности —
 * {@code user_devices.last_seen_at}, а это <i>момент последнего обновления подписки клиентом</i>,
 * округлённый шагом в час, и только для клиентов, присылающих {@code x-hwid} (клиентское приложение и
 * совместимые). Это НЕ «последний раз пользовался»: человек может неделю жить на закешированном
 * конфиге. Поле и на экране называется соответствующе — иначе цифра читается как ложь.
 * Мгновенный онлайн есть только по узлам, которые спрашивают авторизацию, ({@link OnlineTracker}, память, окно 10 минут).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPanelService {

    /** Потолок размера страницы: защита от {@code ?size=100000} и от выгрузки всей базы в JSON. */
    public static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionLedgerRepository ledgerRepository;
    private final UserDeviceRepository deviceRepository;
    private final ReferralRepository referralRepository;
    private final PromoCodeRepository promoCodeRepository;
    private final PromoUsageRepository promoUsageRepository;
    private final PendingGrantRepository pendingGrantRepository;
    private final MagicLinkRepository magicLinkRepository;
    private final OfferAcceptanceRepository offerAcceptanceRepository;
    private final AdminService adminService;
    private final OnlineTracker onlineTracker;
    private final ServerStatusService serverStatusService;
    private final com.orchestra.subscriptions.access.AccessProvider accessProvider;

    // =====================================================================================
    // Обзор
    // =====================================================================================

    /**
     * Верхний экран: те же цифры, что у {@code /stats} и {@code /metrics} в боте (один и тот же
     * {@link AdminService}, чтобы бот и панель не разошлись в определениях), плюс размеры таблиц
     * и текущий онлайн.
     */
    @Transactional(readOnly = true)
    public Overview overview() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> tables = new LinkedHashMap<>();
        tables.put("customers", userRepository.count());
        tables.put("subscriptions", subscriptionRepository.count());
        tables.put("payments", paymentRepository.count());
        tables.put("subscription_ledger", ledgerRepository.count());
        tables.put("user_devices", deviceRepository.count());
        tables.put("referrals", referralRepository.count());
        tables.put("promo_codes", promoCodeRepository.count());
        tables.put("promo_usages", promoUsageRepository.count());
        tables.put("pending_grants", pendingGrantRepository.count());
        tables.put("magic_links", magicLinkRepository.count());
        tables.put("offer_acceptances", offerAcceptanceRepository.count());

        List<OnlineTracker.NodeSession> nodeSessions = onlineTracker.recentNodeSessions();
        OnlineTracker.RelaySnapshot relay = onlineTracker.relaySnapshot();

        return new Overview(
                now,
                adminService.buildStats(),
                adminService.buildMetrics(),
                tables,
                nodeSessions.stream()
                        .map(s -> new OnlineRow(s.username(), s.nodeIp(),
                                LocalDateTime.ofInstant(s.at(), java.time.ZoneOffset.UTC)))
                        .toList(),
                relay == null ? null : new RelayRow(
                        relay.connectionsByPort(), relay.uniqueClients(),
                        LocalDateTime.ofInstant(relay.at(), java.time.ZoneOffset.UTC)),
                deviceRepository.countByLastSeenAtAfter(now.minusDays(1)),
                deviceRepository.countByLastSeenAtAfter(now.minusDays(7)),
                deviceRepository.countByLastSeenAtAfter(now.minusDays(30)));
    }

    public record Overview(
            LocalDateTime generatedAt,
            AdminService.StatsSnapshot stats,
            AdminService.MetricsSnapshot metrics,
            /** Имя таблицы → число строк. «Слепок БД» одним взглядом. */
            Map<String, Long> tableRows,
            /** Живые подключения за последние 10 минут (только память, только hy2-ноды). */
            List<OnlineRow> nodeOnline,
            /** Последний отчёт российского входа. {@code null} — крон ещё не присылал. */
            RelayRow relay,
            /** Устройств, обновлявших подписку за сутки / неделю / месяц. */
            long devicesSeen24h,
            long devicesSeen7d,
            long devicesSeen30d) {}

    public record OnlineRow(String providerUsername, String nodeIp, LocalDateTime at) {}

    public record RelayRow(Map<String, Integer> connectionsByPort, int uniqueClients, LocalDateTime at) {}

    // =====================================================================================
    // Ноды
    // =====================================================================================

    /**
     * Экран «Ноды»: три независимых взгляда на один и тот же флот, сведённые в одну картину.
     * <ol>
     *   <li><b>Панель AccessProvider</b> — что она сама думает о ноде ({@code connected} и версия ядра);</li>
     *   <li><b>Прямая TCP-проба {@code :443}</b> с main — открыт ли порт снаружи;</li>
     *   <li><b>Мониторинг</b> (Uptime Kuma) — uptime за сутки и задержка.</li>
     * </ol>
     * Ценность именно в расхождениях между ними, а не в самих статусах. Панель считает ноду
     * {@code connected}, а {@code :443} снаружи закрыт — это классический per-IP DPI на
     * маршруте, и увидеть его можно только сравнив два источника. Определения общие с
     * командой {@code /nodes}: обе ходят в {@link AdminService#getNodeStatuses()}, чтобы бот
     * и панель не начали считать «здоровую ноду» по-разному.
     * <p>
     * <b>Мониторинг не сводится с панелью по строкам намеренно.</b> Общего ключа нет: в
     * AccessProvider нода зовётся так, как её назвали в панели, в Kuma — так, как назвали монитор.
     * Склейка «по похожему имени» тихо соврала бы на первой же переименованной ноде, поэтому
     * мониторинг отдаётся отдельным списком.
     * <p>
     * <b>Метод ходит наружу и небыстрый:</b> один HTTP к панели плюс по TCP-пробе на ноду
     * (таймаут 2 с каждая, последовательно). Кеша нет: экран открывают, когда что-то уже
     * сломалось, и ответ двадцатисекундной давности в этот момент вреднее ожидания.
     */
    public NodesView nodes() {
        List<AdminService.NodeStatus> panel;
        try {
            panel = adminService.getNodeStatuses();
        } catch (Exception e) {
            // Панель недоступна — это само по себе то, что владелец пришёл сюда узнать.
            // Пустой список + пометка честнее, чем 500 в пустой экран.
            log.warn("Admin panel: не смог опросить ноды AccessProvider: {}", e.toString());
            panel = List.of();
        }

        Map<String, Integer> onlineByIp = new HashMap<>();
        for (OnlineTracker.NodeSession s : onlineTracker.recentNodeSessions()) {
            if (s.nodeIp() != null) {
                onlineByIp.merge(s.nodeIp(), 1, Integer::sum);
            }
        }

        List<NodeRow> rows = new ArrayList<>(panel.size());
        int dpiSuspects = 0;
        int connected = 0;
        for (AdminService.NodeStatus n : panel) {
            boolean isConnected = "connected".equalsIgnoreCase(n.status() == null ? "" : n.status());
            boolean suspect = isConnected && !n.port443Open();
            if (isConnected) {
                connected++;
            }
            if (suspect) {
                dpiSuspects++;
            }
            rows.add(new NodeRow(n.name(), n.status(), n.address(), n.port443Open(), n.message(),
                    n.coreVersion(), suspect,
                    n.address() == null ? 0 : onlineByIp.getOrDefault(n.address(), 0)));
        }

        List<MonitorRow> monitors = new ArrayList<>();
        for (ServerStatusService.ServerStatus s : serverStatusService.getStatuses()) {
            // known=false — «мониторинг не ответил», и это НЕ «нода лежит». Разница обязана
            // дожить до экрана, поэтому up здесь тройственный: true / false / null.
            monitors.add(new MonitorRow(s.name(), s.flag(), s.known() ? s.online() : null,
                    s.uptime24(), s.pingMs()));
        }

        return new NodesView(LocalDateTime.now(), rows, monitors, panel.size(), connected, dpiSuspects);
    }

    /**
     * @param nodes       ноды глазами панели AccessProvider, в порядке её ответа
     * @param monitors    независимый список мониторинга; со строками {@code nodes} не сводится
     * @param dpiSuspects сколько нод «панель connected, а :443 закрыт» — то, ради чего экран
     */
    public record NodesView(
            LocalDateTime generatedAt,
            List<NodeRow> nodes,
            List<MonitorRow> monitors,
            int total,
            int connected,
            int dpiSuspects) {}

    /**
     * @param dpiSuspect    панель считает ноду живой, но порт снаружи закрыт
     * @param nodeOnline подключений к этой ноде за 10 минут; только по таким узлам и только из
     *                       памяти приложения — у узлов вне контура такого следа нет, там всегда 0
     */
    public record NodeRow(
            String name,
            String status,
            String address,
            boolean port443Open,
            String message,
            String coreVersion,
            boolean dpiSuspect,
            int nodeOnline) {}

    /** @param up {@code null} — мониторинг не ответил, состояние неизвестно (не «лежит»). */
    public record MonitorRow(String name, String flag, Boolean up, Double uptime24Pct, Double pingMs) {}

    // =====================================================================================
    // Люди
    // =====================================================================================

    /** Допустимые поля сортировки списка людей: имя поля приходит из URL и уезжает в ORDER BY. */
    private static final Set<String> USER_SORTS =
            Set.of("createdAt", "expiresAt", "telegramId", "telegramUsername", "email", "status");

    @Transactional(readOnly = true)
    public PageView<UserRow> users(String query, String mode, String sort, String dir, int page, int size) {
        Pageable pageable = pageable(page, size, sort, dir, USER_SORTS, "createdAt");
        Page<Customer> found = userRepository.adminSearch(
                like(query), normalizeMode(mode), LocalDateTime.now(), pageable);

        List<Long> ids = found.getContent().stream().map(Customer::getTelegramId).toList();
        Map<Long, PayAgg> pay = payAggregates(ids);
        Map<Long, DeviceAgg> devices = deviceAggregates(ids);
        // Пустой список в IN не отдаём ни одному из трёх агрегатов: страница без строк —
        // штатный ответ поиска, а не край, и запрос с пустым IN тут не нужен вовсе.
        Map<Long, Long> subs = ids.isEmpty()
                ? Map.of()
                : countByTelegramId(subscriptionRepository.countByTelegramIdIn(ids));
        Set<String> online = onlineProviderUsernames();
        LocalDateTime now = LocalDateTime.now();

        List<UserRow> rows = found.getContent().stream().map(u -> {
            PayAgg p = pay.getOrDefault(u.getTelegramId(), PayAgg.EMPTY);
            DeviceAgg d = devices.getOrDefault(u.getTelegramId(), DeviceAgg.EMPTY);
            return new UserRow(
                    u.getTelegramId(),
                    u.getTelegramUsername(),
                    u.getProviderUsername(),
                    u.getEmail(),
                    u.getEmailVerifiedAt() != null,
                    u.getStatus() == null ? null : u.getStatus().name(),
                    u.getExpiresAt(),
                    daysLeft(u.getExpiresAt(), now),
                    u.getCreatedAt(),
                    u.getTrialUsedAt(),
                    u.getDeviceTierId(),
                    u.getDeviceLimit(),
                    d.count(),
                    d.lastSeenAt(),
                    u.isAutorenewEnabled(),
                    u.getAutorenewMethodId() != null,
                    u.getAutorenewPlanId(),
                    p.count(),
                    p.sumKopeks(),
                    p.lastPaidAt(),
                    subs.getOrDefault(u.getTelegramId(), 0L),
                    u.getProviderUsername() != null && online.contains(u.getProviderUsername()));
        }).toList();

        return PageView.of(found, rows);
    }

    public record UserRow(
            Long telegramId,
            String telegramUsername,
            String providerUsername,
            String email,
            boolean emailVerified,
            String status,
            LocalDateTime expiresAt,
            /** Сколько суток осталось; отрицательное — столько прошло с истечения. {@code null} — срока нет. */
            Long daysLeft,
            LocalDateTime createdAt,
            LocalDateTime trialUsedAt,
            String deviceTierId,
            Integer deviceLimit,
            long devices,
            /** Момент последнего обновления подписки клиентом — НЕ последний трафик. */
            LocalDateTime lastSeenAt,
            boolean autorenewEnabled,
            boolean cardBound,
            String autorenewPlanId,
            long paymentsCount,
            long paidKopeks,
            LocalDateTime lastPaidAt,
            long subscriptions,
            /** Есть коннект к узлу за последние 10 минут. */
            boolean onlineNow) {}

    /**
     * Карточка человека: всё, что о нём есть в базе, в одном ответе. Это ровно тот срез, ради
     * которого админка и заводится — не бегать по семи таблицам глазами.
     */
    @Transactional(readOnly = true)
    public Optional<UserCard> userCard(long telegramId) {
        Optional<Customer> found = userRepository.findByTelegramId(telegramId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Customer u = found.get();
        Optional<Referral> invitedBy = referralRepository.findByReferredTelegramId(telegramId);
        // Имена нужны только для двух id: самого человека и того, кто его привёл.
        Map<Long, String> names = usernames(invitedBy
                .map(r -> List.of(telegramId, r.getReferrerTelegramId()))
                .orElse(List.of(telegramId)));
        List<Payment> payments = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(telegramId);
        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId);
        List<UserDevice> devices = deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(telegramId);
        List<SubscriptionLedger> ledger = ledgerRepository.findByTelegramIdOrderByOccurredAtDesc(
                telegramId, PageRequest.of(0, 100));
        Map<Long, Integer> seqById = subs.stream()
                .collect(Collectors.toMap(Subscription::getId, Subscription::getSeq, (a, b) -> a));
        Set<String> online = onlineProviderUsernames();

        return Optional.of(new UserCard(
                userRow(u, payments, devices, subs.size(), online),
                u.getSubscriptionLink(),
                u.getSessionVersion(),
                u.getEmailConsentAt(),
                u.getEmailVerifiedAt(),
                u.getAutorenewLastFailureAt(),
                u.isNotified24h(),
                u.isNotified1h(),
                u.isWinbackSent(),
                subs.stream().map(s -> subscriptionRow(s, names)).toList(),
                payments.stream().map(p -> paymentRow(p, names)).toList(),
                devices.stream().map(d -> deviceRow(d, names)).toList(),
                ledger.stream().map(l -> ledgerRow(l, seqById)).toList(),
                invitedBy.map(r -> referralRow(r, names)).orElse(null),
                referralRepository.countByReferrerTelegramId(telegramId),
                referralRepository.countByReferrerTelegramIdAndPaidAtNotNull(telegramId)));
    }

    public record UserCard(
            UserRow user,
            String subscriptionLink,
            Integer sessionVersion,
            LocalDateTime emailConsentAt,
            LocalDateTime emailVerifiedAt,
            LocalDateTime autorenewLastFailureAt,
            boolean notified24h,
            boolean notified1h,
            boolean winbackSent,
            List<SubscriptionRow> subscriptions,
            List<PaymentRow> payments,
            List<DeviceRow> devices,
            /** Последние 100 записей журнала секунд. */
            List<LedgerRow> ledger,
            /** Кто пригласил этого человека. {@code null} — пришёл сам. */
            ReferralRow invitedBy,
            long invitedCount,
            long invitedPaidCount) {}

    private UserRow userRow(Customer u, List<Payment> payments, List<UserDevice> devices,
                            int subscriptions, Set<String> online) {
        long count = 0;
        long sum = 0;
        LocalDateTime lastPaid = null;
        for (Payment p : payments) {
            if (p.getStatus() != Payment.Status.SUCCEEDED) continue;
            count++;
            sum += p.getAmountKopeks();
            if (p.getPaidAt() != null && (lastPaid == null || p.getPaidAt().isAfter(lastPaid))) {
                lastPaid = p.getPaidAt();
            }
        }
        LocalDateTime lastSeen = devices.stream()
                .map(UserDevice::getLastSeenAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        return new UserRow(
                u.getTelegramId(), u.getTelegramUsername(), u.getProviderUsername(), u.getEmail(),
                u.getEmailVerifiedAt() != null,
                u.getStatus() == null ? null : u.getStatus().name(),
                u.getExpiresAt(), daysLeft(u.getExpiresAt(), LocalDateTime.now()),
                u.getCreatedAt(), u.getTrialUsedAt(), u.getDeviceTierId(), u.getDeviceLimit(),
                devices.size(), lastSeen,
                u.isAutorenewEnabled(), u.getAutorenewMethodId() != null, u.getAutorenewPlanId(),
                count, sum, lastPaid, subscriptions,
                u.getProviderUsername() != null && online.contains(u.getProviderUsername()));
    }

    /**
     * Что о человеке знает панель AccessProvider: статус аккаунта, трафик и — главное —
     * {@code online_at}, единственный источник «был в сети» по узловам. В нашей базе
     * такого следа нет: {@code user_devices.last_seen_at} — это обновление подписки клиентом,
     * а не соединение.
     * <p>
     * Отдельным методом и отдельным эндпоинтом, а не полем карточки, по двум причинам:
     * <ul>
     *   <li>это <b>HTTP наружу</b>, и делать его внутри {@code @Transactional} значило бы
     *       держать соединение к БД на время таймаута панели (до 8 секунд);</li>
     *   <li>панель может лежать, и карточка человека не должна из-за этого не открыться.</li>
     * </ul>
     * ⚠️ {@code AccessProvider.getUser} отвечает пустотой и на «нет такого аккаунта», и на любую
     * ошибку связи — различить нельзя, поэтому {@link PanelView#available()} = false означает
     * ровно «панель не ответила ИЛИ аккаунта в ней нет», и на экране написано так же.
     */
    public PanelView panelViewFor(long telegramId) {
        String providerUsername = userRepository.findByTelegramId(telegramId)
                .map(Customer::getProviderUsername)
                .orElse(null);
        if (providerUsername == null || providerUsername.isBlank()) {
            return new PanelView(false, null, null, null, null, null, null, null, null);
        }
        return accessProvider.getUser(providerUsername)
                .map(u -> new PanelView(true, u.getUsername(), u.getStatus(), u.getOnlineAt(),
                        u.getSubUpdatedAt(), u.getSubLastUserAgent(), u.getUsedTraffic(),
                        u.getDataLimit(), u.getExpire()))
                .orElseGet(() -> new PanelView(false, providerUsername, null, null, null, null, null, null, null));
    }

    public record PanelView(
            /** false = панель не ответила ИЛИ аккаунта в ней нет; различить невозможно. */
            boolean available,
            String username,
            String status,
            /** Момент последнего соединения глазами панели, строкой как её отдаёт AccessProvider. */
            String onlineAt,
            String subUpdatedAt,
            String subLastUserAgent,
            Long usedTrafficBytes,
            Long dataLimitBytes,
            /** Срок в epoch-секундах — так его хранит панель. */
            Long expireEpoch) {}

    // =====================================================================================
    // Платежи
    // =====================================================================================

    private static final Set<String> PAYMENT_SORTS =
            Set.of("createdAt", "paidAt", "amountKopeks", "telegramId", "status", "id");

    @Transactional(readOnly = true)
    public PageView<PaymentRow> payments(String query, List<String> statuses, List<String> providers,
                                         String sort, String dir, int page, int size) {
        Pageable pageable = pageable(page, size, sort, dir, PAYMENT_SORTS, "createdAt");
        Page<Payment> found = paymentRepository.adminSearch(
                like(query),
                enums(statuses, Payment.Status.class),
                enums(providers, Payment.Provider.class),
                pageable);
        Map<Long, String> names = usernames(found.getContent().stream().map(Payment::getTelegramId).toList());
        return PageView.of(found, found.getContent().stream().map(p -> paymentRow(p, names)).toList());
    }

    public record PaymentRow(
            Long id,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            Long telegramId,
            String telegramUsername,
            String provider,
            String status,
            String planId,
            String deviceTierId,
            String targetKind,
            Long subscriptionId,
            long amountKopeks,
            String promoCodeUsed,
            boolean autorenew,
            LocalDateTime refundedAt,
            Long refundAmountKopeks,
            /** Идентификатор на стороне провайдера — по нему платёж ищется в ЛК ЮKassa/CryptoBot. */
            String externalId) {}

    private PaymentRow paymentRow(Payment p, Map<Long, String> names) {
        String external = p.getYookassaPaymentId() != null ? p.getYookassaPaymentId()
                : p.getCryptoInvoiceId() != null ? p.getCryptoInvoiceId()
                : p.getTelegramChargeId();
        return new PaymentRow(
                p.getId(), p.getCreatedAt(), p.getPaidAt(), p.getTelegramId(),
                name(names, p.getTelegramId(), p.getTelegramUsername()),
                p.getProvider() == null ? null : p.getProvider().name(),
                p.getStatus() == null ? null : p.getStatus().name(),
                p.getPlanId(), p.getDeviceTierId(), p.getTargetKind(), p.getSubscriptionId(),
                p.getAmountKopeks(), p.getPromoCodeUsed(), p.isAutorenew(),
                p.getRefundedAt(), p.getRefundAmountKopeks(), external);
    }

    // =====================================================================================
    // Подписки
    // =====================================================================================

    private static final Set<String> SUBSCRIPTION_SORTS =
            Set.of("createdAt", "expiresAt", "telegramId", "seq", "id", "lastActivatedAt");

    @Transactional(readOnly = true)
    public PageView<SubscriptionRow> subscriptions(Long telegramId, String state, String sort, String dir,
                                                   int page, int size) {
        Pageable pageable = pageable(page, size, sort, dir, SUBSCRIPTION_SORTS, "createdAt");
        Page<Subscription> found = subscriptionRepository.adminSearch(
                telegramId == null ? 0L : telegramId, normalizeSubState(state), pageable);
        Map<Long, String> names = usernames(found.getContent().stream().map(Subscription::getTelegramId).toList());
        return PageView.of(found, found.getContent().stream().map(s -> subscriptionRow(s, names)).toList());
    }

    public record SubscriptionRow(
            Long id,
            Long telegramId,
            String telegramUsername,
            int seq,
            String label,
            boolean current,
            String state,
            String origin,
            LocalDateTime expiresAt,
            Long balanceSeconds,
            String providerUsername,
            Integer deviceLimit,
            String deviceTierId,
            boolean autorenewEnabled,
            LocalDateTime createdAt,
            LocalDateTime parkedAt,
            LocalDateTime lastActivatedAt,
            /** Незакрытое намерение в панель AccessProvider — если висит долго, синхронизация буксует. */
            String providerIntent,
            LocalDateTime providerSyncedAt) {}

    private SubscriptionRow subscriptionRow(Subscription s, Map<Long, String> names) {
        return new SubscriptionRow(
                s.getId(), s.getTelegramId(), name(names, s.getTelegramId(), null), s.getSeq(),
                s.getLabel(), s.isCurrent(),
                s.getState() == null ? null : s.getState().name(),
                s.getOrigin() == null ? null : s.getOrigin().name(),
                s.getExpiresAt(), s.getBalanceSeconds(), s.getProviderUsername(),
                s.getDeviceLimit(), s.getDeviceTierId(), s.isAutorenewEnabled(),
                s.getCreatedAt(), s.getParkedAt(), s.getLastActivatedAt(),
                s.getProviderIntent(), s.getProviderSyncedAt());
    }

    // =====================================================================================
    // Устройства
    // =====================================================================================

    private static final Set<String> DEVICE_SORTS =
            Set.of("lastSeenAt", "firstSeenAt", "telegramId", "id", "deviceOs");

    @Transactional(readOnly = true)
    public PageView<DeviceRow> devices(Long telegramId, String status, String sort, String dir,
                                       int page, int size) {
        Pageable pageable = pageable(page, size, sort, dir, DEVICE_SORTS, "lastSeenAt");
        Page<UserDevice> found = deviceRepository.adminSearch(
                telegramId == null ? 0L : telegramId,
                enums(status == null || status.isBlank() ? List.of() : List.of(status), UserDevice.Status.class),
                pageable);
        Map<Long, String> names = usernames(found.getContent().stream().map(UserDevice::getTelegramId).toList());
        return PageView.of(found, found.getContent().stream().map(d -> deviceRow(d, names)).toList());
    }

    public record DeviceRow(
            Long id,
            Long telegramId,
            String telegramUsername,
            Long subscriptionId,
            /** Только края отпечатка: полный HWID — квази-секрет идентичности устройства. */
            String hwid,
            String customName,
            String deviceModel,
            String deviceOs,
            String osVersion,
            String userAgent,
            String status,
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt) {}

    private DeviceRow deviceRow(UserDevice d, Map<Long, String> names) {
        return new DeviceRow(
                d.getId(), d.getTelegramId(), name(names, d.getTelegramId(), null), d.getSubscriptionId(),
                maskHwid(d.getHwid()), d.getCustomName(), d.getDeviceModel(), d.getDeviceOs(),
                d.getOsVersion(), d.getUserAgent(),
                d.getStatus() == null ? null : d.getStatus().name(),
                d.getFirstSeenAt(), d.getLastSeenAt());
    }

    // =====================================================================================
    // Рефералы, промокоды, служебные таблицы
    // =====================================================================================

    @Transactional(readOnly = true)
    public PageView<ReferralRow> referrals(int page, int size) {
        Page<Referral> found = referralRepository.findAll(
                plainPageable(page, size, "referredAt", "id"));
        List<Long> ids = new ArrayList<>();
        found.getContent().forEach(r -> {
            ids.add(r.getReferrerTelegramId());
            ids.add(r.getReferredTelegramId());
        });
        Map<Long, String> names = usernames(ids);
        return PageView.of(found, found.getContent().stream().map(r -> referralRow(r, names)).toList());
    }

    public record ReferralRow(
            Long id,
            Long referrerTelegramId,
            String referrerUsername,
            Long referredTelegramId,
            String referredUsername,
            LocalDateTime referredAt,
            /** Заполнено — бонус начислен (маркер идемпотентности). */
            LocalDateTime paidAt,
            Integer rewardBonusDays,
            Integer refereeBonusDays,
            Long paymentId,
            LocalDateTime bonusRevertedAt) {}

    private ReferralRow referralRow(Referral r, Map<Long, String> names) {
        return new ReferralRow(
                r.getId(), r.getReferrerTelegramId(), name(names, r.getReferrerTelegramId(), null),
                r.getReferredTelegramId(), name(names, r.getReferredTelegramId(), null),
                r.getReferredAt(), r.getPaidAt(), r.getRewardBonusDays(), r.getRefereeBonusDays(),
                r.getPaymentId(), r.getBonusRevertedAt());
    }

    @Transactional(readOnly = true)
    public List<PromoRow> promoCodes() {
        return promoCodeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(p -> new PromoRow(
                        p.getId(), p.getCode(),
                        p.getDiscountType() == null ? null : p.getDiscountType().name(),
                        p.getDiscountValue(), p.getMaxUses(), p.getUsedCount(), p.isActive(),
                        p.getExpiresAt(), p.getCreatedAt(), p.getCreatedByTelegramId()))
                .toList();
    }

    public record PromoRow(
            Long id, String code, String discountType, long discountValue,
            Integer maxUses,
            /** ⚠️ Считает и резервы: строка использования пишется на выставлении счёта, не на оплате. */
            int usedCount,
            boolean active, LocalDateTime expiresAt, LocalDateTime createdAt, Long createdByTelegramId) {}

    @Transactional(readOnly = true)
    public PageView<PromoUsageRow> promoUsages(int page, int size) {
        Page<PromoUsage> found = promoUsageRepository.findAll(
                plainPageable(page, size, "usedAt", "id"));
        Map<Long, String> names = usernames(found.getContent().stream().map(PromoUsage::getTelegramId).toList());
        Map<Long, String> codes = promoCodeRepository.findAllById(
                        found.getContent().stream().map(PromoUsage::getPromoCodeId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(PromoCode::getId, PromoCode::getCode, (a, b) -> a));
        return PageView.of(found, found.getContent().stream()
                .map(u -> new PromoUsageRow(
                        u.getId(), u.getPromoCodeId(), codes.get(u.getPromoCodeId()),
                        u.getTelegramId(), name(names, u.getTelegramId(), null),
                        u.getPaymentId(), u.getDiscountKopeks(), u.getUsedAt(), u.getRevertedAt()))
                .toList());
    }

    public record PromoUsageRow(
            Long id, Long promoCodeId, String code, Long telegramId, String telegramUsername,
            Long paymentId, long discountKopeks, LocalDateTime usedAt, LocalDateTime revertedAt) {}

    @Transactional(readOnly = true)
    public PageView<GrantRow> grants(int page, int size) {
        Page<PendingGrant> found = pendingGrantRepository.findAll(
                plainPageable(page, size, "grantedAt", "id"));
        return PageView.of(found, found.getContent().stream()
                .map(g -> new GrantRow(
                        g.getId(), g.getUsername(), g.getDays(), g.getGrantedAt(), g.getExpiresAt(),
                        g.getGrantedByTelegramId(), g.isUsed(), g.getUsedAt(), g.getUsedByTelegramId()))
                .toList());
    }

    public record GrantRow(
            Long id, String username, int days, LocalDateTime grantedAt, LocalDateTime expiresAt,
            Long grantedByTelegramId, boolean used, LocalDateTime usedAt, Long usedByTelegramId) {}

    /**
     * Ссылки входа в кабинет. Сам токен не отдаётся никогда — по нему выдаётся живая сессия,
     * а «посмотреть» его в панели незачем: видно, кому и когда ссылка ушла и была ли открыта.
     */
    @Transactional(readOnly = true)
    public PageView<MagicLinkRow> magicLinks(int page, int size) {
        Page<MagicLink> found = magicLinkRepository.findAll(
                plainPageable(page, size, "createdAt", "id"));
        Map<Long, String> names = usernames(found.getContent().stream().map(MagicLink::getTelegramId).toList());
        return PageView.of(found, found.getContent().stream()
                .map(m -> new MagicLinkRow(
                        m.getId(), m.getTelegramId(), name(names, m.getTelegramId(), null),
                        m.getEmail(), m.getCreatedAt(), m.getExpiresAt(), m.getUsedAt()))
                .toList());
    }

    public record MagicLinkRow(
            Long id, Long telegramId, String telegramUsername, String email,
            LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime usedAt) {}

    @Transactional(readOnly = true)
    public PageView<OfferRow> offerAcceptances(int page, int size) {
        Page<OfferAcceptance> found = offerAcceptanceRepository.findAll(
                plainPageable(page, size, "acceptedAt", "telegramId"));
        Map<Long, String> names = usernames(found.getContent().stream()
                .map(OfferAcceptance::getTelegramId).toList());
        return PageView.of(found, found.getContent().stream()
                .map(o -> new OfferRow(o.getTelegramId(), name(names, o.getTelegramId(), null),
                        o.getAcceptedAt(), o.getOfferUrl(), o.getOfferRevision()))
                .toList());
    }

    /** @param offerRevision метка редакции; пусто у акцептов до появления поля. */
    public record OfferRow(Long telegramId, String telegramUsername,
                           LocalDateTime acceptedAt, String offerUrl, String offerRevision) {}

    @Transactional(readOnly = true)
    public PageView<LedgerRow> ledger(Long telegramId, int page, int size) {
        Pageable pageable = pageable(page, size, "occurredAt", "desc", Set.of("occurredAt"), "occurredAt");
        Page<SubscriptionLedger> found = ledgerRepository.adminSearch(
                telegramId == null ? 0L : telegramId, pageable);
        Map<Long, Integer> seqById = new HashMap<>();
        if (telegramId != null) {
            subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId)
                    .forEach(s -> seqById.put(s.getId(), s.getSeq()));
        }
        return PageView.of(found, found.getContent().stream().map(l -> ledgerRow(l, seqById)).toList());
    }

    public record LedgerRow(
            Long id, Long subscriptionId, Integer subscriptionSeq, Long telegramId,
            LocalDateTime occurredAt, String kind, long deltaSeconds, long balanceAfterSeconds,
            Long paymentId, String note) {}

    private LedgerRow ledgerRow(SubscriptionLedger l, Map<Long, Integer> seqById) {
        return new LedgerRow(
                l.getId(), l.getSubscriptionId(), seqById.get(l.getSubscriptionId()), l.getTelegramId(),
                l.getOccurredAt(), l.getKind() == null ? null : l.getKind().name(),
                l.getDeltaSeconds(), l.getBalanceAfterSeconds(), l.getPaymentId(), l.getNote());
    }

    // =====================================================================================
    // Общее
    // =====================================================================================

    /** Одна страница любой таблицы: строки + сколько их всего. */
    public record PageView<T>(List<T> items, long total, int page, int size, int totalPages) {
        static <E, T> PageView<T> of(Page<E> source, List<T> rows) {
            return new PageView<>(rows, source.getTotalElements(),
                    source.getNumber(), source.getSize(), source.getTotalPages());
        }
    }

    private record PayAgg(long count, long sumKopeks, LocalDateTime lastPaidAt) {
        static final PayAgg EMPTY = new PayAgg(0, 0, null);
    }

    private record DeviceAgg(long count, LocalDateTime lastSeenAt) {
        static final DeviceAgg EMPTY = new DeviceAgg(0, null);
    }

    private Map<Long, PayAgg> payAggregates(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, PayAgg> result = new HashMap<>();
        for (Object[] row : paymentRepository.aggregateSucceededByUser(ids)) {
            result.put((Long) row[0], new PayAgg(
                    ((Number) row[1]).longValue(),
                    row[2] == null ? 0L : ((Number) row[2]).longValue(),
                    (LocalDateTime) row[3]));
        }
        return result;
    }

    private Map<Long, DeviceAgg> deviceAggregates(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, DeviceAgg> result = new HashMap<>();
        for (Object[] row : deviceRepository.aggregateByUser(ids)) {
            result.put((Long) row[0], new DeviceAgg(
                    ((Number) row[1]).longValue(), (LocalDateTime) row[2]));
        }
        return result;
    }

    private static Map<Long, Long> countByTelegramId(List<Object[]> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    /** {@code telegram_id → @username} одним запросом: строки платежей и устройств имени не хранят. */
    private Map<Long, String> usernames(Collection<Long> ids) {
        Set<Long> unique = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (unique.isEmpty()) return Map.of();
        Map<Long, String> result = new HashMap<>();
        for (Customer u : userRepository.findByTelegramIdIn(unique)) {
            if (u.getTelegramUsername() != null) {
                result.put(u.getTelegramId(), u.getTelegramUsername());
            }
        }
        return result;
    }

    /** Имя из справочника, иначе снапшот из самой строки (у платежа он есть), иначе пусто. */
    private static String name(Map<Long, String> names, Long telegramId, String fallback) {
        if (telegramId != null) {
            String known = names.get(telegramId);
            if (known != null) return known;
        }
        return fallback;
    }

    private Set<String> onlineProviderUsernames() {
        return onlineTracker.recentNodeSessions().stream()
                .map(OnlineTracker.NodeSession::username)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    static String maskHwid(String hwid) {
        if (hwid == null || hwid.length() < 12) return hwid;
        return hwid.substring(0, 8) + "…" + hwid.substring(hwid.length() - 4);
    }

    private static Long daysLeft(LocalDateTime expiresAt, LocalDateTime now) {
        if (expiresAt == null) return null;
        return Duration.between(now, expiresAt).toDays();
    }

    /** Пустой поиск отдаётся как {@code ''} — этим значением запрос и отключает фильтр. */
    private static String like(String query) {
        if (query == null || query.isBlank()) return "";
        String trimmed = query.trim().toLowerCase();
        if (trimmed.startsWith("@")) trimmed = trimmed.substring(1);
        return "%" + trimmed + "%";
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "all";
        return switch (mode) {
            case "active", "expired", "paying", "trial", "email", "autorenew" -> mode;
            default -> "all";
        };
    }

    private static String normalizeSubState(String state) {
        if (state == null || state.isBlank()) return "all";
        return switch (state) {
            case "current", "parked" -> state;
            default -> "all";
        };
    }

    /**
     * Полный набор значений enum'а, если фильтр пуст. {@code IN (…)} со всеми значениями вместо
     * {@code :param IS NULL}: Postgres не выводит тип у параметра, сравниваемого только с NULL,
     * и запрос падал бы на «could not determine data type».
     */
    private static <E extends Enum<E>> List<E> enums(List<String> raw, Class<E> type) {
        List<E> all = List.of(type.getEnumConstants());
        if (raw == null || raw.isEmpty()) return all;
        List<E> picked = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try {
                picked.add(Enum.valueOf(type, s.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Неизвестное значение из URL — просто не сужает выборку.
            }
        }
        return picked.isEmpty() ? all : picked;
    }

    private static Pageable pageable(int page, int size, String sort, String dir,
                                     Set<String> allowed, String fallback) {
        return pageable(page, size, sort, dir, allowed, fallback, "id");
    }

    /**
     * Сортировка для методов, которые Spring Data строит через Criteria API ({@code findAll}).
     * Отличие от {@link #pageable} одно: без {@code NULLS LAST} — Hibernate его в Criteria не
     * умеет и бросает {@code UnsupportedOperationException}. Здесь он и не нужен: все эти
     * таблицы сортируются по дате создания, которая проставляется при вставке.
     */
    private static Pageable plainPageable(int page, int size, String property, String tieBreaker) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc(property), Sort.Order.desc(tieBreaker)));
    }

    /**
     * @param allowed    закрытый список полей сортировки: имя приходит из URL и уезжает в
     *                   {@code ORDER BY}, поэтому всё, чего нет в списке, молча заменяется
     *                   на {@code fallback}
     * @param tieBreaker вторичный ключ. Без него строки с равным значением (например, с
     *                   {@code NULL} в {@code paid_at}) переставляются между страницами и
     *                   «прыгают» при листании. У {@code offer_acceptances} нет столбца
     *                   {@code id} — там ключом идёт {@code telegramId}
     */
    private static Pageable pageable(int page, int size, String sort, String dir,
                                     Set<String> allowed, String fallback, String tieBreaker) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String property = sort != null && allowed.contains(sort) ? sort : fallback;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort order = Sort.by(new Sort.Order(direction, property).nullsLast());
        if (!property.equals(tieBreaker)) {
            order = order.and(Sort.by(Sort.Order.desc(tieBreaker)));
        }
        return PageRequest.of(safePage, safeSize, order);
    }
}
