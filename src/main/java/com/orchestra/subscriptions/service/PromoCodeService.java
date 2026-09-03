package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.PromoCode;
import com.orchestra.subscriptions.model.PromoUsage;
import com.orchestra.subscriptions.repository.PromoCodeRepository;
import com.orchestra.subscriptions.repository.PromoUsageRepository;
import com.orchestra.subscriptions.util.Money;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Промокоды на скидку.
 * <p>
 * Жизненный цикл:
 * <ol>
 *   <li>Админ создаёт код через {@code /promo create CODE PERCENT 20 [maxUses] [daysValid]}.</li>
 *   <li>Юзер вводит код в Mini App / ЛК / боте. Экран дёргает {@link #preview(String, long, Long)} —
 *       он только считает скидку и ничего не занимает.</li>
 *   <li>Юзер нажимает «Оплатить». Канал заводит PENDING-платёж и сразу
 *       {@link #reserveForCheckout(String, long, Long, Long)} — строка в {@code promo_usages}
 *       появляется ЗДЕСЬ, вместе с инкрементом {@code used_count}. С этого момента слот кода
 *       занят, и второй параллельный чекаут того же человека отклоняет частичный уникальный
 *       индекс {@code uk_promo_usages_user_code} (миграция 030), а не везение на превью.</li>
 *   <li>Счёт не состоялся (провайдер не принял, юзер отменил, платёж протух) — резерв снимается:
 *       {@link #releaseReservation(Long)} явно либо {@link #releaseDeadReservations(Long)} лениво,
 *       на ближайшем чекауте по этому коду.</li>
 *   <li>Вебхук перевёл платёж в SUCCEEDED — {@link #recordUsageOnSucceeded(String, long, Long, Long)}.
 *       Резерв уже есть и уже посчитан, поэтому подтверждать нечего: метод идемпотентен по
 *       построению. Записывать применение он умеет только для счетов, выставленных ДО появления
 *       резервирования.</li>
 *   <li>При refund — {@link #revertForPayment(Long)} ставит reverted_at и декрементит used_count.</li>
 * </ol>
 * <p>
 * <b>Почему резерв, а не проверка на оплате.</b> Раньше правило «один код — один раз на человека»
 * держалось на одной проверке в {@link #checkUsable}, а строка, которую эта проверка читает,
 * появлялась только на SUCCEEDED. Все три входа в оплату считали скидку read-only-превью, которое
 * ничего не занимает: человек, открывший N чекаутов параллельно, получал N счетов со скидкой и
 * платил по ним со скидкой. Гарантия обязана стоять на ВЫСТАВЛЕНИИ СЧЁТА — там, где мы называем
 * цену, а не там, где принимаем деньги.
 * <p>
 * <b>used_count считает и резервы тоже.</b> Это плата за то, что счётчик остаётся единственным и
 * не требует отдельной колонки «подтверждено»: строка в {@code promo_usages} существует ровно
 * тогда, когда слот занят, поэтому повторная обработка вебхука и ретрай реконсилера физически
 * не могут списать слот дважды. Брошенный резерв возвращает слот назад при снятии.
 */
@Slf4j
@Service
public class PromoCodeService {

    /** Причина «этот человек уже тратил код» — она же ключ локализации в UI и боте. */
    private static final String REASON_ALREADY_USED = "already_used";

    /**
     * Причина «у человека уже висит НЕОПЛАЧЕННЫЙ счёт с этим кодом». Отдельная от
     * {@link #REASON_ALREADY_USED} намеренно: это прямое следствие резервирования, и действие
     * человеку нужно другое — не искать другой код, а оплатить выставленный счёт или дождаться
     * его отмены. Сказать ему «Вы уже использовали этот промокод» было бы просто неправдой.
     */
    private static final String REASON_PENDING_CHECKOUT = "pending_checkout";

    /**
     * Сколько живёт резерв, если счёт по нему так и не оплатили. Привязан к сроку жизни самого
     * счёта: {@code PaymentReconcileService} сдаётся ровно через сутки и помечает такой платёж
     * FAILED. За этим порогом резерв уже ничего не защищает — он только держал бы код занятым
     * у человека, который передумал платить, а это регресс хуже исходной дыры.
     */
    private static final long RESERVATION_TTL_HOURS = 24;

    /**
     * Сколько брошенных резервов снимаем за один чекаут. Уборка идёт по ходу дела, а не
     * планировщиком, поэтому она обязана быть ограниченной: чекаут человека не должен ждать,
     * пока разгребут всю историю кода. Остаток уберёт следующий чекаут.
     */
    private static final int DEAD_RESERVATION_SWEEP_LIMIT = 200;

    /**
     * Условие «резерв мёртв»: счёт, под который он взят, уже не будет оплачен.
     * <p>
     * Отдельной колонки-признака здесь намеренно нет. Правда о том, состоялся ли платёж, живёт
     * на строке платежа, и любая её копия в {@code promo_usages} рано или поздно разошлась бы с
     * оригиналом. Отсюда же берётся покрытие путей, которые про промокоды не знают вовсе:
     * реконсилер, помечающий зависший счёт FAILED, гасит и резерв, не делая ничего специального.
     * <p>
     * {@code telegram_charge_id IS NULL} — не украшение: у Stars с выставленным charge id звёзды
     * УЖЕ списаны, и такой PENDING — это не брошенный счёт, а оплаченный, у которого не доехала
     * активация (её ретраит реконсилер). Снять его резерв значило бы отобрать скидку, за которую
     * человек заплатил, — и сделать это тем скорее, чем дольше чинится активация.
     */
    private static final String DEAD_RESERVATION_WHERE =
            " AND u.revertedAt IS NULL"
                    + " AND p.telegramChargeId IS NULL"
                    + " AND (p.status = :canceled OR p.status = :failed"
                    + "      OR (p.status = :pending AND p.createdAt < :staleBefore))";

    private final PromoCodeRepository promoRepo;
    private final PromoUsageRepository usageRepo;
    private final EntityManager entityManager;
    private final AppConfig appConfig;
    /** Lazy — SubscriptionBot тянет PromoCodeService через ScreenHandler, а NotificationService тянет SubscriptionBot;
     *  без @Lazy на параметре конструктора получился бы цикл бинов. */
    private final NotificationService notificationService;
    /**
     * Своя транзакция на запись применения промокода. Нужна не для скорости, а чтобы гонка
     * не роняла чужую работу: нарушение уникального индекса — это
     * {@code DataIntegrityViolationException}, а он в Postgres отравляет транзакцию целиком.
     * В общей с платежом транзакции проглоченное исключение кончилось бы
     * {@code UnexpectedRollbackException} на коммите — платёж навсегда PENDING, вебхук 500,
     * реконсилер по кругу (та же грабля, что описана у completeDeviceAddon).
     * Плата за развязку: если транзакция вызывающего откатится ПОСЛЕ записи, применение
     * останется — повторная обработка увидит его по payment_id и не задвоит.
     */
    private final TransactionTemplate usageTxTemplate;

    public PromoCodeService(PromoCodeRepository promoRepo,
                            PromoUsageRepository usageRepo,
                            EntityManager entityManager,
                            AppConfig appConfig,
                            @Lazy NotificationService notificationService,
                            PlatformTransactionManager transactionManager) {
        this.promoRepo = promoRepo;
        this.usageRepo = usageRepo;
        this.entityManager = entityManager;
        this.appConfig = appConfig;
        this.notificationService = notificationService;
        this.usageTxTemplate = new TransactionTemplate(transactionManager);
        this.usageTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ===========================
    //  Read / preview (read-only)
    // ===========================

    /**
     * Считает скидку для предварительного показа в UI без записи в БД.
     * Ничего не занимает: слот кода занимает только
     * {@link #reserveForCheckout(String, long, Long, Long)} в момент выставления счёта.
     *
     * @return детали скидки или {@link PromoResult#invalid(String)} с понятной причиной для UI
     */
    @Transactional(readOnly = true)
    public PromoResult preview(String rawCode, long amountKopeks, Long telegramId) {
        String code = normalize(rawCode);
        if (code == null) return PromoResult.invalid("empty");

        Optional<PromoCode> opt = promoRepo.findByCode(code);
        if (opt.isEmpty()) return PromoResult.invalid("not_found");
        PromoCode promo = opt.get();

        String reason = checkUsable(promo, telegramId);
        if (reason != null) return PromoResult.invalid(reason);

        long discountKopeks = computeDiscountKopeks(promo, amountKopeks);
        long finalKopeks = Math.max(0, amountKopeks - discountKopeks);
        return PromoResult.ok(promo, discountKopeks, finalKopeks);
    }

    // ===========================
    //  Резерв под счёт
    // ===========================

    /**
     * Резервирует промокод под УЖЕ СОЗДАННЫЙ счёт: проверяет применимость, считает скидку и
     * сразу пишет строку в {@code promo_usages} вместе с инкрементом {@code used_count}.
     * Единственная точка, через которую все три канала (Mini App, ЛК, бот) вправе выставить
     * счёт со скидкой.
     * <p>
     * <b>Инвариант:</b> счёт со скидкой существует только там, где слот кода уже занят. Второй
     * параллельный чекаут того же человека упирается в частичный уникальный индекс
     * {@code uk_promo_usages_user_code} и получает не 500, а обычный отказ
     * {@code already_used} — вызывающий покажет его как причину и не пойдёт к провайдеру.
     * <p>
     * При успехе вызывающий обязан сохранить {@link PromoResult#code()} в
     * {@code Payment.promo_code_used} и пересчитать сумму счёта на
     * {@link PromoResult#finalAmountKopeks()}. Если счёт создать не удалось — снять резерв
     * через {@link #releaseReservation(Long)}, иначе код будет занят у человека до истечения
     * {@link #RESERVATION_TTL_HOURS}.
     *
     * @param paymentId строка платежа, под которую берётся резерв; она уже должна быть в БД
     */
    public PromoResult reserveForCheckout(String rawCode, long amountKopeks,
                                          Long telegramId, Long paymentId) {
        String code = normalize(rawCode);
        if (code == null) return PromoResult.invalid("empty");
        if (telegramId == null || paymentId == null) {
            log.warn("Резерв промокода {} невозможен: telegramId={} paymentId={}",
                    code, telegramId, paymentId);
            return PromoResult.invalid("invalid");
        }

        try {
            return usageTxTemplate.execute(status -> {
                PromoCode promo = promoRepo.findByCode(code).orElse(null);
                if (promo == null) return PromoResult.invalid("not_found");

                // Брошенные резервы возвращаем в оборот ДО проверок: иначе счёт, который человек
                // открыл вчера и не оплатил, навсегда занимал бы код ему самому, а чужие такие же
                // счета навсегда съедали бы слоты max_uses.
                if (releaseDeadReservations(promo.getId()) > 0) {
                    // used_count правили запросом мимо контекста — перечитываем строку кода,
                    // иначе проверка лимита ниже смотрела бы на устаревший счётчик.
                    entityManager.refresh(promo);
                }

                String reason = checkUsable(promo, telegramId);
                if (reason != null) return PromoResult.invalid(reason);

                long discountKopeks = computeDiscountKopeks(promo, amountKopeks);
                // saveAndFlush, а не save: нарушение уникального индекса должно всплыть здесь,
                // внутри этой транзакции, а не на её коммите за пределами try/catch.
                usageRepo.saveAndFlush(PromoUsage.builder()
                        .promoCodeId(promo.getId())
                        .telegramId(telegramId)
                        .paymentId(paymentId)
                        .discountKopeks(discountKopeks)
                        .build());

                if (incrementUsedCountWithinCap(promo.getId()) == 0) {
                    // Лимит кода выбрали, пока мы резервировали. Здесь отказать МОЖНО: деньги
                    // ещё не тронуты, человеку названа цена без скидки, а не изменена задним
                    // числом. После оплаты этот же случай разрешается наоборот — в пользу юзера.
                    status.setRollbackOnly();
                    log.info("Промокод {}: лимит выбран во время резерва под платёж id={}",
                            code, paymentId);
                    return PromoResult.invalid("max_uses_reached");
                }

                log.info("Зарезервирован промокод {} за telegramId={} под платёж id={} (скидка {} коп.)",
                        code, telegramId, paymentId, discountKopeks);
                return PromoResult.ok(promo, discountKopeks,
                        Math.max(0, amountKopeks - discountKopeks));
            });
        } catch (DataIntegrityViolationException e) {
            // uk_promo_usages_user_code — параллельный чекаут того же человека успел первым.
            // Отдельная транзакция откатилась целиком; вызывающему возвращаем обычную причину
            // отказа, а не 500: он покажет её человеку и не пойдёт к провайдеру.
            log.info("Промокод {} уже занят у telegramId={} (параллельный чекаут), платёж id={}",
                    code, telegramId, paymentId, e);
            return PromoResult.invalid(REASON_ALREADY_USED);
        }
    }

    /**
     * Снимает резерв со счёта, который не состоялся: провайдер не принял счёт, человек отменил
     * оплату, платёж протух. Слот кода возвращается человеку сразу, не дожидаясь, пока резерв
     * протухнет сам (см. {@link #RESERVATION_TTL_HOURS}).
     * <p>
     * Идемпотентно через {@code reverted_at}. Резерв ОПЛАЧЕННОГО счёта не снимается ни при
     * каких условиях: за него человек уже получил скидку, и вернуть слот значило бы выдать
     * вторую по тому же коду.
     */
    @Transactional
    public void releaseReservation(Long paymentId) {
        if (paymentId == null) return;
        PromoUsage usage = usageRepo.findByPaymentId(paymentId).orElse(null);
        if (usage == null || usage.getRevertedAt() != null) return;

        Payment.Status status = paymentStatus(paymentId);
        if (status == Payment.Status.SUCCEEDED || status == Payment.Status.REFUNDED) {
            log.warn("Резерв промокода по платежу id={} НЕ снят: платёж в статусе {} — "
                    + "скидка по нему уже получена", paymentId, status);
            return;
        }

        usage.setRevertedAt(LocalDateTime.now());
        usageRepo.save(usage);
        promoRepo.decrementUsedCount(usage.getPromoCodeId());
        log.info("Снят резерв промокода promoCodeId={} по несостоявшемуся платежу id={} (статус {})",
                usage.getPromoCodeId(), paymentId, status);
    }

    // ===========================
    //  Оплата и возврат
    // ===========================

    /**
     * Отмечает применение промокода после успешной оплаты.
     * <p>
     * Обычный путь — <b>ничего не делать</b>: резерв под этот платёж взят ещё на чекауте, там же
     * списан слот кода. Именно поэтому метод идемпотентен по построению — ни повторная доставка
     * вебхука, ни ретрай реконсилера не могут списать слот второй раз.
     * <p>
     * Записать применение по факту оплаты метод умеет только для счетов, выставленных ДО
     * появления резервирования (висели с прошлого деплоя). Там же остаётся прежняя политика:
     * <ul>
     *   <li>Платёж НЕ отменяем ни при каком исходе — деньги приняты по счёту, который был
     *       выставлен со скидкой, и пересчитать его задним числом нельзя. Если гонку проиграли,
     *       второе применение просто не засчитывается, а владелец получает сообщение и сам
     *       решает, нужен ли возврат разницы.</li>
     *   <li><b>Soft cap для max_uses:</b> отказывать в записи из-за выбранного лимита нельзя —
     *       юзер оплатил, «отозвать» показанную скидку невозможно. Двигается ли счётчик, решает
     *       сама БД (см. {@link #incrementUsedCountWithinCap(Long)}): при выбранном лимите он
     *       остаётся на потолке, а строка применения всё равно появляется.</li>
     * </ul>
     */
    @Transactional
    public void recordUsageOnSucceeded(String rawCode, long amountKopeks,
                                       Long telegramId, Long paymentId) {
        if (paymentId == null || telegramId == null) return;
        String code = normalize(rawCode);
        if (code == null) return;

        PromoUsage reserved = usageRepo.findByPaymentId(paymentId).orElse(null);
        if (reserved != null) {
            if (reserved.getRevertedAt() == null) {
                // Живой резерв — он и есть запись применения: слот кода списан ещё на чекауте.
                log.debug("Промокод {}: резерв под платёж id={} уже учтён, подтверждать нечего",
                        code, paymentId);
                return;
            }
            // Резерв сняли (счёт признали несостоявшимся), а деньги всё-таки пришли. Счёт
            // выставлен со скидкой — платёж оставляем как есть и зовём владельца.
            reportLostReservation(code, telegramId, paymentId, reserved.getDiscountKopeks());
            return;
        }

        Optional<PromoCode> opt = promoRepo.findByCode(code);
        if (opt.isEmpty()) {
            log.warn("recordUsageOnSucceeded: promo {} disappeared (deleted?), paymentId={}", code, paymentId);
            return;
        }
        PromoCode promo = opt.get();

        long discountKopeks = computeDiscountKopeks(promo, amountKopeks);
        UsageOutcome outcome = writeUsage(promo, telegramId, paymentId, discountKopeks);

        switch (outcome) {
            case RECORDED -> log.info("Recorded promo usage code={} telegramId={} paymentId={} discount={}k",
                    code, telegramId, paymentId, discountKopeks);
            case RECORDED_OVER_CAP -> log.warn("Promo {} used beyond max_uses cap (max_uses={}): "
                            + "usage for paymentId={} recorded, counter left at the cap. "
                            + "Race in checkout validation — acceptable trade-off.",
                    code, promo.getMaxUses(), paymentId);
            case ALREADY_USED, RACE_LOST ->
                    reportRepeatedUse(code, telegramId, paymentId, discountKopeks, outcome);
            case DUPLICATE -> log.debug("recordUsageOnSucceeded: usage appeared concurrently "
                    + "for paymentId={}, nothing to do", paymentId);
        }
    }

    /** Чем закончилась запись применения промокода после успешной оплаты. */
    private enum UsageOutcome {
        /** Применение записано, слот кода списан. */
        RECORDED,
        /** Применение записано, но лимит кода уже выбран — счётчик остался на потолке. */
        RECORDED_OVER_CAP,
        /** Этот человек уже тратил код на другой платёж — второй раз не засчитываем. */
        ALREADY_USED,
        /** Параллельная транзакция вставила такое же применение первой (уникальный индекс). */
        RACE_LOST,
        /** Дубль-вебхук: применение по этому платежу появилось, пока мы сюда шли. */
        DUPLICATE
    }

    /**
     * Пишет применение в СВОЕЙ транзакции (см. поле {@link #usageTxTemplate}) и возвращает,
     * чем всё кончилось. Транзакцию вызывающего этот метод не трогает ни при каком исходе —
     * в том числе при нарушении уникального индекса.
     */
    private UsageOutcome writeUsage(PromoCode outerPromo, Long telegramId, Long paymentId,
                                    long discountKopeks) {
        Long promoCodeId = outerPromo.getId();
        try {
            return usageTxTemplate.execute(status -> {
                // Своя транзакция видит закоммиченное соседями — перепроверяем оба инварианта
                // на самых свежих данных, а не на том, что прочитал чекаут час назад.
                if (usageRepo.findByPaymentId(paymentId).isPresent()) {
                    return UsageOutcome.DUPLICATE;
                }
                // Брошенный резерв не должен мешать записать состоявшуюся оплату: он занимает
                // тот же уникальный индекс, в который мы сейчас вставляем. Снимаем ДО чтения
                // строки кода: снятие правит used_count запросом, и прочитанная раньше строка
                // показывала бы устаревший счётчик.
                releaseDeadReservations(promoCodeId);
                // Код перечитываем в СВОЕЙ транзакции — сущность, загруженная транзакцией
                // вызывающего, здесь чужая. Пропал из БД (удалили руками) — работаем по
                // снимку вызывающего: деньги приняты, отказ в записи только исказил бы учёт.
                PromoCode promo = promoRepo.findById(promoCodeId).orElse(outerPromo);
                String reason = checkUsable(promo, telegramId);
                if (reason != null) {
                    // «Уже применял» — единственная причина, по которой запись действительно
                    // невозможна: её же запрещает уникальный индекс. Остальные (код выключили,
                    // истёк, выбран лимит) остаются мягкими — деньги приняты, и отказ в записи
                    // лишь исказил бы учёт. Проверку по живым применениям повторяем явно:
                    // checkUsable возвращает ПЕРВУЮ причину, и у выключенного кода она
                    // перекрыла бы эту.
                    if (REASON_ALREADY_USED.equals(reason)
                            || hasLiveUsage(promo.getId(), telegramId)) {
                        return UsageOutcome.ALREADY_USED;
                    }
                    log.warn("recordUsageOnSucceeded: промокод {} к моменту оплаты стал непригоден "
                            + "({}), но платёж id={} уже оплачен — применение записываем",
                            promo.getCode(), reason, paymentId);
                }

                // saveAndFlush, а не save: нарушение уникального индекса должно всплыть здесь,
                // внутри этой транзакции, а не на её коммите за пределами try/catch.
                usageRepo.saveAndFlush(PromoUsage.builder()
                        .promoCodeId(promo.getId())
                        .telegramId(telegramId)
                        .paymentId(paymentId)
                        .discountKopeks(discountKopeks)
                        .build());

                return incrementUsedCountWithinCap(promo.getId()) == 0
                        ? UsageOutcome.RECORDED_OVER_CAP
                        : UsageOutcome.RECORDED;
            });
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(payment_id) — дубль-вебхук, либо uk_promo_usages_user_code — параллельный
            // чекаут того же человека. В обоих случаях строку вставил кто-то другой; откатилась
            // только наша отдельная транзакция, платёж вызывающего цел.
            log.debug("recordUsageOnSucceeded: race lost on unique index, paymentId={}", paymentId, e);
            return UsageOutcome.RACE_LOST;
        }
    }

    /**
     * Инкремент used_count с потолком в одном операторе: условие живёт в WHERE, поэтому два
     * параллельных чекаута не могут оба прочитать «лимит ещё не выбран» и оба списать слот.
     *
     * @return 1 — слот списан; 0 — лимит уже выбран (или кода нет), счётчик не тронут
     */
    private int incrementUsedCountWithinCap(Long promoCodeId) {
        return entityManager.createQuery(
                        "UPDATE PromoCode p SET p.usedCount = p.usedCount + 1 "
                                + "WHERE p.id = :id AND (p.maxUses IS NULL OR p.usedCount < p.maxUses)")
                .setParameter("id", promoCodeId)
                .executeUpdate();
    }

    /**
     * Один и тот же человек применил код второй раз (параллельные чекауты). Платёж не трогаем:
     * счёт выставлен со скидкой и уже оплачен, пересчитать его нельзя. Сообщаем владельцу —
     * решение о возврате разницы за ним.
     */
    private void reportRepeatedUse(String code, Long telegramId, Long paymentId,
                                   long discountKopeks, UsageOutcome outcome) {
        log.warn("Promo {} applied twice by telegramId={} (paymentId={}, outcome={}): "
                + "usage not recorded, payment left untouched", code, telegramId, paymentId, outcome);
        notifyOwner("⚠️ Промокод применён повторно: " + code
                + "\nЮзер: id=" + telegramId
                + "\nПлатёж: id=" + paymentId
                + "\nСкидка в счёте: " + Money.rub(discountKopeks) + " ₽"
                + "\n\nСчёт был выставлен со скидкой и уже оплачен, поэтому платёж "
                + "оставлен как есть, а второе применение не засчитано. "
                + "Решите, нужен ли возврат разницы.", code, paymentId);
    }

    /**
     * Счёт со скидкой признали несостоявшимся (отменён или протух), слот кода вернули в оборот,
     * а деньги по нему всё-таки пришли. Платёж не трогаем по тому же правилу: переоценивать
     * оплаченный счёт нельзя.
     */
    private void reportLostReservation(String code, Long telegramId, Long paymentId,
                                       long discountKopeks) {
        log.warn("Promo {} reservation was released for paymentId={} (telegramId={}) but payment "
                + "succeeded later: usage stays reverted, payment left untouched",
                code, paymentId, telegramId);
        notifyOwner("⚠️ Промокод: резерв снят, а платёж прошёл. Код: " + code
                + "\nЮзер: id=" + telegramId
                + "\nПлатёж: id=" + paymentId
                + "\nСкидка в счёте: " + Money.rub(discountKopeks) + " ₽"
                + "\n\nСчёт со скидкой был признан несостоявшимся, поэтому слот кода вернулся "
                + "в оборот. Оплата пришла позже: деньги приняты и подписка выдана, применение "
                + "кода повторно не засчитано. Решите, нужен ли возврат разницы.", code, paymentId);
    }

    /** Сообщение владельцу о денежной аномалии. Неудача отправки не должна ломать оплату. */
    private void notifyOwner(String text, String code, Long paymentId) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        try {
            notificationService.sendPlainText(ownerId, text);
        } catch (Exception e) {
            log.warn("Failed to notify owner about promo anomaly, code={} paymentId={}",
                    code, paymentId, e);
        }
    }

    /**
     * Откатить применение промо при refund платежа. Идемпотентно через reverted_at:
     * повторный refund-webhook (дубликат delivery) не декрементит used_count второй раз.
     * Молча no-op если промо к платежу не применялся.
     */
    @Transactional
    public void revertForPayment(Long paymentId) {
        Optional<PromoUsage> opt = usageRepo.findByPaymentId(paymentId);
        if (opt.isEmpty()) return;
        PromoUsage usage = opt.get();
        if (usage.getRevertedAt() != null) {
            log.debug("Promo usage for paymentId={} already reverted, skipping", paymentId);
            return;
        }
        usage.setRevertedAt(LocalDateTime.now());
        usageRepo.save(usage);
        promoRepo.decrementUsedCount(usage.getPromoCodeId());
        log.info("Reverted promo usage paymentId={} promoCodeId={}", paymentId, usage.getPromoCodeId());
    }

    // ===========================
    //  Admin CRUD
    // ===========================

    /**
     * Создаёт новый промокод. Бросает {@link IllegalArgumentException} если код уже существует
     * или параметры невалидны (вызывающий конвертирует в человекочитаемый ответ юзеру).
     *
     * @param daysValid сколько дней код будет активен. NULL = бессрочный.
     * @param maxUses   максимум применений. NULL = безлимит.
     */
    @Transactional
    public PromoCode createPromo(String rawCode, PromoCode.DiscountType type, long discountValue,
                                 Integer maxUses, Integer daysValid, Long createdBy) {
        String code = normalize(rawCode);
        if (code == null) throw new IllegalArgumentException("Код пустой");
        if (code.length() < 3 || code.length() > 64) {
            throw new IllegalArgumentException("Длина кода: 3..64 символа");
        }
        if (type == PromoCode.DiscountType.PERCENT && (discountValue < 1 || discountValue > 99)) {
            throw new IllegalArgumentException("Процент скидки: 1..99");
        }
        if (type == PromoCode.DiscountType.FIXED && discountValue < 1) {
            throw new IllegalArgumentException("Размер скидки должен быть положительным (в копейках)");
        }
        if (maxUses != null && maxUses < 1) {
            throw new IllegalArgumentException("max_uses должен быть >= 1 (или не указан = безлимит)");
        }
        if (daysValid != null && daysValid < 1) {
            throw new IllegalArgumentException("days_valid должен быть >= 1 (или не указан = бессрочно)");
        }
        if (promoRepo.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Код уже существует: " + code);
        }
        LocalDateTime expiresAt = daysValid == null ? null : LocalDateTime.now().plusDays(daysValid);
        PromoCode promo = PromoCode.builder()
                .code(code)
                .discountType(type)
                .discountValue(discountValue)
                .maxUses(maxUses)
                .usedCount(0)
                .expiresAt(expiresAt)
                .active(true)
                .createdByTelegramId(createdBy)
                .build();
        return promoRepo.save(promo);
    }

    @Transactional(readOnly = true)
    public List<PromoCode> listAll() {
        return promoRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<PromoCode> findByCode(String rawCode) {
        String code = normalize(rawCode);
        if (code == null) return Optional.empty();
        return promoRepo.findByCode(code);
    }

    @Transactional(readOnly = true)
    public List<PromoUsage> recentUsages(Long promoCodeId) {
        return usageRepo.findTop10ByPromoCodeIdOrderByUsedAtDesc(promoCodeId);
    }

    /** Soft-disable: ставит active=false. Невозвратно для не-админа, /promo create под тем же
     * именем не сработает (UNIQUE). Возвращает true если что-то поменяли. */
    @Transactional
    public boolean disable(String rawCode) {
        String code = normalize(rawCode);
        if (code == null) return false;
        Optional<PromoCode> opt = promoRepo.findByCode(code);
        if (opt.isEmpty()) return false;
        PromoCode promo = opt.get();
        if (!promo.isActive()) return false;
        promo.setActive(false);
        promoRepo.save(promo);
        return true;
    }

    // ===========================
    //  Internal helpers
    // ===========================

    /**
     * Возвращает null если код можно применить, иначе строковую причину для UI.
     * Локализуется в WebAppController/боте.
     */
    private String checkUsable(PromoCode promo, Long telegramId) {
        if (!promo.isActive()) return "disabled";
        if (promo.getExpiresAt() != null && promo.getExpiresAt().isBefore(LocalDateTime.now())) {
            return "expired";
        }
        if (promo.getMaxUses() != null && promo.getUsedCount() >= promo.getMaxUses()) {
            return "max_uses_reached";
        }
        if (telegramId != null && hasLiveUsage(promo.getId(), telegramId)) {
            // Под «код занят» скрываются два разных положения дел, и человеку из них следует
            // разное: код потрачен на оплаченный счёт — либо просто висит неоплаченный счёт
            // с этим кодом, который он может оплатить или бросить.
            return hasPendingCheckout(promo.getId(), telegramId)
                    ? REASON_PENDING_CHECKOUT
                    : REASON_ALREADY_USED;
        }
        return null;
    }

    /**
     * Держит ли код НЕОПЛАЧЕННЫЙ счёт этого человека — тот самый резерв, который снимется сам,
     * как только счёт отменят или он протухнет.
     */
    private boolean hasPendingCheckout(Long promoCodeId, Long telegramId) {
        Long count = entityManager.createQuery(
                        "SELECT count(u) FROM PromoUsage u, Payment p"
                                + " WHERE p.id = u.paymentId AND u.promoCodeId = :promoId"
                                + " AND u.telegramId = :tgId AND u.revertedAt IS NULL"
                                + " AND p.status = :pending AND p.createdAt > :staleBefore", Long.class)
                .setParameter("promoId", promoCodeId)
                .setParameter("tgId", telegramId)
                .setParameter("pending", Payment.Status.PENDING)
                .setParameter("staleBefore", reservationHorizon())
                .getSingleResult();
        return count != null && count > 0;
    }

    /**
     * Занят ли код у этого человека ПРЯМО СЕЙЧАС: есть оплаченное применение либо резерв под
     * счётом, который ещё могут оплатить.
     * <p>
     * Проверять просто «есть неоткаченная строка» с появлением резервирования нельзя: строку
     * теперь заводит чекаут, и человек, открывший счёт и передумавший, иначе остался бы без
     * своего же кода навсегда. Живое = всё неоткаченное МИНУС мёртвое
     * ({@link #DEAD_RESERVATION_WHERE}); считаем именно так, чтобы «живое» и «мёртвое» не
     * разошлись на краях — например, на строке платежа без даты создания.
     */
    private boolean hasLiveUsage(Long promoCodeId, Long telegramId) {
        if (telegramId == null) return false;
        long notReverted = countNonRevertedUsages(promoCodeId, telegramId);
        if (notReverted == 0) return false;
        return notReverted > countDeadReservations(promoCodeId, telegramId);
    }

    private long countNonRevertedUsages(Long promoCodeId, Long telegramId) {
        Long count = entityManager.createQuery(
                        "SELECT count(u) FROM PromoUsage u"
                                + " WHERE u.promoCodeId = :promoId AND u.telegramId = :tgId"
                                + " AND u.revertedAt IS NULL", Long.class)
                .setParameter("promoId", promoCodeId)
                .setParameter("tgId", telegramId)
                .getSingleResult();
        return count == null ? 0L : count;
    }

    private long countDeadReservations(Long promoCodeId, Long telegramId) {
        Long count = entityManager.createQuery(
                        "SELECT count(u) FROM PromoUsage u, Payment p"
                                + " WHERE p.id = u.paymentId AND u.promoCodeId = :promoId"
                                + " AND u.telegramId = :tgId" + DEAD_RESERVATION_WHERE, Long.class)
                .setParameter("promoId", promoCodeId)
                .setParameter("tgId", telegramId)
                .setParameter("canceled", Payment.Status.CANCELED)
                .setParameter("failed", Payment.Status.FAILED)
                .setParameter("pending", Payment.Status.PENDING)
                .setParameter("staleBefore", reservationHorizon())
                .getSingleResult();
        return count == null ? 0L : count;
    }

    /**
     * Снимает резервы, взятые под счета, которые уже не будут оплачены, — по всему коду, а не
     * только по текущему человеку: иначе брошенный чужой счёт навсегда съедал бы слот
     * {@code max_uses}. Возвращает слоты в {@code used_count}.
     * <p>
     * Уборка идёт по ходу чекаута, без отдельного планировщика: момент, когда занятость кода
     * действительно важна, — это и есть чекаут, а фоновая задача решала бы ту же задачу
     * позже и с собственным риском (лишний тик в тестах, ещё один выключатель в конфиге).
     *
     * @return сколько резервов снято
     */
    private int releaseDeadReservations(Long promoCodeId) {
        List<PromoUsage> dead = entityManager.createQuery(
                        "SELECT u FROM PromoUsage u, Payment p"
                                + " WHERE p.id = u.paymentId AND u.promoCodeId = :promoId"
                                + DEAD_RESERVATION_WHERE, PromoUsage.class)
                .setParameter("promoId", promoCodeId)
                .setParameter("canceled", Payment.Status.CANCELED)
                .setParameter("failed", Payment.Status.FAILED)
                .setParameter("pending", Payment.Status.PENDING)
                .setParameter("staleBefore", reservationHorizon())
                .setMaxResults(DEAD_RESERVATION_SWEEP_LIMIT)
                .getResultList();
        if (dead.isEmpty()) return 0;

        LocalDateTime now = LocalDateTime.now();
        for (PromoUsage usage : dead) {
            usage.setRevertedAt(now);
            // saveAndFlush, а не save: снятый резерв обязан покинуть частичный уникальный индекс
            // ДО того, как мы вставим новый. В одном flush'е Hibernate выполняет INSERT'ы раньше
            // UPDATE'ов, поэтому отложенное снятие ловило бы нарушение индекса на своей же уборке.
            usageRepo.saveAndFlush(usage);
            promoRepo.decrementUsedCount(usage.getPromoCodeId());
            log.info("Промокод id={}: снят брошенный резерв (paymentId={}, telegramId={})",
                    promoCodeId, usage.getPaymentId(), usage.getTelegramId());
        }
        return dead.size();
    }

    /** Момент, старше которого неоплаченный счёт больше не держит за собой промокод. */
    private static LocalDateTime reservationHorizon() {
        return LocalDateTime.now().minusHours(RESERVATION_TTL_HOURS);
    }

    /** Статус платежа или null, если строки нет (её никто не удаляет, но запрос обязан быть безопасным). */
    private Payment.Status paymentStatus(Long paymentId) {
        return entityManager.createQuery(
                        "SELECT p.status FROM Payment p WHERE p.id = :id", Payment.Status.class)
                .setParameter("id", paymentId)
                .getResultList().stream()
                .findFirst()
                .orElse(null);
    }

    private long computeDiscountKopeks(PromoCode promo, long amountKopeks) {
        if (amountKopeks <= 0) return 0;
        long raw = switch (promo.getDiscountType()) {
            case PERCENT -> amountKopeks * promo.getDiscountValue() / 100;
            case FIXED   -> promo.getDiscountValue();
        };
        // Не уводим цену в минус: скидка не больше суммы.
        return Math.min(raw, amountKopeks);
    }

    private static String normalize(String code) {
        if (code == null) return null;
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Результат проверки/применения промо. {@code valid=true} → код применился, скидка
     * посчитана. {@code valid=false} → причина в {@link #reason()} (стабильный enum-like
     * строковый ключ, локализация в UI/боте).
     */
    public record PromoResult(
            boolean valid,
            String reason,
            Long promoCodeId,
            String code,
            PromoCode.DiscountType discountType,
            long discountValue,
            long discountKopeks,
            long finalAmountKopeks
    ) {
        public static PromoResult invalid(String reason) {
            return new PromoResult(false, reason, null, null, null, 0, 0, 0);
        }
        public static PromoResult ok(PromoCode p, long discountKopeks, long finalKopeks) {
            return new PromoResult(true, "ok", p.getId(), p.getCode(),
                    p.getDiscountType(), p.getDiscountValue(),
                    discountKopeks, finalKopeks);
        }
    }
}
