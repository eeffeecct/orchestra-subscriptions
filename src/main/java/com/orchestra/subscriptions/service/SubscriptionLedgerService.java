package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Запись в журнал сроков ({@link SubscriptionLedger}).
 * <p>
 * <b>Главное свойство: никогда не бросает и ничего не ломает.</b> Журнал — наблюдение, а не
 * бизнес-логика. Он пишется внутри платёжных транзакций, и упавшая запись истории не имеет
 * права откатить выдачу подписки: это ровно тот класс аварии «деньги списаны, услуги нет»,
 * который в этом сервисе уже чинили. Поэтому всё в try/catch, а исключение уходит в лог.
 * <p>
 * Транзакцию намеренно НЕ открываем: вызовы идут изнутри уже существующих транзакций
 * ({@code AccessService}), и вставка едет вместе с ними. Откатилась бизнес-операция — откатится
 * и её запись в журнале, что и требуется: журнал не должен помнить то, чего не произошло.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionLedgerService {

    /** Потолок выдачи в {@code /ledger}: больше в одно сообщение Telegram всё равно не влезет. */
    private static final int MAX_HISTORY = 20;

    private final SubscriptionLedgerRepository repository;
    /**
     * Нужен, чтобы подставить ТЕКУЩУЮ подписку тем вызывающим, которые её не называют
     * (триал, покупка, продление, урезание при возврате, истечение — всё это события
     * работающей подписки). Без подстановки колонка оставалась бы пустой у большинства
     * записей, и журнал человека с двумя подписками читался бы как перемешанный список.
     */
    private final com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository;

    /**
     * Записать изменение срока.
     *
     * @param telegramId       чей срок менялся
     * @param kind             причина изменения
     * @param deltaSeconds     на сколько подвинули ({@code +} добавили, {@code −} забрали)
     * @param balanceAfter     остаток оплаченного времени сразу после события
     * @param paymentId        платёж-причина, если есть
     * @param note             человекочитаемая пометка для разбора инцидентов
     */
    public void record(Long telegramId, SubscriptionLedger.Kind kind, long deltaSeconds,
                       long balanceAfter, Long paymentId, String note) {
        record(telegramId, currentSubscriptionId(telegramId), kind, deltaSeconds,
                balanceAfter, paymentId, note);
    }

    /**
     * Подписка, к которой относится событие, когда вызывающий её не назвал.
     * <p>
     * Короткую форму зовут пути, работающие с ТЕКУЩЕЙ подпиской: выдача триала, покупка,
     * продление, урезание срока при возврате, истечение. Подставлять здесь — надёжнее, чем
     * протаскивать id через пять мест: забытый аргумент не виден ни в компиляции, ни в
     * тестах, а проявляется только через месяцы, когда у человека появится вторая подписка
     * и историю станет невозможно разобрать.
     * <p>
     * {@code null} остаётся законным исходом: у человека может не быть строки в
     * {@code subscriptions} (зеркалирование выключено, бэкфилл не дошёл, первая покупка ещё
     * не создала подписку). Журнал в этом случае пишется как раньше — без адреса.
     */
    private Long currentSubscriptionId(Long telegramId) {
        if (telegramId == null) {
            return null;
        }
        try {
            return subscriptionRepository.findByTelegramIdAndCurrentTrue(telegramId)
                    .map(com.orchestra.subscriptions.model.Subscription::getId)
                    .orElse(null);
        } catch (Exception e) {
            // Журнал — наблюдение, а не право доступа: его сбой не должен ронять оплату.
            log.warn("Не смог определить текущую подписку для журнала (telegramId={})", telegramId, e);
            return null;
        }
    }

    /**
     * То же, но с указанием КОНКРЕТНОЙ подписки. Обязательно для всего, что двигает время не
     * у текущей подписки: без {@code subscription_id} журнал у человека с несколькими
     * подписками превращается в перемешанный список, по которому нельзя ответить на главный
     * вопрос разбора — «куда делись дни ЭТОЙ подписки».
     *
     * @param subscriptionId подписка-адресат; {@code null} — адрес неизвестен (у человека нет
     *                       строки в {@code subscriptions}). Пути, работающие с текущей
     *                       подпиской, адрес НЕ передают — его подставляет короткая форма
     *                       {@link #record(Long, SubscriptionLedger.Kind, long, long, Long, String)}.
     */
    public void record(Long telegramId, Long subscriptionId, SubscriptionLedger.Kind kind,
                       long deltaSeconds, long balanceAfter, Long paymentId, String note) {
        if (telegramId == null || kind == null) {
            return;
        }
        try {
            repository.save(SubscriptionLedger.builder()
                    .telegramId(telegramId)
                    .subscriptionId(subscriptionId)
                    .occurredAt(LocalDateTime.now())
                    .kind(kind)
                    .deltaSeconds(deltaSeconds)
                    .balanceAfterSeconds(Math.max(0L, balanceAfter))
                    .paymentId(paymentId)
                    .note(trimTo(note, 255))
                    .build());
        } catch (Exception e) {
            // Журнал не может быть причиной провала оплаты — см. джавадок класса.
            log.warn("Ledger write failed for telegramId={} kind={} — продолжаем без записи",
                    telegramId, kind, e);
        }
    }

    /**
     * Изменение срока, выраженное через «было → стало». Считает дельту сам, чтобы вызывающим
     * не дублировать арифметику: они и так знают обе даты.
     */
    public void recordExpiryChange(Long telegramId, SubscriptionLedger.Kind kind,
                                   LocalDateTime before, LocalDateTime after,
                                   Long paymentId, String note) {
        LocalDateTime now = LocalDateTime.now();
        long balanceBefore = SubscriptionTime.remainingSeconds(before, now);
        long balanceAfter = SubscriptionTime.remainingSeconds(after, now);
        record(telegramId, kind, balanceAfter - balanceBefore, balanceAfter, paymentId, note);
    }

    /** Последние события юзера, свежие сверху. */
    public List<SubscriptionLedger> history(Long telegramId) {
        try {
            return repository.findByTelegramIdOrderByOccurredAtDesc(
                    telegramId, PageRequest.of(0, MAX_HISTORY));
        } catch (Exception e) {
            log.warn("Ledger history read failed for telegramId={}", telegramId, e);
            return List.of();
        }
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
