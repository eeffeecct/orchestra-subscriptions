package com.orchestra.subscriptions.service.payment;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.service.MultiSubscriptionPolicy;
import com.orchestra.subscriptions.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Превращает пожелание канала ({@link TargetSpec}) в решение, куда лягут деньги
 * ({@link PaymentTarget}). Вызывается ОДИН раз — при создании счёта, до обращения к провайдеру.
 * <p>
 * <b>Главное свойство: деньги всегда ложатся туда, что человек видит на экране.</b> Канал,
 * который не умеет спрашивать про цель (старая кнопка из истории чата, закешированный фронт,
 * web-ЛК, автопродление), получает текущую подписку — ровно ту, которую этот канал ему и
 * показывает. Класса ошибок «деньги ушли в подписку, которой юзер не видел» здесь нет
 * структурно, а не за счёт аккуратности вызывающих.
 * <p>
 * <b>14.08.2026 правило пришлось достроить.</b> Раньше ступень сравнивалась ТОЛЬКО с текущей
 * подпиской, а всё остальное падало в «продлить текущую» — и это возвращало исходный баг фичи:
 * покупка ступени, которая уже есть у замороженной подписки, не могла завести отдельную
 * («дубликат»), уходила в текущую и понижала ТАМ тариф на весь оплаченный срок с отключением
 * лишних устройств. Теперь совпадение ступени ищется среди ВСЕХ подписок человека, а понижение
 * работающей не делается ни при каком раскладе (см. {@link #unnamed}).
 * <p>
 * Прежнее возражение против такого подтягивания — «деньги уходят в спящую подписку, которой
 * человек не видел» — снято тем, что цель теперь названа на экране до оплаты во ВСЕХ каналах
 * с выбором ступени (Mini App — строкой последствий, бот — предупреждением перед оплатой).
 * Каналы без выбора ступени (web-ЛК, автопродление, докупка слота) сюда не приходят вовсе:
 * у них свой вход {@link #resolveCurrent}, который всегда целится в работающую подписку.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTargetResolver {

    private final SubscriptionRepository subscriptionRepository;
    private final MultiSubscriptionPolicy policy;
    private final NotificationService notificationService;

    /**
     * @param purchasedTierId ступень устройств покупки ({@code app.device-tiers});
     *                        {@code null} — legacy-покупка без выбора устройств
     */
    @Transactional(readOnly = true)
    public PaymentTarget resolve(long telegramId, String purchasedTierId, TargetSpec spec) {
        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId);
        if (subs.isEmpty()) {
            // Первая покупка: продлевать нечего, активация пойдёт сегодняшним путём
            // «создать аккаунт в панели». Это единственный легальный случай пустой цели.
            return PaymentTarget.first();
        }

        Subscription current = subs.stream().filter(Subscription::isCurrent).findFirst().orElse(null);
        // current == null означает битое зеркало (ни одна строка не помечена текущей). Отказывать
        // человеку в оплате из-за нашей же бухгалтерии нельзя — берём первую по номеру, она
        // детерминирована и видна человеку в списке.
        Subscription fallback = current != null ? current : subs.get(0);

        return switch (spec.kind()) {
            case SUB -> named(telegramId, spec, fallback);
            case NEW -> separate(telegramId, purchasedTierId, subs);
            case CURRENT -> unnamed(telegramId, purchasedTierId, current, fallback, subs);
        };
    }

    /**
     * Цель — ТОЛЬКО работающая сейчас подписка, никогда новая.
     * <p>
     * Для каналов, у которых выбора нет по построению: автосписание по карте (продлевает то,
     * что работает), докупка слота (цена считается pro-rata из остатка текущей), покупка из
     * web-ЛК (там нет выбора ступени вовсе). Отдельный метод, а не значение {@link TargetSpec}:
     * «этот канал не умеет заводить новую подписку» должно быть свойством кода. Иначе
     * достаточно одной невнимательной правки, чтобы автосписание однажды создало человеку
     * вторую подписку вместо продления первой — и он остался бы без доступа, заплатив.
     */
    @Transactional(readOnly = true)
    public PaymentTarget resolveCurrent(long telegramId) {
        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId);
        if (subs.isEmpty()) {
            return PaymentTarget.first();
        }
        return PaymentTarget.extend(subs.stream()
                .filter(Subscription::isCurrent)
                .findFirst()
                .orElseGet(() -> subs.get(0)));
    }

    /**
     * Явно названная подписка. Принадлежность перепроверяется ВСЕГДА и здесь, а не на стороне
     * канала: id приезжает из кнопки/тела запроса, то есть подделывается, и без проверки
     * получился бы способ оплатить чужую подписку.
     * <p>
     * Чужой/удалённый id — не отказ в оплате: человек уже дошёл до кассы, и деньги надо принять
     * туда, где он их увидит. Владельцу уходит алерт — такое значит либо баг канала, либо
     * попытку перебора.
     */
    private PaymentTarget named(long telegramId, TargetSpec spec, Subscription fallback) {
        if (spec.subscriptionId() == null) {
            return anomalyFallback(telegramId, "spec=SUB без id подписки", fallback);
        }
        return subscriptionRepository.findByIdAndTelegramId(spec.subscriptionId(), telegramId)
                .map(PaymentTarget::extend)
                .orElseGet(() -> anomalyFallback(telegramId,
                        "подписка id=" + spec.subscriptionId() + " не найдена или принадлежит другому",
                        fallback));
    }

    private PaymentTarget anomalyFallback(long telegramId, String reason, Subscription fallback) {
        log.warn("Цель платежа не сошлась (telegramId={}): {} — зачислю в подписку id={}",
                telegramId, reason, fallback.getId());
        notificationService.notifyOwnerPaymentTargetAnomaly(telegramId, null, reason);
        return PaymentTarget.extend(fallback);
    }

    /**
     * Явная просьба «завести отдельную подписку». Единственная ветка, которая может отказать в
     * создании счёта — до обращения к провайдеру, поэтому деньги при отказе не движутся.
     */
    private PaymentTarget separate(long telegramId, String purchasedTierId, List<Subscription> subs) {
        if (!policy.canOpenNew(telegramId)) {
            throw new CheckoutRejectedException(CheckoutRejectedException.MULTI_DISABLED);
        }
        if (subs.size() >= policy.maxPerUser()) {
            throw new CheckoutRejectedException(CheckoutRejectedException.LIMIT);
        }
        if (sameTierExists(subs, purchasedTierId)) {
            // Две неотличимые подписки бесполезны — работает всё равно одна, зато появляется
            // вопрос «какая из них рабочая». Такая покупка обязана продлевать, а не плодить.
            throw new CheckoutRejectedException(CheckoutRejectedException.SAME_TIER_EXISTS);
        }
        return PaymentTarget.separate();
    }

    /**
     * Цель не названа: legacy-кнопка из истории чата, закешированный фронт, web-ЛК, автопродление.
     * <p>
     * Правило целиком, по приоритету:
     * <ol>
     *   <li>ступень совпала с ТЕКУЩЕЙ — продлеваем её;</li>
     *   <li>ступень совпала с другой (замороженной) подпиской — пополняем ЕЁ;</li>
     *   <li>ступень новая и лимит позволяет — отдельная подписка;</li>
     *   <li>иначе — замороженная, если есть, и только в последнюю очередь текущая.</li>
     * </ol>
     *
     * <b>Пункты 2 и 4 закрывают дыру, найденную владельцем 14.08.2026.</b> Раньше обе ветки
     * падали в «продлить текущую», и активация переписывала ступень работающей подписки по
     * оплате: покупка дешёвой ступени при уже существующей подписке той же ступени понижала
     * тариф работающей на весь оплаченный срок и отключала лишние устройства. Это ровно тот
     * баг, ради которого затевалась вся мультиподписка, — он возвращался через дверь «отдельную
     * заводить нельзя, дубликат». Продуктовое решение неизменно: <b>понижение ступени никогда
     * не применяется к уже оплаченному периоду</b>.
     * <p>
     * Пункт 4 предпочитает замороженную подписку осознанно: она не работает, и смена её ступени
     * никому не обрывает доступ, тогда как та же операция над текущей отключает устройства прямо
     * сейчас. Оба канала называют эту цель на экране до оплаты.
     * <p>
     * Пункты 2 и 4 работают и при ВЫКЛЮЧЕННОМ флаге: {@code canOpenNew} гейтит создание новых
     * подписок, а не защиту оплаченного срока. У человека с одной подпиской ни один из них не
     * достижим (выбирать не из чего), поэтому для подавляющего большинства это не изменение.
     */
    private PaymentTarget unnamed(long telegramId, String purchasedTierId,
                                  Subscription current, Subscription fallback,
                                  List<Subscription> subs) {
        if (current != null && purchasedTierId != null
                && purchasedTierId.equals(current.getDeviceTierId())) {
            return PaymentTarget.extend(current);
        }
        Subscription sameTier = otherWithTier(subs, purchasedTierId, current);
        if (sameTier != null) {
            return PaymentTarget.extend(sameTier);
        }
        if (opensSeparateSubscription(telegramId, purchasedTierId, current, subs)) {
            return PaymentTarget.separate();
        }
        Subscription parked = firstParked(subs, current);
        return PaymentTarget.extend(parked != null && purchasedTierId != null ? parked : fallback);
    }

    /** Подписка КРОМЕ текущей, у которой ровно эта ступень; {@code null} — такой нет. */
    private static Subscription otherWithTier(List<Subscription> subs, String purchasedTierId,
                                              Subscription current) {
        if (purchasedTierId == null) {
            return null;
        }
        return subs.stream()
                .filter(s -> current == null || !Objects.equals(s.getId(), current.getId()))
                .filter(s -> purchasedTierId.equals(s.getDeviceTierId()))
                .findFirst()
                .orElse(null);
    }

    /** Первая замороженная по номеру — цель последней очереди вместо понижения работающей. */
    private static Subscription firstParked(List<Subscription> subs, Subscription current) {
        return subs.stream()
                .filter(s -> current == null || !Objects.equals(s.getId(), current.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Пора ли заводить отдельную подписку, когда канал цель не назвал.
     * <p>
     * Ступень сравнивается ТОЛЬКО с текущей подпиской. Человек без ступени
     * ({@code device_tier_id IS NULL} — это триальные и все, кто покупал до появления ступеней)
     * сюда не попадает вовсе: их «поглощение триала» и legacy-апгрейд обязаны остаться
     * такими же, какими были.
     */
    private boolean opensSeparateSubscription(long telegramId, String purchasedTierId,
                                              Subscription current, List<Subscription> subs) {
        if (!policy.canOpenNew(telegramId)) {
            return false;
        }
        if (current == null || purchasedTierId == null || current.getDeviceTierId() == null) {
            return false;
        }
        if (purchasedTierId.equals(current.getDeviceTierId())) {
            return false;
        }
        return subs.size() < policy.maxPerUser() && !sameTierExists(subs, purchasedTierId);
    }

    private static boolean sameTierExists(List<Subscription> subs, String purchasedTierId) {
        return purchasedTierId != null
                && subs.stream().anyMatch(s -> Objects.equals(s.getDeviceTierId(), purchasedTierId));
    }
}
