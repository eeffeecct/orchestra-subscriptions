package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Единственная точка чтения подписочного состояния. Прячет за собой ГЛАВНЫЙ переключатель
 * переезда: читать из старой {@code customers} или из новой {@code subscriptions}.
 * <p>
 * <b>Зачем так.</b> Пока подписка у человека одна, обе таблицы обязаны отдавать неотличимый
 * результат — таблица подписок ведётся как точная копия и еженощно сверяется. Значит переезд
 * можно делать по одному потребителю за раз, каждый раз проверяя тестами, что при флаге
 * включённом и выключенном ответ одинаковый. Когда все потребители переедут, флаг перестанет
 * иметь значение, и только тогда появится смысл заводить вторую подписку.
 * <p>
 * <b>Флаг выключен — поведение ровно сегодняшнее</b>, до последнего запроса. Это и есть путь
 * отката: не деплой, а один параметр.
 * <p>
 * Полный план — {@code docs/multi-subscription-plan.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionLookupService {

    private final CustomerRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * Источник правды для чтения. {@code false} (пока) — старая таблица; {@code true} —
     * таблица подписок. Переключается на проде без пересборки.
     */
    @Value("${app.subscriptions.read-model-enabled:false}")
    private boolean readFromSubscriptions;

    /**
     * Подписка по подписочному токену (из ссылки {@code /sub/<token>}).
     * <p>
     * Если новый источник ничего не нашёл — читаем из старого (см. {@link #fallback}).
     */
    @Transactional(readOnly = true)
    public Optional<SubscriptionView> byToken(String subscriptionToken) {
        if (subscriptionToken == null || subscriptionToken.isBlank()) {
            return Optional.empty();
        }
        Optional<SubscriptionView> legacy =
                userRepository.findBySubscriptionToken(subscriptionToken).map(SubscriptionView::of);
        if (!readFromSubscriptions) {
            return legacy;
        }
        return fallback(subscriptionRepository.findBySubscriptionToken(subscriptionToken)
                .map(this::withOwner), legacy, "token");
    }

    /** Подписка по имени аккаунта в AccessProvider. */
    @Transactional(readOnly = true)
    public Optional<SubscriptionView> byProviderUsername(String providerUsername) {
        if (providerUsername == null || providerUsername.isBlank()) {
            return Optional.empty();
        }
        Optional<SubscriptionView> legacy =
                userRepository.findByProviderUsername(providerUsername).map(SubscriptionView::of);
        if (!readFromSubscriptions) {
            return legacy;
        }
        return fallback(subscriptionRepository.findByProviderUsername(providerUsername)
                .map(this::withOwner), legacy, "providerUsername=" + providerUsername);
    }

    /** Текущая подписка человека. */
    @Transactional(readOnly = true)
    public Optional<SubscriptionView> currentFor(Long telegramId) {
        if (telegramId == null) {
            return Optional.empty();
        }
        Optional<SubscriptionView> legacy =
                userRepository.findByTelegramId(telegramId).map(SubscriptionView::of);
        if (!readFromSubscriptions) {
            return legacy;
        }
        return fallback(subscriptionRepository.findByTelegramIdAndCurrentTrue(telegramId)
                .map(this::withOwner), legacy, "telegramId=" + telegramId);
    }

    /**
     * Страховка на время переезда: нет строки в новом источнике — берём из старого.
     * <p>
     * Таблица подписок — ПРОИЗВОДНЫЕ данные. Её пустота означает пробел в нашем учёте
     * (пропущенное зеркалирование, недоехавший перенос), а не то, что у человека нет
     * подписки. Отказать ему в доступе из-за нашей же бухгалтерии было бы худшим из
     * возможных поведений, поэтому падаем в сторону правды, а не в сторону отказа.
     * <p>
     * Пробел при этом не заметается: он логируется и ловится ночной сверкой ({@code /subcheck}),
     * а перенос на следующем старте заводит недостающие строки.
     */
    private Optional<SubscriptionView> fallback(Optional<SubscriptionView> fromSubscriptions,
                                                Optional<SubscriptionView> fromUsers,
                                                String what) {
        if (fromSubscriptions.isPresent() || fromUsers.isEmpty()) {
            return fromSubscriptions;
        }
        log.warn("Зеркало подписок не нашло {} — читаю из основной таблицы. Это пробел в учёте, "
                + "поймает ночная сверка (/subcheck)", what);
        return fromUsers;
    }

    // ========================
    //  Оконные выборки планировщиков
    //
    //  Отдают ТЕ ЖЕ сущности {@code Customer}, что и раньше: планировщики не только читают
    //  кандидатов, но и правят их (флаги «уведомлён», счётчик неудач), а источник правды на
    //  запись по-прежнему {@code customers}. Переезжает именно ВЫБОРКА — то, кого считать
    //  кандидатом; сам цикл обработки не меняется ни на строку.
    // ========================

    /** Кому пора слать предупреждение «истекает через сутки». */
    @Transactional(readOnly = true)
    public List<Customer> expiringWithout24hNotice(LocalDateTime from, LocalDateTime to) {
        if (!readFromSubscriptions) {
            return userRepository.findAllByStatusAndNotified24hFalseAndExpiresAtBetween(
                    Customer.Status.ACTIVE, from, to);
        }
        return loadUsers(subscriptionRepository.findTelegramIdsExpiringWithout24hNotice(from, to));
    }

    /** Кому пора слать предупреждение «истекает через час». */
    @Transactional(readOnly = true)
    public List<Customer> expiringWithout1hNotice(LocalDateTime from, LocalDateTime to) {
        if (!readFromSubscriptions) {
            return userRepository.findAllByStatusAndNotified1hFalseAndExpiresAtBetween(
                    Customer.Status.ACTIVE, from, to);
        }
        return loadUsers(subscriptionRepository.findTelegramIdsExpiringWithout1hNotice(from, to));
    }

    /** Активные подписки с вышедшим сроком — пора отключать. */
    @Transactional(readOnly = true)
    public List<Customer> expired(LocalDateTime now) {
        if (!readFromSubscriptions) {
            return userRepository.findAllByStatusAndExpiresAtBefore(Customer.Status.ACTIVE, now);
        }
        return loadUsers(subscriptionRepository.findTelegramIdsExpired(now));
    }

    /** Отключённые, истёкшие в окне и ещё не получавшие «возвращайтесь». */
    @Transactional(readOnly = true)
    public List<Customer> winbackCandidates(LocalDateTime from, LocalDateTime to) {
        if (!readFromSubscriptions) {
            return userRepository.findAllByStatusAndWinbackSentFalseAndExpiresAtBetween(
                    Customer.Status.DISABLED, from, to);
        }
        return loadUsers(subscriptionRepository.findTelegramIdsForWinback(from, to));
    }

    /**
     * Кандидаты на автосписание.
     * <p>
     * Проверка привязанной карты ({@code autorenew_method_id}) в обоих режимах остаётся на
     * стороне человека: карта принадлежит человеку, а не подписке. В новом режиме она
     * применяется здесь, после загрузки, — иначе условие «карта есть» просто исчезло бы и
     * планировщик пытался бы списать с несуществующего метода.
     */
    @Transactional(readOnly = true)
    public List<Customer> autorenewCandidates(LocalDateTime from, LocalDateTime to,
                                             LocalDateTime failureCutoff) {
        if (!readFromSubscriptions) {
            return userRepository.findAutorenewCandidates(from, to, failureCutoff);
        }
        return loadUsers(subscriptionRepository
                .findAutorenewCandidateTelegramIds(from, to, failureCutoff)).stream()
                .filter(u -> u.getAutorenewMethodId() != null)
                .toList();
    }

    /**
     * Подгружает людей по id кандидатов. Объёмы тут малы по устройству задачи: это выборки
     * «истекает в ближайший час/сутки», а не полная база, — поэтому простая загрузка по
     * одному честнее преждевременной оптимизации. Пропавший юзер (подписка есть, человека
     * нет) молча пропускается: это рассинхрон, а не повод ронять весь прогон планировщика.
     */
    private List<Customer> loadUsers(List<Long> telegramIds) {
        return telegramIds.stream()
                .map(userRepository::findByTelegramId)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Достаёт имя в Telegram — оно живёт на человеке, а не на подписке. Человека может не
     * оказаться только при рассинхроне (подписка есть, юзера нет); это не повод отказывать
     * в доступе, поэтому просто отдаём подписку без имени.
     */
    private SubscriptionView withOwner(Subscription sub) {
        String telegramUsername = userRepository.findByTelegramId(sub.getTelegramId())
                .map(u -> u.getTelegramUsername())
                .orElse(null);
        return SubscriptionView.of(sub, telegramUsername);
    }
}
