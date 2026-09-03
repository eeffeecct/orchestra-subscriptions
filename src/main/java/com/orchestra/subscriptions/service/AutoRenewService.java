package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.payment.PaymentDraft;
import com.orchestra.subscriptions.service.payment.PaymentFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Планировщик автопродления. Раз в час находит активных юзеров с привязанной картой,
 * истекающих в окне [now+24h, now+25h], и списывает стоимость их последнего купленного
 * тарифа через YooKassa. На успехе — продлевает подписку тем же путём, что платная оплата.
 * На отказе — пишет last_failure_at (чтобы не ретраить в том же окне) и шлёт юзеру
 * уведомление «продли вручную».
 * <p>
 * Политика (согласована 2026-04-17):
 * <ul>
 *   <li>Списание за 24ч до истечения</li>
 *   <li>Opt-out (включено по умолчанию при первой оплате картой с сохранением метода)</li>
 *   <li>Без retry при отказе: один шанс, уведомление, expires_at не меняется, подписка
 *       по истечении отключается как обычно {@link com.orchestra.subscriptions.bot.SubscriptionExpiryNotifier}</li>
 *   <li>Без grace-периода</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRenewService {

    /**
     * Нижняя граница окна — за сколько до истечения начинаем списывать.
     * <p>
     * Не {@code private}: ту же цифру показывает человеку {@link AutoRenewInfoService} строкой
     * «спишется такого-то числа». Своя копия константы там разъехалась бы с планировщиком
     * молча — экран продолжал бы называть дату, которой уже не соответствует ни одно списание.
     */
    static final long WINDOW_START_HOURS = 24;
    /** Верхняя граница окна — добавляем 1 час запаса чтобы ни один юзер не проскочил,
     *  если планировщик опоздает на тик. */
    private static final long WINDOW_END_HOURS = 25;
    /** Сколько часов после failure игнорируем юзера (чтобы не ретраить одну ошибку). */
    private static final long FAILURE_COOLDOWN_HOURS = 25;

    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    /** Строку платежа заводит только фабрика — она же пишет цель (в какую подписку зачислять). */
    private final PaymentFactory paymentFactory;
    private final YooKassaClient yooKassaClient;
    private final AppConfig appConfig;
    private final NotificationService notificationService;
    /** Активация делегируется общему пути вебхуков — см. {@link #chargeAndActivate}. */
    private final PaymentProcessingService paymentProcessingService;
    /** Зеркало подписок: переключение автопродления тоже меняет зеркалируемое состояние. */
    private final SubscriptionRegistryService subscriptionRegistry;
    /** Кого считать кандидатом на списание — через общий фасад (источник за флагом). */
    private final SubscriptionLookupService subscriptionLookup;

    /**
     * Feature-флаг для фонового scheduling. В prod = true (default). В тестах ставим false,
     * чтобы @Scheduled не фирился одновременно с ручными runOnce() из @Test — это вызывало
     * flaky "wanted 1 time, was 2 times" в CI на race condition.
     */
    @Value("${app.scheduler.autorenew-enabled:true}")
    private boolean schedulerEnabled;

    /** Раз в час ищет кандидатов и пытается списать. */
    @Scheduled(fixedDelay = 60L * 60L * 1000L)
    public void processUpcomingRenewals() {
        if (!schedulerEnabled) {
            return;
        }
        runOnce();
    }

    /** Публичный метод для ручного запуска из тестов / админки. */
    public void runOnce() {
        // Kill-switch: если автоплатежи у магазина не одобрены YooKassa — даже не пытаемся.
        // Защищает от лишних 403-х и непонятных FAILED-записей в payments, пока админ
        // не поднял yookassa.recurrent-enabled=true после одобрения заявки.
        if (!appConfig.getYookassa().isRecurrentEnabled()) {
            log.debug("Autorenew disabled (yookassa.recurrent-enabled=false), skipping tick");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.plusHours(WINDOW_START_HOURS);
        LocalDateTime to = now.plusHours(WINDOW_END_HOURS);
        LocalDateTime failureCutoff = now.minusHours(FAILURE_COOLDOWN_HOURS);

        List<Customer> candidates = subscriptionLookup.autorenewCandidates(from, to, failureCutoff);
        if (candidates.isEmpty()) {
            log.debug("No autorenew candidates in window [{}, {}]", from, to);
            return;
        }
        log.info("Autorenew: processing {} candidates", candidates.size());
        for (Customer user : candidates) {
            try {
                chargeAndActivate(user);
            } catch (Exception e) {
                // Защитный catch: одна проблема у юзера не должна останавливать весь прогон.
                log.error("Autorenew unexpected error for telegramId={}", user.getTelegramId(), e);
            }
        }
    }

    /**
     * Списывает и продлевает одного юзера.
     * <p>
     * <b>Метод намеренно НЕ {@code @Transactional}.</b> Внутри — внешнее списание денег, которое
     * откатом транзакции не отменишь, а строка платежа обязана быть ВИДНА снаружи до того, как
     * мы позовём {@link PaymentProcessingService#handleSucceeded} (тот берёт на неё row-lock в
     * своей транзакции). Раньше аннотация здесь стояла, но не работала: {@link #runOnce} зовёт
     * этот метод через {@code this}, минуя Spring-прокси — то есть код и так жил без транзакции,
     * только выглядел иначе.
     * <p>
     * <b>Порядок шагов важен.</b> Сначала сохраняем PENDING-платёж с {@code yookassa_payment_id},
     * и только потом активируем — общим путём {@code handleSucceeded}, тем же, что обрабатывает
     * вебхуки. Это даёт три вещи, которых у автопродления не было:
     * <ul>
     *   <li><b>Автоповтор.</b> Если активация упала (AccessProvider недоступен), платёж остаётся PENDING
     *       с известным id — {@code PaymentReconcileService} подхватит его в ближайшие 10 минут и
     *       догонит. Раньше SUCCEEDED проставлялся ДО активации и коммитился отдельно: деньги
     *       списаны, дней нет, реконсилер такой платёж не видит (он ищет только PENDING),
     *       ретрая нет — чинить руками.</li>
     *       Общий путь выписывает.</li>
     *   <li><b>Атомарность.</b> Там активация и пометка SUCCEEDED — в одной транзакции, поэтому
     *       «дни начислили, но платёж не отметили» (и, как следствие, двойное начисление при
     *       догоне) невозможно.</li>
     * </ul>
     */
    public void chargeAndActivate(Customer user) {
        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(user.getAutorenewPlanId());
        } catch (IllegalArgumentException e) {
            log.error("Autorenew: unknown plan {} on telegramId={}, disabling autorenew",
                    user.getAutorenewPlanId(), user.getTelegramId());
            // Невозможно продлить — тариф удалён. Отключаем автопродление, чтобы не спамить ошибки.
            user.setAutorenewEnabled(false);
            userRepository.save(user);
            // Кандидатов выбирают ПО СТРОКЕ ПОДПИСКИ: без зеркала отключение сюда не доезжает,
            // человек остаётся кандидатом навсегда и владелец получает эту ошибку каждый час.
            subscriptionRegistry.mirrorAfterCommit(user.getTelegramId());
            return;
        }

        // Ступень берём У ПРОДЛЕВАЕМОЙ ПОДПИСКИ — той самой, которую сейчас и продлеваем
        // (createIntoCurrent ниже целится в неё же).
        //
        // ⚠️ Раньше ступень читалась с ЧЕЛОВЕКА (autorenew_device_tier_id — ступень последней
        // покупки). Пока подписка у человека одна, это одно и то же; с несколькими они
        // расходятся, и расхождение било по деньгам дважды: списывалась цена чужой ступени, а
        // активация переписывала лимит устройств продлеваемой подписки под неё — человек,
        // переключившийся на подписку «1 устройство», ночью получал счёт за «3 устройства» и
        // лимит 3. Решение владельца 14.08.2026: продлевать ровно ту ступень, что у подписки.
        //
        // Если у продлеваемой подписки ступени нет (триал, покупка до появления ступеней) —
        // берём ступень человека, то есть ступень его последней покупки. Это осознанный
        // фолбэк, а не возврат к старому поведению: расхождение выше возможно только когда у
        // подписки СВОЯ ступень есть, и тогда выигрывает она. Здесь выбирать не из чего, и
        // человек получает ровно ту ступень, за которую платит.
        //
        // Ступени нет ни у подписки, ни у человека (или её удалили из конфига) → legacy-цена
        // плана, и лимит устройств активация не трогает вовсе.
        //
        // ⚠️ orElseGet срабатывает в ДВУХ случаях: текущей подписки нет вовсе и её
        // device_tier_id пуст — map(null) даёт пустой Optional. Оба ведут в фолбэк намеренно.
        String tierId = subscriptionLookup.currentFor(user.getTelegramId())
                .map(SubscriptionView::deviceTierId)
                .orElseGet(user::getDeviceTierId);
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(tierId).orElse(null);
        long chargeKopeks = appConfig.tierPriceKopeks(plan, tier);

        // Создаём PENDING-запись до фактического списания — помогает audit trail
        // и гарантирует, что /stats увидит попытку даже если случится exception.
        //
        // Цель — подписка, которая работает у человека В МОМЕНТ СПИСАНИЯ (её и выбрал
        // кандидатом планировщик: окно «истекает через сутки» считается по сроку текущей
        // подписки). Замороженные подписки автопродлением не продлеваются: карта и настройки
        // автопродления — свойство человека, а работает всегда одна подписка.
        //
        // createIntoCurrent, а не общий create: автосписание не имеет права завести НОВУЮ
        // подписку. Иначе человек, у которого ступень автопродления разошлась с текущей
        // подпиской, в ночь списания получил бы вторую замороженную подписку — и остался бы
        // без доступа, хотя деньги списали именно за продление.
        Payment pending = paymentFactory.createIntoCurrent(PaymentDraft.builder()
                .telegramId(user.getTelegramId())
                .telegramUsername(user.getTelegramUsername())
                .planId(plan.getId())
                .deviceTierId(tier != null ? tier.getId() : null)
                .amountKopeks(chargeKopeks)
                .provider(Payment.Provider.YOOKASSA)
                // Метка «списал планировщик»: по ней handleSucceeded выберет текст DM про
                // автопродление вместо «Оплата получена» — в том числе когда активацию
                // догоняет реконсилер уже без нашего участия.
                .autorenew(true)
                .build());

        YooKassaClient.PaymentResponse resp;
        try {
            resp = yooKassaClient.chargeRecurrent(
                    user.getAutorenewMethodId(),
                    chargeKopeks,
                    "Автопродление: " + plan.getDescription()
                            + (tier != null ? " · " + tier.getName() : ""),
                    UUID.randomUUID().toString()
            );
        } catch (Exception e) {
            log.warn("Autorenew charge failed for telegramId={}: {}", user.getTelegramId(), e.getMessage());
            handleChargeFailure(user, pending, e.getMessage());
            return;
        }

        pending.setYookassaPaymentId(resp.getId());

        if (!"succeeded".equals(resp.getStatus())) {
            // pending / canceled / waiting_for_capture — для v1 всё это трактуется как failure.
            // При pending мы теоретически могли бы ждать подтверждения, но политика "без retry"
            // это упрощает: просто уведомляем и живём дальше.
            log.warn("Autorenew non-succeeded status={} for telegramId={}",
                    resp.getStatus(), user.getTelegramId());
            handleChargeFailure(user, pending, "YooKassa status=" + resp.getStatus());
            return;
        }

        // Деньги списаны. Фиксируем id платежа, оставляя статус PENDING: с этого момента платёж
        // «виден» реконсилеру, и даже если мы прямо сейчас упадём (или контейнер уедет в деплой),
        // активация всё равно догонится — потерять оплаченные дни больше нельзя.
        paymentRepository.save(pending);

        // Активация — общим путём вебхука: row-lock, идемпотентность по статусу, ре-верификация
        // в YooKassa, начисление дней, DM про автопродление. Всё в его
        // транзакции, поэтому пометка SUCCEEDED и начисленные дни коммитятся вместе.
        try {
            // resp уже получен от самой YooKassa по аутентифицированному API — передаём его как
            // подтверждённый, чтобы не перезапрашивать статус лишним сетевым вызовом.
            paymentProcessingService.handleSucceeded(resp.getId(), resp);
        } catch (Exception e) {
            // Платёж остался PENDING с известным id — реконсилер повторит в течение ~10 минут.
            // Владельцу об этом уже сообщил notifyOwnerAccessSyncFailure изнутри активации.
            log.error("Autorenew: charge succeeded but activation failed for telegramId={} paymentId={} "
                    + "— payment left PENDING for reconciler retry", user.getTelegramId(), resp.getId(), e);
            return;
        }

        log.info("Autorenew charged telegramId={} plan={} paymentId={}",
                user.getTelegramId(), plan.getId(), resp.getId());
    }

    private void handleChargeFailure(Customer user, Payment pending, String errorMessage) {
        pending.setStatus(Payment.Status.FAILED);
        paymentRepository.save(pending);

        user.setAutorenewLastFailureAt(LocalDateTime.now());
        userRepository.save(user);
        subscriptionRegistry.mirrorAfterCommit(user.getTelegramId());

        try {
            notificationService.sendAutoRenewFailed(user.getTelegramId());
        } catch (Exception e) {
            log.warn("Failed to notify telegramId={} about autorenew failure", user.getTelegramId(), e);
        }
        try {
            notificationService.notifyOwnerAutorenewFailure(user, errorMessage);
        } catch (Exception e) {
            log.warn("Failed to notify owner about autorenew failure for telegramId={}",
                    user.getTelegramId(), e);
        }
    }

    /**
     * Выключает автопродление у юзера (по запросу из /autorenew UI).
     * Сохраняет method_id, чтобы можно было включить обратно без повторной привязки карты.
     */
    @Transactional
    public void disableAutoRenew(Long telegramId) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            user.setAutorenewEnabled(false);
            userRepository.save(user);
            subscriptionRegistry.mirrorAfterCommit(telegramId);
            log.info("Disabled autorenew for telegramId={}", telegramId);
        });
    }

    /**
     * Включает автопродление. Работает только если уже есть сохранённый method_id
     * (иначе нечем списывать). Возвращает false, если method_id отсутствует.
     */
    @Transactional
    public boolean enableAutoRenew(Long telegramId) {
        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null || user.getAutorenewMethodId() == null) {
            return false;
        }
        user.setAutorenewEnabled(true);
        user.setAutorenewLastFailureAt(null); // новый старт
        userRepository.save(user);
        subscriptionRegistry.mirrorAfterCommit(telegramId);
        log.info("Enabled autorenew for telegramId={}", telegramId);
        return true;
    }

    /**
     * Полностью отвязывает карту: чистит payment_method_id, plan_id, last_failure_at и выключает
     * автопродление. После этого для повторного подключения нужно оформить новую оплату с
     * чекбоксом "сохранить карту". Это требование YooKassa для подключения рекуррентов
     * (правила: "при нажатии на кнопку отвязки карта сразу удаляется из вашей системы").
     *
     * @return true если что-то отвязали, false если юзера не было или карта уже не привязана
     */
    @Transactional
    public boolean unbindCard(Long telegramId) {
        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null || user.getAutorenewMethodId() == null) {
            return false;
        }
        user.setAutorenewMethodId(null);
        user.setAutorenewPlanId(null);
        user.setAutorenewDeviceTierId(null);
        user.setAutorenewEnabled(false);
        user.setAutorenewLastFailureAt(null);
        userRepository.save(user);
        subscriptionRegistry.mirrorAfterCommit(telegramId);
        log.info("Unbound autorenew card for telegramId={}", telegramId);
        return true;
    }

}
