package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Referral;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.ReferralRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.util.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Обработка webhook-уведомлений от платёжных провайдеров (ЮKassa и CryptoBot).
 * Идемпотентность гарантируется проверкой статуса Payment в БД + ре-верификацией через API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProcessingService {

    /**
     * С какой доли платежа возврат считается ПОЛНЫМ, в процентах. От этого порога зависят все
     * НЕОБРАТИМЫЕ эффекты возврата: отключение доступа целиком, откат ступени устройств и откат
     * реферальных бонусов. Частичный возврат обязан только урезать срок пропорционально —
     * иначе 100 ₽, возвращённые с годовой оплаты, отбирали и год доступа, и семейный лимит
     * устройств, и бонусные дни у обоих участников реферальной пары.
     * <p>
     * 95, а не 100: «полный» возврат легко недосчитывается копеек на округлении pro-rata или
     * на удержанной комиссии, и по смыслу остаётся полным. Осмысленный частичный возврат (за
     * неиспользованные дни) в этот зазор не попадает — он всегда заметно меньше суммы платежа.
     */
    private static final int FULL_REFUND_PERCENT = 95;

    /**
     * На сколько процентов свежая котировка докупки слота может разойтись с оплаченной суммой,
     * прежде чем мы перестанем начислять слот молча. Цена слота — pro-rata от ОСТАТКА подписки,
     * поэтому несколько процентов набегают сами: между выставлением счёта и оплатой проходят
     * часы, остаток тает, округление до рубля добавляет ещё копейки. 10% покрывают эту
     * естественную усушку (для месячного слота — примерно трое суток задержки оплаты) и при
     * этом ловят реальный случай: счёт на хвост подписки, оплаченный уже ПОСЛЕ продления на год.
     */
    private static final int ADDON_QUOTE_TOLERANCE_PERCENT = 10;

    private final YooKassaClient yooKassaClient;
    private final CryptoBotClient cryptoBotClient;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository userRepository;
    /** Нужен, чтобы сверить цель платежа с тем, что у человека работает сейчас. */
    private final SubscriptionRepository subscriptionRepository;
    private final AccessService accessService;
    /** Создание новой подписки поверх существующей — единственный путь появления второй. */
    private final SubscriptionMintService subscriptionMintService;
    /** Журнал сроков: зачисление в припаркованную подписку не проходит через AccessService. */
    private final SubscriptionLedgerService ledger;
    private final AppConfig appConfig;
    private final NotificationService notificationService;
    private final ReferralService referralService;
    private final ReferralRepository referralRepository;
    private final PromoCodeService promoCodeService;
    private final StarsChargePersister starsChargePersister;
    /** Перепроверка котировки докупки слота на webhook'е: счёт мог устареть, пока его оплачивали. */
    private final DeviceTrackingService deviceTrackingService;

    @Transactional
    public void handleSucceeded(String yookassaPaymentId) {
        handleSucceeded(yookassaPaymentId, null);
    }

    /**
     * @param preVerified ответ YooKassa, уже полученный вызывающим по аутентифицированному API
     *                    (автопродление: {@code chargeRecurrent} возвращает тот же объект, что
     *                    вернул бы {@code getPayment}). Не null — пропускаем повторный запрос:
     *                    перепроверять по сети то, что мы только что получили от самой YooKassa,
     *                    незачем, а лишний вызов — лишняя точка отказа. Null (вебхук, реконсилер)
     *                    — состояние платежа запрашивается и проверяется, потому что вебхук
     *                    никем не подписан.
     */
    @Transactional
    public void handleSucceeded(String yookassaPaymentId, YooKassaClient.PaymentResponse preVerified) {
        // Lock-on-read: параллельная повторная доставка/реконсилер блокируются до коммита и
        // увидят SUCCEEDED — статус-проверка становится атомарной, без дублей чека/нотификаций.
        Payment payment = paymentRepository.findByYookassaPaymentIdForUpdate(yookassaPaymentId).orElse(null);
        if (payment == null) {
            log.warn("Received webhook for unknown YooKassa payment {}", yookassaPaymentId);
            return;
        }

        if (payment.getStatus() == Payment.Status.SUCCEEDED) {
            log.info("Payment {} already processed, skipping", yookassaPaymentId);
            return;
        }

        // Ре-верификация через API ЮKassa — webhook не аутентифицирован, доверять ему нельзя.
        // Guard на id: подставленный не тому платежу preVerified игнорируем и перезапрашиваем.
        YooKassaClient.PaymentResponse actual =
                preVerified != null && yookassaPaymentId.equals(preVerified.getId())
                        ? preVerified
                        : yooKassaClient.getPayment(yookassaPaymentId);
        if (actual == null || !"succeeded".equals(actual.getStatus())) {
            log.warn("Payment {} status mismatch: webhook=succeeded api={}",
                    yookassaPaymentId, actual == null ? "null" : actual.getStatus());
            return;
        }

        // Докупка «+1 устройство» — не тарифный план: инкремент лимита вместо начисления дней.
        if (payment.isDeviceAddon()) {
            completeDeviceAddon(payment);
            return;
        }

        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(payment.getPlanId());
        } catch (IllegalArgumentException e) {
            log.error("Unknown plan {} on payment {}", payment.getPlanId(), yookassaPaymentId);
            return;
        }

        Landing landing = activateInto(payment, plan, resolveDeviceTier(payment));
        Customer customer = landing.user();

        payment.setStatus(Payment.Status.SUCCEEDED);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // Промокод: если был применён к этому платежу — записываем usage и инкрементим
        // used_count. До этого момента промо был лишь «зарезервирован» на Payment в виде
        // promo_code_used; реальная запись делается только когда платёж точно SUCCEEDED.
        recordPromoUsageIfApplicable(payment, plan);

        // Автопродление: если YooKassa вернула сохранённый payment_method, привязываем его
        // к пользователю. Дальше планировщик AutoRenewService за 24ч до истечения сделает
        // charge через YooKassaClient.chargeRecurrent. Opt-out модель: включаем по умолчанию.
        YooKassaClient.PaymentMethod method = actual.getPaymentMethod();
        if (payment.isAutorenew()) {
            // Это списание самого планировщика: карта уже привязана, перепривязывать нечего.
            // Не трогаем autorenew_enabled — иначе повторная обработка (реконсилер догоняет
            // застрявшую активацию спустя минуты) молча включила бы автопродление обратно,
            // если юзер успел его отключить. Сбрасываем только счётчик неудач: списание прошло.
            if (customer.getAutorenewLastFailureAt() != null) {
                customer.setAutorenewLastFailureAt(null);
                userRepository.save(customer);
            }
        } else if (customer != null && method != null
                && Boolean.TRUE.equals(method.getSaved()) && method.getId() != null) {
            // Карта — свойство ЧЕЛОВЕКА, её сохраняем всегда.
            customer.setAutorenewMethodId(method.getId());
            // Сбрасываем last_failure — это новая привязка карты, предыдущие failure не актуальны.
            customer.setAutorenewLastFailureAt(null);
            // А ЧТО продлевать — свойство ПОДПИСКИ, и переустанавливать его вправе только
            // оплата той подписки, которая работает. Иначе покупка дешёвой ступени в
            // замороженную подписку молча переписала бы автопродление работающей: следующее
            // списание взяло бы чужую цену и через activatePaidSubscription срезало бы её
            // лимит устройств — ровно тот баг, ради которого затевались отдельные подписки.
            if (landing.intoCurrent()) {
                customer.setAutorenewPlanId(payment.getPlanId());
                // Ступень устройств продлеваем ту же, что купили (null = legacy-цена плана).
                customer.setAutorenewDeviceTierId(payment.getDeviceTierId());
                customer.setAutorenewEnabled(true);
            }
            userRepository.save(customer);
            accessService.mirrorSubscription(payment.getTelegramId());
            log.info("Saved autorenew method for telegramId={} planId={} (в текущую: {})",
                    payment.getTelegramId(), payment.getPlanId(), landing.intoCurrent());
        } else if (landing.intoCurrent()) {
            syncAutorenewSelection(customer, payment);
        }

        // Реферальная программа: если это первая SUCCEEDED-оплата юзера и он был приглашён —
        // начисляем бонусы рефереру и приглашённому. Ошибки внутри не должны ломать оплату.
        referralService.maybeAwardReferral(payment.getTelegramId(), payment.getId());

        announceSuccess(payment, plan, landing);

        log.info("Processed succeeded payment {} for telegramId={}",
                yookassaPaymentId, payment.getTelegramId());
    }

    /**
     * Обработка webhook {@code refund.succeeded} от YooKassa. Вызывается и для юзерских
     * возвратов через /refund в боте, и для возвратов, которые владелец делает вручную
     * в ЛК YooKassa, и для forced chargeback-ов инициированных банком клиента.
     * <p>
     * Всё тело webhook.object приходит сюда, потому что нам нужен {@code payment_id}
     * (не {@code id} — это id самого возврата).
     * <p>
     * Политика: срок урезается ПРОПОРЦИОНАЛЬНО фактически возвращённой сумме
     * ({@code plan.days × сумма возврата / сумма платежа}) — частичный возврат не вправе
     * отобрать весь оплаченный период. Полное отключение (status=DISABLED, AccessProvider disable)
     * наступает, только если возврат фактически полный (см. {@link #FULL_REFUND_PERCENT})
     * И урезанный срок ушёл в прошлое. Если дни остаются (например, стэкнутая подписка с
     * несколькими оплатами и возвратом только одной) — уменьшаем {@code expires_at} и
     * оставляем активным.
     * <p>
     * Дополнительно отключаем автопродление и стираем {@code autorenew_method_id} —
     * возвращённая карта не должна списываться автоматически.
     */
    @Transactional
    public void handleRefundSucceeded(Map<String, Object> refundObject) {
        String paymentId = (String) refundObject.get("payment_id");
        String refundId = (String) refundObject.get("id");
        if (paymentId == null) {
            log.warn("Refund webhook without payment_id: {}", refundObject);
            return;
        }

        // Lock-on-read, как в handleSucceeded: параллельная повторная доставка вебхука и
        // админский путь /refund иначе оба проходили проверку статуса и применяли эффекты
        // дважды (двойное урезание срока, двойной декремент лимита устройств у addon-возврата).
        Payment payment = paymentRepository.findByYookassaPaymentIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Received refund webhook for unknown payment {}", paymentId);
            return;
        }
        if (payment.getStatus() == Payment.Status.REFUNDED) {
            log.info("Payment {} already refunded, skipping", paymentId);
            return;
        }

        // Re-verification: webhook не подписан YooKassa, доверять нельзя. Без re-verify атакующий,
        // знающий payment_id, мог бы залить «refund.succeeded» извне и отключить чужую подписку
        // + отвязать карту + откатить реф-бонусы. Поэтому id ОБЯЗАТЕЛЕН: без него re-verify
        // невозможна, и мы отказываем независимо от того, насколько «правдоподобно» выглядит payload.
        if (refundId == null) {
            log.warn("Refund webhook without id rejected (re-verification impossible), payment_id={}", paymentId);
            return;
        }
        long verifiedRefundKopeks;
        try {
            YooKassaClient.RefundResponse actual = yooKassaClient.getRefund(refundId);
            if (actual == null || !"succeeded".equals(actual.getStatus())) {
                log.warn("Refund {} status mismatch: webhook=succeeded api={}",
                        refundId, actual == null ? "null" : actual.getStatus());
                return;
            }
            // Возврат обязан относиться ИМЕННО к этому платежу. Без этой проверки re-verify
            // подтверждала лишь «такой возврат существует и он succeeded»: атакующий, у которого
            // есть любой свой валидный refund_id, мог подставить в payment_id чужой платёж и
            // выключить чужую подписку (+ отвязать карту, откатить бонусы). Оба id — неугадываемые
            // UUID, поэтому это defence-in-depth, но связка стоит одну строку.
            if (!paymentId.equals(actual.getPaymentId())) {
                log.warn("Refund {} belongs to payment {} but webhook claimed {} — refusing",
                        refundId, actual.getPaymentId(), paymentId);
                return;
            }
            // Сумму берём из подтверждённого API-ответа, а не из неподписанного вебхука.
            verifiedRefundKopeks = amountToKopeks(actual.getAmount(), payment.getAmountKopeks());
        } catch (Exception e) {
            // Соответствие webhook ↔ API нарушено / API ЮKassa недоступен — отказываем.
            // Раньше «proceeding with webhook data» оставляло вектор: атакующий мог
            // подобрать невалидный, но валидно-выглядящий refundId, getRefund бросало 404,
            // и refund применялся. Теперь — explicit fail.
            log.warn("Refund {} re-verify failed, refusing to apply webhook", refundId, e);
            return;
        }

        // Возврат за докупку слота: лимит откатывается, срок подписки не трогаем.
        if (payment.isDeviceAddon()) {
            applyAddonRefund(payment, verifiedRefundKopeks);
            return;
        }

        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(payment.getPlanId());
        } catch (IllegalArgumentException e) {
            log.error("Unknown plan {} on refunded payment {}", payment.getPlanId(), paymentId);
            return;
        }

        RefundOutcome outcome = applyRefundEffects(payment, plan, verifiedRefundKopeks);
        if (outcome == null) {
            notificationService.notifyOwnerRefund(payment, plan);
            return;
        }

        try {
            if (outcome.parked() != null) {
                notificationService.sendRefundFromParked(
                        payment.getTelegramId(), plan, outcome.parked(), verifiedRefundKopeks);
            } else if (outcome.disabled()) {
                notificationService.sendRefundDisabled(
                        payment.getTelegramId(), plan, verifiedRefundKopeks);
            } else {
                notificationService.sendRefundReduced(
                        payment.getTelegramId(), plan, outcome.newExpiry(), verifiedRefundKopeks);
            }
        } catch (Exception e) {
            log.warn("Failed to notify user {} about refund", payment.getTelegramId(), e);
        }
        notificationService.notifyOwnerRefund(payment, plan);
    }

    /**
     * Outcome of applying a refund: либо подписка отключена полностью, либо expires_at урезан
     * ({@code newExpiry == null} если disabled). При частичном возврате, который срок не
     * тронул, здесь лежит НЕизменённая дата — тексту человеку врать нельзя.
     *
     * @param parked заполнено, если возврат прошёл по ЗАМОРОЖЕННОЙ подписке: работающая при
     *               этом не изменилась ни на день, и человеку нужен другой текст — обычное
     *               «подписка отключена» здесь напугало бы владельца работающего доступа
     */
    private record RefundOutcome(boolean disabled, LocalDateTime newExpiry, Subscription parked) {
    }

    /**
     * Эффекты возврата ЮKassa (единственного, который сейчас есть): урезает expires_at,
     * отвязывает автопродление если карта была привязана этим платежом, и помечает Payment
     * как REFUNDED. Возвращает null если Customer не найден (тогда обновляем только Payment).
     */
    private RefundOutcome applyRefundEffects(Payment payment, AppConfig.Plan plan, long refundedKopeks) {
        Customer customer = accessService.getUser(payment.getTelegramId()).orElse(null);
        if (customer == null) {
            log.warn("Customer not found for refunded payment id={}, updating payment row only",
                    payment.getId());
            markPaymentRefunded(payment, refundedKopeks);
            return null;
        }

        // Возврат бьёт по ТОЙ подписке, за которую платили. Резать дни работающей за возврат
        // по спящей значило бы отобрать доступ, который человек не оплачивал этим платежом, —
        // причём по полной сумме, хотя дни спящей остались бы нетронутыми.
        Subscription parkedTarget = refundTargetIfNotCurrent(payment);
        if (parkedTarget != null) {
            return refundFromParked(parkedTarget, payment, plan, refundedKopeks, customer);
        }

        LocalDateTime now = LocalDateTime.now();
        // Снимаем ровно оплаченную ДОЛЮ срока. До этого срезался весь план независимо от суммы
        // возврата: 100 ₽, возвращённые с годовой оплаты, отбирали год оплаченного доступа.
        int daysToRevoke = revokedDays(plan, payment, refundedKopeks);
        // Полнота возврата решает, применять ли необратимые эффекты (см. FULL_REFUND_PERCENT).
        boolean fullRefund = isFullRefund(payment, refundedKopeks);
        LocalDateTime newExpiry = customer.getExpiresAt() != null
                ? customer.getExpiresAt().minusDays(daysToRevoke)
                : now.minusDays(1); // expires_at=null → считаем что уже истекло
        // Полное отключение доступа — эффект ПОЛНОГО возврата. Частичный возврат, съевший весь
        // остаток, доступ не гасит: вернули часть денег, значит и отобрать можно только часть.
        boolean fullKill = fullRefund && newExpiry.isBefore(now);

        // Compliance с правилами YooKassa: при возврате карта должна отвязаться.
        // Раньше сравнивали planId платежа с autorenewPlanId — но если юзер успел
        // перепривязать карту через ДРУГОЙ план (купил month_1 → потом month_3 с
        // save_method, autorenewPlanId=month_3), а refund приходит на month_1,
        // сравнение давало false и карта оставалась привязанной. Особенно опасно
        // когда владелец делает refund вручную в ЛК YK — там вообще нет связи
        // между «какой платёж рефанджу» и «какая карта сейчас активна».
        // Безопасный выбор: отвязываем всегда, если что-то привязано. Юзер при
        // следующей оплате заново поставит чекбокс «сохранить карту».
        // Для Stars autorenewMethodId всегда null, так что блок безопасно no-op.
        if (customer.getAutorenewMethodId() != null) {
            customer.setAutorenewMethodId(null);
            customer.setAutorenewPlanId(null);
            customer.setAutorenewEnabled(false);
            customer.setAutorenewLastFailureAt(null);
        }

        customer.setNotified24h(false);
        customer.setNotified1h(false);

        // Если refund отправляет подписку в прошлое — обнуляем expires_at явно, чтобы
        // последующий /status и admin-card не показывали «❌ Неактивна, Действует до: 26 мая»
        // (visual inconsistency). disableAccess читает свежую запись из БД, так что после
        // нашего save status=DISABLED ляжет на запись с already-null expires_at.
        // Не трогаем expires_at если refund частичный (newExpiry в будущем) — там
        // setExpiry установит правильную урезанную дату.
        // Обнуление привязано к fullKill, а не к одной лишь дате: при неполном возврате мы
        // доступ не гасим, а стереть дату означало бы отобрать срок, за который деньги остались.
        if (fullKill) {
            customer.setExpiresAt(null);
        }

        userRepository.save(customer);

        // Порядок важен: сначала помечаем платёж REFUNDED и откатываем ступень устройств,
        // и только потом пишем в AccessProvider. setExpiry выводит ip_limit из device_limit юзера,
        // поэтому при обратном порядке в панель уезжал бы ещё НЕ откаченный лимит (например,
        // семейные 11) и там бы и остался: rollbackDeviceTierAfterRefund правит только БД.
        // markPaymentRefunded обязан идти первым — rollback ищет «последнюю оставшуюся
        // успешную оплату со ступенью» и текущий платёж должен уже выпасть из выборки.
        markPaymentRefunded(payment, refundedKopeks);
        // Откат ступени устройств — тоже эффект ПОЛНОГО возврата: за долю денег отбирать
        // у человека целую ступень (семейный лимит на весь оставшийся срок) нельзя.
        if (fullRefund) {
            rollbackDeviceTierAfterRefund(payment);
        }

        boolean disabled = false;
        // По умолчанию срок остаётся тем, что уже лежит в строке: ниже его меняют только те
        // ветки, которые реально что-то урезали. Это же значение уходит в текст человеку.
        LocalDateTime effectiveExpiry = customer.getExpiresAt();
        if (fullKill) {
            accessService.disableAccess(payment.getTelegramId());
            disabled = true;
            effectiveExpiry = null;
            log.info("Refund of payment id={} (полный) → disabled subscription for telegramId={}",
                    payment.getId(), payment.getTelegramId());
        } else if (daysToRevoke <= 0) {
            // Возврат меньше стоимости половины суток — срок не двигаем вовсе. Иначе на каждый
            // символический возврат в журнал сроков падала бы REFUND-запись с нулевой дельтой.
            log.info("Refund of payment id={} ({} коп. из {}) → срок не изменён, telegramId={}",
                    payment.getId(), refundedKopeks, payment.getAmountKopeks(), payment.getTelegramId());
        } else if (!newExpiry.isBefore(now)) {
            accessService.setExpiry(payment.getTelegramId(), newExpiry);
            effectiveExpiry = newExpiry;
            log.info("Refund of payment id={} → снято {} дн. из {}, expiry → {} for telegramId={}",
                    payment.getId(), daysToRevoke, plan.getDays(), newExpiry, payment.getTelegramId());
        } else {
            // Частичный возврат съел бы весь остаток. Отключить доступ целиком мы здесь не
            // вправе (это эффект полного возврата), а записать прошедшую дату нечем —
            // setExpiry прошедшие даты игнорирует. Оставляем срок как есть и зовём владельца:
            // сколько снять в такой ситуации — его решение, а не молчаливое «всё».
            log.warn("Частичный возврат платежа id={} ({} коп. из {}) съедает весь остаток "
                            + "telegramId={} — срок оставлен без изменений, нужно решение владельца",
                    payment.getId(), refundedKopeks, payment.getAmountKopeks(), payment.getTelegramId());
        }

        // Откатываем реферальные бонусы, если этот платёж был triggering-оплатой —
        // иначе эксплойт: invite → pay → refund → +14/+5 дней остаются (см. 008-миграцию).
        // Только при полном возврате: бонусные дни начислялись ОБОИМ участникам за факт
        // оплаты, и частичный возврат этот факт не отменяет.
        if (fullRefund) {
            revertReferralBonusesIfAny(payment, now);
        }

        // Откатываем PromoUsage, если к этому платежу был применён промокод. Идемпотентно
        // через reverted_at: повторный refund-webhook не задвоит decrement used_count.
        // Ошибка отката не должна ломать основной refund-эффект — try/catch внутри сервиса.
        try {
            promoCodeService.revertForPayment(payment.getId());
        } catch (Exception e) {
            log.warn("Failed to revert promo usage for payment {}", payment.getId(), e);
        }

        return new RefundOutcome(disabled, effectiveExpiry, null);
    }

    /**
     * Сколько дней срока снять за возврат {@code refundedKopeks} по платежу за {@code plan}.
     * <p>
     * Возврат бывает частичным: владелец возвращает часть суммы в ЛК YooKassa, банк проводит
     * частичный chargeback. Снимать за него весь оплаченный период нельзя — 100 ₽, возвращённые
     * с годовой оплаты, отбирали у человека год.
     * <p>
     * Доля считается на {@link BigDecimal} с округлением HALF_UP: возврат дороже половины суток
     * стоит человеку сутки, дешевле — не стоит ничего. Целочисленное деление здесь давало бы 0
     * дней почти на любом частичном возврате, то есть возврат вообще ничего не отбирал бы.
     * Результат клампится в {@code [0, plan.days]}: возврат больше суммы платежа (YooKassa
     * такого не допускает, но сумма приходит извне) не должен резать больше, чем дал платёж.
     * <p>
     * {@code amount_kopeks <= 0} — битая строка платежа, долю считать не из чего: снимаем весь
     * план, то есть ведём себя ровно как до появления пропорции.
     */
    private static int revokedDays(AppConfig.Plan plan, Payment payment, long refundedKopeks) {
        int planDays = plan.getDays();
        long paidKopeks = payment.getAmountKopeks();
        if (planDays <= 0) {
            return 0;
        }
        if (paidKopeks <= 0) {
            return planDays;
        }
        if (refundedKopeks <= 0) {
            return 0;
        }
        if (refundedKopeks >= paidKopeks) {
            return planDays;
        }
        int days = BigDecimal.valueOf(planDays)
                .multiply(BigDecimal.valueOf(refundedKopeks))
                .divide(BigDecimal.valueOf(paidKopeks), 0, RoundingMode.HALF_UP)
                .intValueExact();
        return Math.max(0, Math.min(planDays, days));
    }

    /**
     * Считается ли возврат ПОЛНЫМ — вернули не меньше {@link #FULL_REFUND_PERCENT}% суммы
     * платежа. Только полный возврат тянет за собой необратимые эффекты (отключение доступа,
     * откат ступени устройств, откат реферальных бонусов).
     * <p>
     * {@code amount_kopeks <= 0} — сравнивать не с чем: считаем возврат полным, то есть
     * сохраняем поведение, которое было до появления порога.
     */
    private static boolean isFullRefund(Payment payment, long refundedKopeks) {
        long paidKopeks = payment.getAmountKopeks();
        if (paidKopeks <= 0) {
            return true;
        }
        return refundedKopeks * 100L >= paidKopeks * (long) FULL_REFUND_PERCENT;
    }

    /**
     * Возврат тарифной оплаты со ступенью откатывает и лимит устройств — иначе self-refund
     * семейного тарифа при стэкнутой подписке оставлял бы бесплатный семейный лимит на весь
     * оставшийся срок. Новый лимит = ступень последней ОСТАВШЕЙСЯ успешной плановой оплаты;
     * таких нет → как до фичи (NULL = без лимита; уместно и для legacy, и для триала).
     * Платёж уже помечен REFUNDED ({@code markPaymentRefunded} выше) — из поиска выпадает сам.
     */
    private void rollbackDeviceTierAfterRefund(Payment refunded) {
        rollbackDeviceTierAfterRefund(refunded, null);
    }

    /**
     * @param parked подписка возврата, если она НЕ работающая: лимит откатывается на её строке,
     *               {@code customers} не трогаем вовсе. Иначе возврат за спящую подписку
     *               переставил бы лимит устройств у работающей — по последнему платежу чужой
     *               подписки, то есть просто мимо
     */
    private void rollbackDeviceTierAfterRefund(Payment refunded, Subscription parked) {
        if (refunded.getDeviceTierId() == null) {
            return;
        }
        if (parked != null) {
            rollbackTierOnParked(refunded, parked);
            return;
        }
        userRepository.findByTelegramId(refunded.getTelegramId()).ifPresent(user -> {
            Payment lastTierPayment = paymentRepository
                    .findTop20ByTelegramIdOrderByCreatedAtDesc(refunded.getTelegramId()).stream()
                    .filter(p -> p.getStatus() == Payment.Status.SUCCEEDED)
                    .filter(p -> !p.isDeviceAddon())
                    .filter(p -> p.getDeviceTierId() != null)
                    .filter(p -> sameSubscription(p, refunded))
                    .findFirst()
                    .orElse(null);
            // Докупленные слоты («+1 устройство») откатывать НЕЛЬЗЯ: за них платили отдельным
            // платежом, который здесь не возвращается (addon-возврат идёт своей веткой,
            // applyAddonRefund). Считаем их как превышение текущего лимита над ступенью,
            // действовавшей ДО отката — та же арифметика, что в DeviceTrackingService.addonQuote.
            int extraSlots = addonSlotsOf(user);
            if (lastTierPayment != null) {
                AppConfig.DeviceTier tier =
                        appConfig.findDeviceTierOptional(lastTierPayment.getDeviceTierId()).orElse(null);
                user.setDeviceTierId(lastTierPayment.getDeviceTierId());
                user.setDeviceLimit(tier != null
                        ? tier.getDevices() + extraSlots
                        : user.getDeviceLimit());
            } else {
                user.setDeviceTierId(null);
                user.setDeviceLimit(null);
            }
            userRepository.save(user);
            // Лимит мог упасть — снимаем лишние устройства со слотов (иначе откат ступени
            // менял бы только цифру в БД, а доступ оставался бы прежним). Снимаем в пределах
            // РАБОТАЮЩЕЙ подписки: счёт слотов давно per-subscription, и версия «по человеку»
            // выбила бы из слотов ещё и устройства другой, ни при чём не бывшей подписки.
            Subscription current = subscriptionRepository
                    .findByTelegramIdAndCurrentTrue(refunded.getTelegramId()).orElse(null);
            if (current != null) {
                accessService.enforceDeviceLimit(current, user.getDeviceLimit());
            } else {
                accessService.enforceDeviceLimit(refunded.getTelegramId(), user.getDeviceLimit());
            }
            accessService.mirrorSubscription(refunded.getTelegramId());
            log.info("Refund of tiered payment id={} → device tier rolled back to {} (limit {}, "
                            + "{} докупленных слот(ов) сохранено) for telegramId={}",
                    refunded.getId(), user.getDeviceTierId(), user.getDeviceLimit(),
                    extraSlots, refunded.getTelegramId());
        });
    }

    /**
     * Тот же откат ступени, но на строке ЗАМОРОЖЕННОЙ подписки. Отдельным методом, а не
     * ветками внутри общего: у припаркованной подписки нет ни синка с панелью, ни отражения
     * в {@code customers} — попытка переиспользовать общий код кончилась бы тем, что одна из
     * этих двух вещей всё-таки выполнилась бы для не той подписки.
     */
    private void rollbackTierOnParked(Payment refunded, Subscription parked) {
        Payment lastTierPayment = paymentRepository
                .findTop20ByTelegramIdOrderByCreatedAtDesc(refunded.getTelegramId()).stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCEEDED)
                .filter(p -> !p.isDeviceAddon())
                .filter(p -> p.getDeviceTierId() != null)
                .filter(p -> sameSubscription(p, refunded))
                .findFirst()
                .orElse(null);
        int extraSlots = addonSlotsOf(parked.getDeviceTierId(), parked.getDeviceLimit());
        if (lastTierPayment != null) {
            AppConfig.DeviceTier tier =
                    appConfig.findDeviceTierOptional(lastTierPayment.getDeviceTierId()).orElse(null);
            parked.setDeviceTierId(lastTierPayment.getDeviceTierId());
            parked.setDeviceLimit(tier != null ? tier.getDevices() + extraSlots : parked.getDeviceLimit());
        } else {
            parked.setDeviceTierId(null);
            parked.setDeviceLimit(null);
        }
        subscriptionRepository.save(parked);
        // Лишние устройства снимаем со слотов ТОЛЬКО у этой подписки.
        accessService.enforceDeviceLimit(parked, parked.getDeviceLimit());
        log.info("Возврат id={} → ступень подписки id={} откачена до {} (лимит {})",
                refunded.getId(), parked.getId(), parked.getDeviceTierId(), parked.getDeviceLimit());
    }

    /**
     * Платежи разных подписок не должны участвовать в откате ступени друг друга: иначе возврат
     * за подписку #2 переставил бы лимит по последнему платежу подписки #1.
     * <p>
     * У возвращаемого платежа нет цели (историческая строка) — сравнивать не с чем, и мы
     * оставляем сегодняшнее поведение «по всем платежам человека»: у него подписка одна.
     */
    private static boolean sameSubscription(Payment candidate, Payment refunded) {
        Long target = refunded.getSubscriptionId();
        if (target == null) {
            return true;
        }
        // ⚠️ Кандидат без цели тоже подходит. Колонка появилась только на этапе 5, и все
        // платежи старше неё относятся к единственной подписке, которая у человека тогда была.
        // Отбросить их значило бы: возврат за вторую оплату не находит первую, решает, что
        // «оплат со ступенью не осталось», и снимает лимит устройств совсем — у человека,
        // который за него заплатил и продолжает пользоваться подпиской.
        Long candidateTarget = candidate.getSubscriptionId();
        return candidateTarget == null || target.equals(candidateTarget);
    }

    /**
     * Сколько слотов юзер докупил сверх своей ступени («+1 устройство»): превышение
     * {@code device_limit} над {@code devices} текущей ступени. Ступень неизвестна/удалена
     * из конфига — считаем, что докупок нет (занижать безопаснее, чем раздать лишнее).
     */
    private int addonSlotsOf(Customer user) {
        return addonSlotsOf(user.getDeviceTierId(), user.getDeviceLimit());
    }

    /** То же по паре «ступень + лимит» — подписка хранит их у себя, а не в {@code customers}. */
    private int addonSlotsOf(String tierId, Integer deviceLimit) {
        if (deviceLimit == null) {
            return 0;
        }
        return appConfig.findDeviceTierOptional(tierId)
                .map(t -> Math.max(0, deviceLimit - t.getDevices()))
                .orElse(0);
    }

    /**
     * Если refund коснулся оплаты, по которой начислялись реферальные бонусы,
     * откатываем их у обоих (referrer и referee). Идемпотентно через bonus_reverted_at.
     * Ошибки проглатываем: основной refund-эффект уже применён, ломать его из-за
     * частичной неудачи отката нельзя.
     */
    private void revertReferralBonusesIfAny(Payment payment, LocalDateTime now) {
        try {
            Optional<Referral> opt = referralRepository.findByPaymentId(payment.getId());
            if (opt.isEmpty()) return;
            Referral ref = opt.get();
            // paid_at=NULL → награды не начислялись (ни одной оплаты не было).
            // bonus_reverted_at != NULL → уже откатили, дубль-webhook.
            if (ref.getPaidAt() == null || ref.getBonusRevertedAt() != null) return;
            revertBonusForUser(ref.getReferredTelegramId(), ref.getRefereeBonusDays(), now);
            revertBonusForUser(ref.getReferrerTelegramId(), ref.getRewardBonusDays(), now);
            ref.setBonusRevertedAt(now);
            referralRepository.save(ref);
            log.info("Reverted referral bonuses for payment id={}: referrer={} (-{}d), referee={} (-{}d)",
                    payment.getId(),
                    ref.getReferrerTelegramId(), ref.getRewardBonusDays(),
                    ref.getReferredTelegramId(), ref.getRefereeBonusDays());
        } catch (Exception e) {
            log.warn("Failed to revert referral bonuses for payment {}", payment.getId(), e);
        }
    }

    /**
     * Вычитает bonusDays из expires_at пользователя. Если результат в прошлом —
     * полный disable (как и для основного refund-пути). Если Customer нет / expires_at нет —
     * skip с warn (edge case: referrer никогда не заходил, его Customer создался в
     * maybeAwardReferral и ему выдали 14д, но тут мы ориентируемся на фактическое состояние).
     */
    private void revertBonusForUser(Long telegramId, Integer bonusDays, LocalDateTime now) {
        if (bonusDays == null || bonusDays <= 0) return;
        Customer u = userRepository.findByTelegramId(telegramId).orElse(null);
        if (u == null || u.getExpiresAt() == null) {
            log.warn("Skipping referral bonus revert for telegram_id={} (user or expires_at missing)", telegramId);
            return;
        }
        LocalDateTime candidate = u.getExpiresAt().minusDays(bonusDays);
        if (candidate.isBefore(now)) {
            accessService.disableAccess(telegramId);
        } else {
            accessService.setExpiry(telegramId, candidate);
        }
    }

    /**
     * Фактически возвращённая сумма в копейках из ПОДТВЕРЖДЁННОГО ответа API возврата.
     * Раньше сумма читалась из тела вебхука, который никем не подписан — то есть отправитель
     * мог назвать любую сумму возврата. {@code fallbackKopeks} (полная сумма платежа) — если
     * YooKassa не прислала блок amount.
     */
    private long amountToKopeks(YooKassaClient.Amount amount, long fallbackKopeks) {
        try {
            if (amount != null && amount.getValue() != null) {
                return new BigDecimal(amount.getValue()).movePointRight(2).longValueExact();
            }
        } catch (Exception e) {
            log.warn("Failed to parse refund amount '{}', falling back to full payment amount",
                    amount.getValue(), e);
        }
        return fallbackKopeks;
    }

    /** Помечает платёж как REFUNDED с указанной суммой возврата. */
    private void markPaymentRefunded(Payment payment, long refundedKopeks) {
        payment.setStatus(Payment.Status.REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        payment.setRefundAmountKopeks(refundedKopeks);
        paymentRepository.save(payment);
    }

    @Transactional
    public void handleCanceled(String yookassaPaymentId) {
        Payment payment = paymentRepository.findByYookassaPaymentIdForUpdate(yookassaPaymentId).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.PENDING) {
            return;
        }

        // Re-verify через API: webhook не аутентифицирован. Без проверки атакующий, знающий
        // yookassa_payment_id, мог бы спуфить payment.canceled и выбить чужой PENDING-платёж из
        // recovery-набора реконсилера (тот фильтрует по status=PENDING). Заодно достаём
        // cancellation_details для аналитики. API недоступно / статус не canceled → не трогаем.
        YooKassaClient.PaymentResponse actual;
        try {
            actual = yooKassaClient.getPayment(yookassaPaymentId);
        } catch (Exception e) {
            log.warn("Cannot verify cancel for {}, leaving PENDING for reconciler", yookassaPaymentId, e);
            return;
        }
        if (actual == null || !"canceled".equals(actual.getStatus())) {
            log.warn("Refusing to cancel {} — API status={}",
                    yookassaPaymentId, actual == null ? "null" : actual.getStatus());
            return;
        }

        payment.setStatus(Payment.Status.CANCELED);
        paymentRepository.save(payment);

        // Счёт отменён — резерв промокода возвращаем человеку сразу, не дожидаясь, пока он
        // протухнет сам. Иначе отказ банка на одном чекауте лишал бы человека собственного
        // кода на сутки. Ошибка отката не должна мешать отметить платёж отменённым.
        if (payment.getPromoCodeUsed() != null) {
            try {
                promoCodeService.releaseReservation(payment.getId());
            } catch (Exception e) {
                log.warn("Failed to release promo reservation for canceled payment {}",
                        payment.getId(), e);
            }
        }
        log.info("Marked payment {} as CANCELED", yookassaPaymentId);
    }

    /**
     * Обработка webhook "invoice_paid" от CryptoBot.
     * Структурно повторяет {@link #handleSucceeded(String)}, но с тремя отличиями:
     * <ol>
     *   <li>Ищем Payment по cryptoInvoiceId (не yookassaPaymentId).</li>
     *   <li>Ре-верифицируем через {@link CryptoBotClient#getInvoice}; статус должен быть "paid".</li>
     * </ol>
     */
    @Transactional
    public void handleCryptoSucceeded(String cryptoInvoiceId) {
        // Lock-on-read (как в handleSucceeded) — атомарная статус-проверка против дублей доставки.
        Payment payment = paymentRepository.findByCryptoInvoiceIdForUpdate(cryptoInvoiceId).orElse(null);
        if (payment == null) {
            log.warn("Received webhook for unknown CryptoBot invoice {}", cryptoInvoiceId);
            return;
        }

        if (payment.getStatus() == Payment.Status.SUCCEEDED) {
            log.info("Crypto invoice {} already processed, skipping", cryptoInvoiceId);
            return;
        }

        // Ре-верификация через API CryptoBot — несмотря на HMAC-подпись webhook,
        // не помешает убедиться, что статус действительно paid на стороне провайдера.
        CryptoBotClient.InvoiceResponse actual = cryptoBotClient.getInvoice(cryptoInvoiceId);
        if (actual == null || !"paid".equals(actual.getStatus())) {
            log.warn("Crypto invoice {} status mismatch: webhook=paid api={}",
                    cryptoInvoiceId, actual == null ? "null" : actual.getStatus());
            return;
        }

        if (payment.isDeviceAddon()) {
            completeDeviceAddon(payment);
            return;
        }

        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(payment.getPlanId());
        } catch (IllegalArgumentException e) {
            log.error("Unknown plan {} on crypto payment {}", payment.getPlanId(), cryptoInvoiceId);
            return;
        }

        Landing landing = activateInto(payment, plan, resolveDeviceTier(payment));

        payment.setStatus(Payment.Status.SUCCEEDED);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // Оплата криптой у юзера с привязанной картой тоже переустанавливает, ЧТО будет
        // автопродлеваться — иначе карта продлит прошлый тариф с прошлым лимитом устройств.
        // Только если деньги легли в работающую подписку: см. гард в handleSucceeded.
        if (landing.intoCurrent()) {
            syncAutorenewSelection(landing.user(), payment);
        }

        recordPromoUsageIfApplicable(payment, plan);

        // Реферальная программа — см. handleSucceeded для логики.
        referralService.maybeAwardReferral(payment.getTelegramId(), payment.getId());

        announceSuccess(payment, plan, landing);
        log.info("Processed succeeded crypto invoice {} for telegramId={} (paidAsset={}, paidAmount={})",
                cryptoInvoiceId, payment.getTelegramId(), actual.getPaidAsset(), actual.getPaidAmount());
    }

    /**
     * Обработка успешной оплаты Telegram Stars.
     * <p>
     * Вызывается из {@link com.orchestra.subscriptions.bot.SubscriptionBot} при получении {@code successful_payment}
     * в message-апдейте — Telegram шлёт это уведомление напрямую боту (не через webhook).
     * Повторная верификация не нужна: апдейт пришёл от Telegram по доверенному каналу (Bot API),
     * а подпись успешной оплаты — сам {@code telegram_payment_charge_id}, который Telegram
     * генерирует только при реальном списании средств.
     * <p>
     * Stars идут через Telegram Fragment, поэтому рублёвой суммы у такого платежа нет —
     * в отчётности он учитывается отдельно от эквайринга
     * после вывода.
     *
     * @param invoicePayload   наш {@code payment.id}, который мы передали в sendInvoice.payload
     * @param telegramChargeId {@code telegram_payment_charge_id} — уникальный id списания, нужен
     *                         для возможных рефандов через {@code refundStarPayment}
     * @param totalStars       фактически списанная сумма в Stars, для логов и аудита
     */
    @Transactional
    public void handleStarsSucceeded(String invoicePayload, String telegramChargeId, long totalStars) {
        Long paymentId;
        try {
            paymentId = Long.parseLong(invoicePayload);
        } catch (NumberFormatException e) {
            log.warn("Stars successful_payment with non-numeric payload: {}", invoicePayload);
            return;
        }

        // Долговечно фиксируем charge id в ОТДЕЛЬНОЙ committed-транзакции ДО активации.
        // У Stars нет webhook-передоставки: если активация ниже упадёт (AccessProvider недоступен) и
        // транзакция откатится, charge не должен потеряться — PaymentReconcileService ретраит
        // активацию по нему (findStaleStarsPending фильтрует по telegram_charge_id IS NOT NULL).
        // Идемпотентно: повторная доставка / реконсилер не перезапишут уже выставленный id.
        starsChargePersister.persistChargeId(paymentId, telegramChargeId);

        // Lock-on-read: сериализует параллельные доставки одного платежа (M2) — второй поток
        // дождётся коммита первого и увидит SUCCEEDED, не задвоив активацию и нотификации.
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Stars successful_payment for unknown payment id {}", paymentId);
            return;
        }
        if (payment.getProvider() != Payment.Provider.STARS) {
            log.warn("Stars successful_payment on non-STARS payment {} (provider={})",
                    paymentId, payment.getProvider());
            return;
        }
        if (payment.getStatus() == Payment.Status.SUCCEEDED) {
            log.info("Stars payment {} already processed, skipping", paymentId);
            return;
        }

        if (payment.isDeviceAddon()) {
            completeDeviceAddon(payment);
            return;
        }

        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(payment.getPlanId());
        } catch (IllegalArgumentException e) {
            log.error("Unknown plan {} on stars payment {}", payment.getPlanId(), paymentId);
            return;
        }

        Landing landing = activateInto(payment, plan, resolveDeviceTier(payment));

        payment.setStatus(Payment.Status.SUCCEEDED);
        payment.setPaidAt(LocalDateTime.now());
        payment.setTelegramChargeId(telegramChargeId);
        paymentRepository.save(payment);

        // Оплата Stars у юзера с привязанной картой тоже переустанавливает, ЧТО будет
        // автопродлеваться (см. syncAutorenewSelection) — и тоже только в текущую подписку.
        if (landing.intoCurrent()) {
            syncAutorenewSelection(landing.user(), payment);
        }

        recordPromoUsageIfApplicable(payment, plan);

        // Реферальная программа — см. handleSucceeded для логики.
        referralService.maybeAwardReferral(payment.getTelegramId(), payment.getId());

        announceSuccess(payment, plan, landing);
        log.info("Processed succeeded stars payment id={} telegramId={} totalStars={} chargeId={}",
                paymentId, payment.getTelegramId(), totalStars, telegramChargeId);
    }

    /**
     * Записывает применение промокода к платежу, если он был указан в чекауте.
     * Скидка считается от ОРИГИНАЛЬНОЙ цены плана (не от {@code payment.amountKopeks},
     * который уже мог быть пересчитан со скидкой), чтобы PromoUsage.discount_kopeks
     * был корректным абсолютным значением.
     * <p>
     * Ошибки проглатываем: основной платёж уже SUCCEEDED, ломать его из-за сбоя записи
     * промо нельзя — admin увидит warn в логах и вручную поправит при необходимости.
     */
    private void recordPromoUsageIfApplicable(Payment payment, AppConfig.Plan plan) {
        if (payment.getPromoCodeUsed() == null || payment.getPromoCodeUsed().isBlank()) {
            return;
        }
        try {
            promoCodeService.recordUsageOnSucceeded(
                    payment.getPromoCodeUsed(),
                    // База скидки — цена план×ступень (как в чекауте), не голая цена плана.
                    appConfig.tierPriceKopeks(plan, resolveDeviceTier(payment)),
                    payment.getTelegramId(),
                    payment.getId());
        } catch (Exception e) {
            log.warn("Failed to record promo usage for payment id={} code={}",
                    payment.getId(), payment.getPromoCodeUsed(), e);
        }
    }

    /**
     * Автопродление должно продлевать то, что юзер купил ПОСЛЕДНИМ, а не то, на чём он
     * когда-то поставил галочку «с автопродлением».
     * <p>
     * Без этого: купил «1 месяц · 2 устройства» с галочкой → потом «6 месяцев · семейная ×5»
     * без галочки (галочка выключена по умолчанию, а в чате её нет вовсе) → через полгода
     * автосписание молча возвращало «1 месяц · 2 устройства»: юзер платит за месяц вместо
     * полугода И теряет 3 устройства ({@code activatePaidSubscription} переписывает
     * {@code device_limit} из ступени). Теперь любая успешная оплата плана переустанавливает,
     * ЧТО будет продлеваться.
     * <p>
     * Только для юзеров с уже привязанной картой ({@code autorenewMethodId != null}) —
     * оплата без карты не должна создавать автопродление из воздуха. Флаг
     * {@code autorenewEnabled} не трогаем: выключил юзер — значит выключено.
     * Докупка слота ({@code device_addon}) сюда не попадает — она отсекается раньше.
     */
    private void syncAutorenewSelection(Customer customer, Payment payment) {
        if (customer == null || customer.getAutorenewMethodId() == null) {
            return;
        }
        boolean planChanged = !java.util.Objects.equals(customer.getAutorenewPlanId(), payment.getPlanId());
        boolean tierChanged = !java.util.Objects.equals(
                customer.getAutorenewDeviceTierId(), payment.getDeviceTierId());
        if (!planChanged && !tierChanged) {
            return;
        }
        customer.setAutorenewPlanId(payment.getPlanId());
        customer.setAutorenewDeviceTierId(payment.getDeviceTierId());
        userRepository.save(customer);
        log.info("Autorenew selection follows last purchase for telegramId={}: plan={} tier={}",
                payment.getTelegramId(), payment.getPlanId(), payment.getDeviceTierId());
    }

    /**
     * Сообщает об успешной оплате — человеку и владельцу. Единая точка для всех трёх
     * провайдеров: тексты про оплату разъезжались бы иначе по способу оплаты.
     * <p>
     * <b>Деньги, легшие не в работающую подписку, требуют другого текста.</b> Обычное «подписка
     * активна до такой-то даты» в этом случае — прямая ложь: срок работающей подписки не
     * изменился, а оплаченные дни лежат замороженными в другой. Человек, прочитавший привычный
     * текст, решит, что всё в порядке, и заметит подмену в худший момент — когда доступ выключится.
     */
    /**
     * Извещает об успешной оплате — но ПОСЛЕ коммита, а не внутри транзакции.
     * <p>
     * Зачем отложено. Внутри уходят DM в Telegram и письмо; и то и другое — сеть, которая
     * может залипнуть. Пока она залипает, транзакция открыта и держит блокировку строки
     * платежа, взятую в {@code findByYookassaPaymentIdForUpdate}. 31.08.2026 так и вышло:
     * SMTP-узел провайдера российский, main до России достучаться перестал, соединение
     * установилось и замолчало, а таймаутов у JavaMail не было — транзакция провисела 4
     * минуты. За ней в очередь встали повторная доставка вебхука и реконсилер, платёж всё
     * это время оставался PENDING, хотя подписка и чек уже выданы. Таймауты почте проставлены
     * (см. {@code spring.mail.properties} в application.yml), но потолок в 10 секунд — это
     * всё ещё 10 секунд с заблокированной строкой на каждый залипший вызов, а клиент
     * Telegram-бота своих таймаутов не обещает вовсе.
     * <p>
     * Побочная выгода: раньше исключение из уведомления откатывало транзакцию, и платёж
     * возвращался в PENDING уже ПОСЛЕ начисления дней — повторный проход
     * реконсилера выписал бы второй чек на те же деньги. Теперь провал извещения не может
     * отменить оплату.
     * <p>
     * Вне транзакции (прямой вызов из теста) выполняется сразу — иначе уведомление потерялось
     * бы молча.
     */
    private void announceSuccess(Payment payment, AppConfig.Plan plan, Landing landing) {
        afterCommit(() -> announceSuccessNow(payment, plan, landing));
    }

    /**
     * Выполнить действие после успешного коммита текущей транзакции. Нет активной транзакции —
     * выполняем немедленно.
     * <p>
     * Две неочевидности, обе стоили бы уведомлений юзеру.
     * <p>
     * <b>Порядок.</b> Spring гоняет afterCommit-хуки простым циклом без try/catch на каждый
     * (см. {@code TransactionSynchronizationUtils.invokeAfterCommit}) — первый бросивший
     * обрывает остальные. В этой же транзакции хуки регистрируют активация подписки и
     * реферальная программа, и один из них ({@code SubscriptionMintService.settle}) в ветке
     * восстановления зовёт {@code accessSync.push} ничем не обёрнутым. Регистрируйся мы
     * последними — его падение молча съело бы «Оплата получена», причём навсегда: вебхук
     * отвечает 200 и повтора не будет, а реконсилер берёт только PENDING. Поэтому
     * {@link #getOrder()} = наивысший приоритет: извещение уходит первым, как и было до
     * выноса из транзакции.
     * <p>
     * <b>Глушение.</b> Исключение из afterCommit Spring пробрасывает вызывающему уже ПОСЛЕ
     * успешного коммита. У автопродления это ловится как «activation failed, payment left
     * PENDING for reconciler retry» — ложь: платёж закоммичен SUCCEEDED, и реконсилер его не
     * подберёт. Поэтому здесь глушим и логируем: провалившееся уведомление не должно ни
     * врать в логах, ни ронять соседние хуки.
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSwallowing(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public void afterCommit() {
                runSwallowing(action);
            }
        });
    }

    private void runSwallowing(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("Уведомление после коммита не отправлено (платёж при этом проведён)", e);
        }
    }

    private void announceSuccessNow(Payment payment, AppConfig.Plan plan, Landing landing) {
        if (!landing.intoCurrent()) {
            notificationService.sendPaymentIntoOtherSubscription(
                    payment.getTelegramId(), plan, landing.subscription(), landing.targetGoesLive());
            notificationService.notifyOwnerLargePayment(payment, plan);
            return;
        }
        if (payment.isAutorenew()) {
            // Деньги списались сами — юзеру нужен другой текст («Оплата получена» на списание,
            // которого он не инициировал, читается как ошибка) и путь к управлению картой.
            notificationService.sendAutoRenewSuccess(
                    payment.getTelegramId(), plan, payment.getAmountKopeks());
        } else {
            notificationService.sendPaymentSuccess(payment.getTelegramId(), landing.user(), plan);
        }
        // Резервная email-копия — отрабатывает только если юзер сохранил email в Mini App,
        // иначе no-op. Внутри swallow'ит SMTP-ошибки, не ломая платёжный флоу.
        notificationService.sendPaymentSuccessEmail(landing.user(), plan);
        notificationService.notifyOwnerLargePayment(payment, plan);
    }

    /**
     * Успешная оплата «+1 устройство»: SUCCEEDED + инкремент {@code device_limit} через
     * {@link AccessService#addDeviceSlot} (он же синкает AccessProvider ip_limit) + чек (только YK)
     * + DM юзеру. Без промо/рефералки/автопродления/аналитики — это не тарифный план.
     * Идемпотентность обеспечивает вызывающая сторона (уже проверила status != SUCCEEDED).
     * <p>
     * Перед начислением условия докупки и цена перепроверяются заново: счёт выставлялся по
     * состоянию подписки на тот момент, и к оплате оно могло разъехаться с действительностью.
     * Не сошлось — слот не начисляется, платёж уходит на разбор владельцу
     * ({@link #holdDeviceAddon}).
     */
    private void completeDeviceAddon(Payment payment) {
        payment.setStatus(Payment.Status.SUCCEEDED);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // Проверка юзера ДО addDeviceSlot и БЕЗ try/catch вокруг него: addDeviceSlot —
        // вложенный @Transactional (joined tx); его RuntimeException пометил бы транзакцию
        // rollback-only, и «проглотивший» catch кончался бы UnexpectedRollbackException на
        // коммите — платёж вечно PENDING, реконсилер лупится, алерты владельцу каждые 10 мин.
        // Юзер исчез (деньги пришли) → терминально: платёж уже SUCCEEDED (дублей не будет),
        // владельцу один алерт на ручной рефанд.
        Customer addonUser = userRepository.findByTelegramId(payment.getTelegramId()).orElse(null);
        if (addonUser == null) {
            log.error("Device addon paid but user missing: payment={} telegramId={}",
                    payment.getId(), payment.getTelegramId());
            notifyOwnerPlain("⚠️ Оплачен слот устройства, но не начислен (юзер не найден): payment id="
                    + payment.getId() + ", telegramId=" + payment.getTelegramId());
            return;
        }

        // Условия докупки перепроверяются ЗДЕСЬ, а не только на чекауте. Между выставлением
        // счёта и оплатой подписка могла истечь, а потолок доп-слотов — быть выкуплен с другого
        // экрана. Раньше обе ситуации лишь слали алерт владельцу, а слот выдавался всё равно:
        // потолок доп-слотов ничего не ограничивал, а слот к мёртвой подписке продавался — при
        // том, что цена у него pro-rata и к мёртвой подписке неприменима.
        LocalDateTime now = LocalDateTime.now();
        boolean subInactive = addonUser.getStatus() != Customer.Status.ACTIVE
                || addonUser.getExpiresAt() == null || !addonUser.getExpiresAt().isAfter(now);
        int extraAfter = appConfig.findDeviceTierOptional(addonUser.getDeviceTierId())
                .map(t -> (addonUser.getDeviceLimit() != null ? addonUser.getDeviceLimit() : 0)
                        + 1 - t.getDevices())
                .orElse(0);
        boolean overCap = appConfig.getDeviceAddon() != null
                && extraAfter > appConfig.getDeviceAddon().getMaxExtraDevices();
        if (subInactive || overCap) {
            holdDeviceAddon(payment,
                    subInactive ? "подписка уже неактивна" : "превышен потолок доп-слотов");
            return;
        }

        // Цену тоже пересчитываем заново: она pro-rata от ОСТАТКА подписки, то есть меняется
        // каждый день. Счёт на 10 ₽ за хвост подписки, оплаченный уже ПОСЛЕ продления на год,
        // открывал бы годовой слот за 10 ₽ — это и есть инвариант, который здесь защищается:
        // слот выдаётся только за ту цену, сколько он стоит В МОМЕНТ выдачи.
        DeviceTrackingService.AddonQuote quote =
                deviceTrackingService.addonQuote(addonUser).orElse(null);
        if (quote == null) {
            // Сюда попадают только конфигурационные причины: докупка выключена в конфиге или
            // ступень юзера из него удалена (состояние самого юзера проверено выше). Сравнить
            // не с чем — слот начисляем (деньги приняты), но владельцу говорим, что цена
            // осталась непроверенной.
            notifyOwnerPlain("⚠️ Слот устройства начислен БЕЗ проверки цены (payment id="
                    + payment.getId() + ", telegramId=" + payment.getTelegramId()
                    + "): котировка недоступна — докупка выключена в конфиге либо ступень "
                    + "юзера из него удалена. Оплачено " + Money.rub(payment.getAmountKopeks()) + " ₽.");
        } else {
            // Расхождение считаем в обе стороны, но реагируем по-разному.
            // Вниз (заплатили меньше, чем слот стоит сейчас) — не начисляем: деньги мимо цены,
            // ровно тот случай, ради которого перепроверка и делается.
            // Вверх (переплата) слот не блокирует — человек заплатил больше, чем мы просим, и
            // отказать ему значило бы забрать деньги и не дать ничего. Но владельца зовём и
            // сюда: оплачен год слота, а достаётся хвост подписки — повод вернуть разницу.
            long expectedKopeks = quote.priceKopeks();
            long paidKopeks = payment.getAmountKopeks();
            long toleranceKopeks = expectedKopeks * ADDON_QUOTE_TOLERANCE_PERCENT / 100L;
            if (paidKopeks + toleranceKopeks < expectedKopeks) {
                holdDeviceAddon(payment, "оплачено " + Money.rub(paidKopeks)
                        + " ₽, а слот сейчас стоит " + Money.rub(expectedKopeks)
                        + " ₽ (остаток подписки — " + quote.remainingDays() + " дн.)");
                return;
            }
            if (paidKopeks > expectedKopeks + toleranceKopeks) {
                notifyOwnerPlain("⚠️ Слот устройства оплачен дороже текущей котировки (payment id="
                        + payment.getId() + ", telegramId=" + payment.getTelegramId() + "): оплачено "
                        + Money.rub(paidKopeks) + " ₽ при текущей цене " + Money.rub(expectedKopeks)
                        + " ₽ (остаток подписки — " + quote.remainingDays() + " дн.). "
                        + "Слот начислен — оцените, нужен ли возврат разницы.");
            }
        }

        int newLimit = accessService.addDeviceSlot(payment.getTelegramId()).getDeviceLimit();

        Long addonTelegramId = payment.getTelegramId();
        afterCommit(() -> notificationService.sendPlainText(addonTelegramId,
                "✅ Слот устройства добавлен: теперь до " + newLimit + " устройств.\n\n"
                        + "Действует до конца текущей подписки — при продлении лимит снова "
                        + "берётся из тарифа."));
        log.info("Processed device addon payment {} for telegramId={}, newLimit={}",
                payment.getId(), payment.getTelegramId(), newLimit);
    }

    /**
     * Оплата слота принята, но слот НЕ начисляется: условия докупки на момент оплаты не
     * сходятся с теми, по которым выставляли счёт. Инвариант, который это защищает: слот
     * выдаётся только живой подписке и только за ту цену, сколько он стоит в момент выдачи —
     * иначе устаревший счёт открывает доступ дешевле прейскуранта, а потолок доп-слотов
     * перестаёт быть потолком.
     * <p>
     * Платёж остаётся SUCCEEDED: деньги пришли, а откат статуса заставил бы реконсилер вечно
     * ретраить ту же выдачу с алертом каждые 10 минут (та же причина, что у ветки «юзер не
     * найден» выше).
     */
    private void holdDeviceAddon(Payment payment, String reason) {
        notifyOwnerPlain("⛔ Слот устройства оплачен, но НЕ начислен (payment id="
                + payment.getId() + ", telegramId=" + payment.getTelegramId() + "): " + reason
                + ". Сумма: " + Money.rub(payment.getAmountKopeks())
                + " ₽. Начислите слот вручную или сделайте возврат.");
        Long heldTelegramId = payment.getTelegramId();
        afterCommit(() -> notificationService.sendPlainText(heldTelegramId,
                "Оплата получена, слот устройства не начислен: условия докупки изменились "
                        + "после выставления счёта.\n\n"
                        + "Платёж передан на ручную проверку. Начисление слота или возврат "
                        + "выполнит владелец сервиса."));
        log.warn("Device addon held (not granted): payment={} telegramId={} reason={}",
                payment.getId(), payment.getTelegramId(), reason);
    }

    /**
     * Возврат за докупку слота: платёж REFUNDED, {@code device_limit} откатывается на 1,
     * но не ниже базы ступени тарифа (если ступень удалена из конфига — не ниже 1).
     * Срок подписки и карта не трогаются (в отличие от плановых возвратов).
     * <p>
     * <b>Слот отбирается только при ПОЛНОМ возврате</b> (см. {@link #FULL_REFUND_PERCENT}) —
     * тот же порог, что у отключения доступа, отката ступени и реферальных бонусов. Слот
     * неделим: пропорция к нему неприменима, но и «вернули 50 ₽ из 300 — забираем устройство
     * целиком» неверно. Поэтому частичный возврат оставляет слот и зовёт владельца: сколько
     * снять в такой ситуации — его решение, а не молчаливое «всё».
     */
    private void applyAddonRefund(Payment payment, long refundedKopeks) {
        payment.setStatus(Payment.Status.REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        payment.setRefundAmountKopeks(refundedKopeks);
        paymentRepository.save(payment);

        boolean fullRefund = isFullRefund(payment, refundedKopeks);
        String partialNote = "\n\n⚠️ Возврат ЧАСТИЧНЫЙ (" + Money.rub(refundedKopeks) + " ₽ из "
                + Money.rub(payment.getAmountKopeks()) + " ₽), поэтому слот оставлен: "
                + "устройство неделимо, и забирать его целиком за часть денег нельзя. "
                + "Решите, снимать ли слот вручную.";

        // Слот докупался к КОНКРЕТНОЙ подписке (цена считалась pro-rata из её остатка) —
        // и снимать его надо у неё же. Возврат слота спящей подписки, уменьшивший лимит
        // работающей, отключил бы человеку устройство, за которое он платит.
        Subscription parked = refundTargetIfNotCurrent(payment);
        if (parked != null) {
            if (fullRefund) {
                int floor = appConfig.findDeviceTierOptional(parked.getDeviceTierId())
                        .map(AppConfig.DeviceTier::getDevices)
                        .orElse(1);
                int current = parked.getDeviceLimit() != null ? parked.getDeviceLimit() : floor;
                parked.setDeviceLimit(Math.max(floor, current - 1));
                subscriptionRepository.save(parked);
                accessService.enforceDeviceLimit(parked, parked.getDeviceLimit());
            }
            notifyOwnerPlain("💸 Возврат за слот устройства (замороженная подписка #"
                    + parked.getSeq() + "): payment id=" + payment.getId()
                    + ", telegramId=" + payment.getTelegramId()
                    + ", " + Money.rub(refundedKopeks) + " ₽. "
                    + (fullRefund ? "Лимит подписки уменьшен." : "Лимит подписки НЕ изменён.")
                    + (fullRefund ? "" : partialNote));
            log.info("Processed device addon refund for payment {} telegramId={} (подписка id={}, "
                            + "полный={}, лимит {})",
                    payment.getId(), payment.getTelegramId(), parked.getId(),
                    fullRefund, parked.getDeviceLimit());
            return;
        }

        if (fullRefund) {
            userRepository.findByTelegramId(payment.getTelegramId()).ifPresent(user -> {
                int floor = appConfig.findDeviceTierOptional(user.getDeviceTierId())
                        .map(AppConfig.DeviceTier::getDevices)
                        .orElse(1);
                int current = user.getDeviceLimit() != null ? user.getDeviceLimit() : floor;
                user.setDeviceLimit(Math.max(floor, current - 1));
                userRepository.save(user);
                // Слот оплачен возвратом — снимаем с него устройство, иначе вернули бы деньги,
                // а доступ у лишнего устройства остался бы до конца срока.
                accessService.enforceDeviceLimit(user.getTelegramId(), user.getDeviceLimit());
                accessService.mirrorSubscription(user.getTelegramId());
            });
        }

        notifyOwnerPlain("💸 Возврат за слот устройства: payment id=" + payment.getId()
                + ", telegramId=" + payment.getTelegramId()
                + ", " + Money.rub(refundedKopeks) + " ₽. "
                + (fullRefund ? "Лимит юзера уменьшен." : "Лимит юзера НЕ изменён.")
                + (fullRefund ? "" : partialNote));
        log.info("Processed device addon refund for payment {} telegramId={} (полный={})",
                payment.getId(), payment.getTelegramId(), fullRefund);
    }

    /** Плоский owner-алерт без HTML — для addon-веток, где plan-объекта нет. */
    /**
     * Алерт владельцу — тоже после коммита: это сеть внутри транзакции, держащей блокировку
     * строки платежа. Глушение исключений оставлено (его делает {@link #afterCommit}).
     */
    private void notifyOwnerPlain(String text) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        afterCommit(() -> notificationService.sendPlainText(ownerId, text));
    }

    /**
     * Куда в итоге легли деньги. Нужно вызывающим для двух решений: каким текстом отвечать
     * человеку и можно ли переустанавливать настройки автопродления.
     *
     * @param user           человек (строка {@code customers}) — она общая для всех подписок
     * @param subscription   подписка-получатель; {@code null} только у первой покупки человека,
     *                       у которого зеркала ещё нет
     * @param intoCurrent    деньги легли в подписку, которая работает у человека прямо сейчас
     * @param targetGoesLive деньги легли в НЕ-текущую подписку, но живой текущей у человека нет,
     *                       поэтому эта подписка включится сразу после коммита. Влияет на текст
     *                       письма: сказать «она заморожена, Ваш доступ работает» человеку, у
     *                       которого доступ как раз не работает, — прямая ложь
     */
    private record Landing(Customer user, Subscription subscription,
                           boolean intoCurrent, boolean targetGoesLive) {}

    /**
     * Начисляет оплаченные дни В ЦЕЛЬ ПЛАТЕЖА — подписку, записанную на строке при создании
     * счёта ({@code payments.subscription_id}). Единственная точка активации для всех трёх
     * провайдеров: разъехавшиеся копии этой логики означали бы, что деньги ложатся по-разному
     * в зависимости от способа оплаты.
     * <p>
     * Три ветки:
     * <ol>
     *   <li><b>цель — текущая подписка</b> (и все исторические платежи без цели): сегодняшний
     *       путь байт-в-байт;</li>
     *   <li><b>цель — припаркованная подписка</b>: дни падают в её замороженный остаток,
     *       панель не трогается вовсе — у припаркованной подписки нет живого аккаунта;</li>
     *   <li><b>цель — новая подписка</b>: {@link SubscriptionMintService}.</li>
     * </ol>
     * Ветки 2 и 3 достижимы только при включённом флаге {@code app.multi-subscription}, потому
     * что цель проставляется на чекауте, а он их без флага не выбирает.
     * <p>
     * <b>Ни одна ветка не оставляет платёж PENDING.</b> Порченая цель (висячий id) — не повод
     * не выдать оплаченное: деньги уже приняты, и «оплатил, а дней нет» хуже, чем «дни легли
     * в текущую подписку, владельцу ушёл алерт».
     */
    private Landing activateInto(Payment payment, AppConfig.Plan plan, AppConfig.DeviceTier tier) {
        Long telegramId = payment.getTelegramId();
        Subscription current = subscriptionRepository
                .findByTelegramIdAndCurrentTrue(telegramId).orElse(null);
        Long targetId = payment.getSubscriptionId();

        // Цель ещё не материализована: либо это исторический платёж (колонки не было), либо
        // первая покупка человека, либо платёж, которым открывают отдельную подписку.
        if (targetId == null) {
            if (opensSeparateSubscription(payment, telegramId)) {
                Subscription minted = subscriptionMintService.mint(payment, plan.getDays(), tier);
                return new Landing(loadUser(telegramId), minted, false, !isLive(current, LocalDateTime.now()));
            }
            return new Landing(activateCurrent(payment, plan, tier), current, true, false);
        }

        if (current != null && targetId.equals(current.getId())) {
            return new Landing(activateCurrent(payment, plan, tier), current, true, false);
        }

        // Порядок замков в проекте един: платёж → customers → subscriptions. Строка человека
        // блокируется здесь, хотя дни в неё не пишутся: дальше по методу её всё равно правит
        // привязка карты, и захват в обратном порядке дал бы взаимную блокировку с
        // переключением подписок (оно берёт замки именно в этом порядке).
        userRepository.findByTelegramIdForUpdate(telegramId);
        // Блокировка строки подписки: параллельная повторная доставка не прибавит дни дважды.
        Subscription target = subscriptionRepository.findOwnedForUpdate(targetId, telegramId).orElse(null);
        if (target == null || target.isCurrent()) {
            // Висячий id (подписку удалили руками) или гонка, в которой цель успела стать
            // текущей между двумя чтениями. И то и другое — в текущую, с алертом владельцу.
            reportTargetAnomaly(payment, "платёж нацелен на подписку id=" + targetId
                    + ", а её " + (target == null ? "нет" : "уже включили") + " — зачисляю в текущую");
            return new Landing(activateCurrent(payment, plan, tier), current, true, false);
        }

        depositIntoParked(target, payment, plan, tier);
        return new Landing(loadUser(telegramId), target, false, !isLive(current, LocalDateTime.now()));
    }

    /**
     * Этим платежом открывают ОТДЕЛЬНУЮ подписку.
     * <p>
     * Проверяется ИМЕННО {@code SEPARATE}, а не «новая подписка вообще»: у первой покупки
     * человека цель тоже пустая, но активировать её обязан сегодняшний путь. Разница особенно
     * важна для зависших счетов — человек мог открыть счёт до появления подписки (первая
     * покупка), а оплатить его уже после того, как подписка появилась (со второй попытки, после
     * триала, догоном реконсилера). Такой платёж обязан продлить работающую подписку, а не
     * заморозить деньги в новой, и произойти это должно даже при выключенном флаге.
     * <p>
     * Второе условие — страховка: {@code SEPARATE} без единой существующей подписки невозможен
     * (резолвер такого не выдаёт), но если он всё же случится, «отдельная» подписка человека без
     * подписок — это его первая, и заводить её надо обычным путём.
     */
    private boolean opensSeparateSubscription(Payment payment, Long telegramId) {
        return com.orchestra.subscriptions.service.payment.PaymentTarget.Kind.SEPARATE.name()
                .equals(payment.getTargetKind())
                && subscriptionRepository.countByTelegramId(telegramId) > 0;
    }

    /**
     * Подписка платежа, если это НЕ работающая сейчас подписка. {@code null} — возврат идёт
     * сегодняшним путём (по человеку).
     * <p>
     * Пустая цель — не этот случай: так выглядят исторические строки, и они относятся к
     * единственной подписке человека. Висячий id (подписку удалили руками) — тоже: там резать
     * нечего, и сегодняшний путь хотя бы вернёт деньги и уменьшит срок работающей подписки,
     * что для человека с одной подпиской и есть правильный исход.
     */
    private Subscription refundTargetIfNotCurrent(Payment payment) {
        Long targetId = payment.getSubscriptionId();
        if (targetId == null) {
            return null;
        }
        Long currentId = subscriptionRepository.findByTelegramIdAndCurrentTrue(payment.getTelegramId())
                .map(Subscription::getId)
                .orElse(null);
        if (targetId.equals(currentId)) {
            return null;
        }
        if (currentId == null) {
            // Ни одна строка не помечена текущей — это битое зеркало, а не «подписка спит».
            // Правда в такой момент живёт в customers, поэтому возврат идёт сегодняшним путём;
            // молча списать баланс строки значило бы вернуть деньги, не тронув доступ.
            reportTargetAnomaly(payment, "возврат: у человека нет текущей подписки (битое зеркало) — "
                    + "срезаю срок по customers");
            return null;
        }
        // Порядок замков в проекте един: платёж → customers → subscriptions. Строку человека
        // берём первой, потому что ниже её правит отвязка карты: обратный порядок дал бы
        // взаимную блокировку с переключением подписок, которое ходит именно так.
        userRepository.findByTelegramIdForUpdate(payment.getTelegramId());
        // Блокировка строки: повторная доставка refund-вебхука не должна списать баланс дважды.
        return subscriptionRepository.findOwnedForUpdate(targetId, payment.getTelegramId()).orElse(null);
    }

    /**
     * Возврат по ЗАМОРОЖЕННОЙ подписке: списываем с её остатка ту долю оплаченного времени,
     * которую фактически вернули ({@link #revokedDays}).
     * <p>
     * <b>Панель не трогаем.</b> У припаркованной подписки аккаунт и так выключен, а работающая
     * подписка к этому возврату отношения не имеет — единственное, что мог бы сделать вызов
     * панели, это оборвать доступ не тому.
     * <p>
     * Остаток клампится в ноль: уйти в минус значило бы, что при следующем включении человек
     * получит отрицательный срок, а на деле «вернули больше, чем там лежало» — это разбор
     * владельца, а не повод портить строку.
     */
    private RefundOutcome refundFromParked(Subscription target, Payment payment,
                                           AppConfig.Plan plan, long refundedKopeks, Customer customer) {
        // Та же пропорция, что и в основной ветке: частичный возврат списывает с остатка
        // только оплаченную долю срока, а не весь план.
        int daysToRevoke = revokedDays(plan, payment, refundedKopeks);
        boolean fullRefund = isFullRefund(payment, refundedKopeks);
        long balanceBefore = target.getBalanceSeconds() == null ? 0L : target.getBalanceSeconds();
        long balanceAfter = Math.max(0L, balanceBefore - SubscriptionTime.daysToSeconds(daysToRevoke));
        target.setBalanceSeconds(balanceAfter);
        subscriptionRepository.save(target);

        // Карта — свойство ЧЕЛОВЕКА, и правила YooKassa требуют отвязать её при возврате
        // независимо от того, какая подписка возвращается (см. разбор в основной ветке).
        unbindAutorenewCard(customer);

        // Порядок тот же, что в основной ветке и по той же причине: платёж выпадает из
        // выборки «последняя оставшаяся оплата со ступенью» до того, как ступень откатывается.
        markPaymentRefunded(payment, refundedKopeks);
        // Ступень откатываем только при ПОЛНОМ возврате — см. FULL_REFUND_PERCENT и разбор
        // в основной ветке: частичный возврат не повод забирать оплаченную ступень целиком.
        if (fullRefund) {
            rollbackDeviceTierAfterRefund(payment, target);
        }

        // В журнале — фактически снятые дни, а не длина плана: иначе запись обещала бы
        // списание, которого не было, и остаток в ней сходился бы только на полном возврате.
        ledger.record(payment.getTelegramId(), target.getId(),
                com.orchestra.subscriptions.model.SubscriptionLedger.Kind.REFUND,
                balanceAfter - balanceBefore, balanceAfter, payment.getId(),
                "возврат по замороженной подписке #" + target.getSeq() + ", " + daysToRevoke + " дн.");

        // Реферальные бонусы — тоже только при полном возврате (та же причина, что выше).
        if (fullRefund) {
            revertReferralBonusesIfAny(payment, LocalDateTime.now());
        }
        try {
            promoCodeService.revertForPayment(payment.getId());
        } catch (Exception e) {
            log.warn("Failed to revert promo usage for payment {}", payment.getId(), e);
        }

        log.info("Возврат платежа id={} по замороженной подписке id={} (#{}) telegramId={}: "
                        + "снято {} дн. из {}, остаток {} → {} с",
                payment.getId(), target.getId(), target.getSeq(), payment.getTelegramId(),
                daysToRevoke, plan.getDays(), balanceBefore, balanceAfter);
        return new RefundOutcome(false, null, target);
    }

    /**
     * Отвязка карты при возврате — compliance YooKassa. Вынесено из основной ветки без
     * изменения логики: обе ветки возврата обязаны вести себя одинаково, иначе после возврата
     * по спящей подписке карта осталась бы привязанной.
     */
    private void unbindAutorenewCard(Customer customer) {
        if (customer.getAutorenewMethodId() == null) {
            return;
        }
        customer.setAutorenewMethodId(null);
        customer.setAutorenewPlanId(null);
        customer.setAutorenewEnabled(false);
        customer.setAutorenewLastFailureAt(null);
        userRepository.save(customer);
        accessService.mirrorSubscription(customer.getTelegramId());
    }

    /** Даёт ли подписка доступ прямо сейчас. */
    private static boolean isLive(Subscription sub, LocalDateTime now) {
        return sub != null
                && sub.getState() == Customer.Status.ACTIVE
                && sub.getExpiresAt() != null
                && sub.getExpiresAt().isAfter(now);
    }

    /** Сегодняшний путь активации: дни поверх остатка текущей подписки, синк панели, зеркало. */
    private Customer activateCurrent(Payment payment, AppConfig.Plan plan, AppConfig.DeviceTier tier) {
        return accessService.activatePaidSubscription(
                payment.getTelegramId(),
                payment.getTelegramUsername(),
                plan.getDays(),
                tier,
                // Списание с карты и ручная покупка приходят сюда одинаково — различает их
                // только этот флаг (см. changeset 024), а в журнале сроков разница важна.
                payment.isAutorenew()
                        ? com.orchestra.subscriptions.model.SubscriptionLedger.Kind.AUTORENEW
                        : com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PURCHASE
        );
    }

    /**
     * Зачисление в ЗАМОРОЖЕННУЮ подписку: дни прибавляются к остатку, дата не появляется,
     * подписка остаётся выключенной.
     * <p>
     * <b>Панель не трогаем вообще.</b> У припаркованной подписки аккаунт в AccessProvider выключен,
     * и «продлить» его значило бы включить второй живой аккаунт — удвоенный лимит устройств
     * и удвоенный лимит соединений, то есть выдача того, за что не платили.
     * <p>
     * <b>Флаги предупреждений сбрасываем.</b> Без сброса человек, оплативший замороженную
     * подписку после её истечения, разморозил бы её уже «предупреждённой» и не получил бы ни
     * одного напоминания о новом сроке. Статус возвращаем в ACTIVE по той же причине: оплата
     * отменяет отключение по истечении.
     */
    private void depositIntoParked(Subscription target, Payment payment,
                                   AppConfig.Plan plan, AppConfig.DeviceTier tier) {
        long paidSeconds = SubscriptionTime.daysToSeconds(plan.getDays());
        long balanceBefore = target.getBalanceSeconds() == null ? 0L : target.getBalanceSeconds();
        target.setBalanceSeconds(balanceBefore + paidSeconds);
        target.setState(Customer.Status.ACTIVE);
        target.setNotified24h(false);
        target.setNotified1h(false);
        target.setWinbackSent(false);
        if (tier != null) {
            // Ступень пишется НА СТРОКУ ПОДПИСКИ, а не в customers: там она описывает
            // работающую сейчас подписку, и перезапись сломала бы её лимит устройств.
            target.setDeviceLimit(tier.getDevices());
            target.setDeviceTierId(tier.getId());
        }
        subscriptionRepository.save(target);

        ledger.record(payment.getTelegramId(), target.getId(),
                payment.isAutorenew()
                        ? com.orchestra.subscriptions.model.SubscriptionLedger.Kind.AUTORENEW
                        : com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PURCHASE,
                paidSeconds, balanceBefore + paidSeconds, payment.getId(),
                "подписка #" + target.getSeq() + " (заморожена), " + plan.getDays() + " дн."
                        + (tier != null ? ", " + tier.getId() : ""));

        // Если у человека прямо сейчас ничего не работает, оплата обязана дать доступ, а не
        // положить дни в невидимый карман. Разбор — после коммита: включение пишет в частичный
        // уникальный индекс, а его нарушение отравило бы платёжную транзакцию.
        subscriptionMintService.settleAfterCommit(payment.getTelegramId(), target.getId());

        log.info("Зачислил {} дн. в замороженную подписку id={} (#{}) telegramId={}: остаток {} с",
                plan.getDays(), target.getId(), target.getSeq(), payment.getTelegramId(),
                balanceBefore + paidSeconds);
    }

    /** Человек целиком — он один на все подписки, и его читают уведомления и автопродление. */
    private Customer loadUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId).orElse(null);
    }

    /**
     * Сообщает владельцу, что цель платежа не сошлась. Неудача алерта не должна ронять
     * активацию: без дней останется человек, а владелец всё равно увидит WARN в логах.
     */
    private void reportTargetAnomaly(Payment payment, String reason) {
        log.warn("{} (payment id={}, telegramId={})", reason, payment.getId(), payment.getTelegramId());
        try {
            notificationService.notifyOwnerPaymentTargetAnomaly(
                    payment.getTelegramId(), payment.getId(), reason);
        } catch (Exception e) {
            log.warn("Не удалось сообщить владельцу о расхождении цели платежа id={}",
                    payment.getId(), e);
        }
    }

    /**
     * Ступень устройств платежа ({@code payments.device_tier_id}). Null — legacy-платёж без
     * выбора устройств ИЛИ ступень удалили из конфига между чекаутом и webhook'ом (тогда
     * активация идёт по legacy-пути: срок начисляем, лимит устройств не трогаем — деньги
     * юзера важнее строгости конфига).
     */
    private AppConfig.DeviceTier resolveDeviceTier(Payment payment) {
        if (payment.getDeviceTierId() == null) {
            return null;
        }
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(payment.getDeviceTierId()).orElse(null);
        if (tier == null) {
            log.warn("Device tier {} on payment {} not found in config — activating without tier",
                    payment.getDeviceTierId(), payment.getId());
        }
        return tier;
    }

}
