package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.Subscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** Текущая (единственная активная) подписка человека. */
    Optional<Subscription> findByTelegramIdAndCurrentTrue(Long telegramId);

    /** Все подписки человека — для переключателя и админ-карточки. */
    List<Subscription> findByTelegramIdOrderBySeqAsc(Long telegramId);

    /**
     * Подписка по id, но только своя. Отдельно от {@link #findById}, чтобы владелец
     * проверялся ЗАПРОСОМ, а не сравнением после загрузки: сравнение легко забыть, и тогда
     * названный в кнопке id стал бы способом оплатить чужую подписку.
     */
    Optional<Subscription> findByIdAndTelegramId(Long id, Long telegramId);

    Optional<Subscription> findBySubscriptionToken(String subscriptionToken);

    Optional<Subscription> findByProviderUsername(String providerUsername);

    long countByTelegramId(Long telegramId);

    /**
     * Наибольший уже выданный номер подписки. Нужен аллокатору как страховка: колонку-счётчик
     * {@code customers.subscription_seq} могли не проставить (её ведёт бэкфилл, а юзер мог
     * появиться позже), и тогда номер выдался бы повторно — а повторный номер значит повторное
     * имя аккаунта в панели, то есть шанс «усыновить» чужой аккаунт при 409.
     * <p>
     * Это НЕ {@code count(*)}: количество строк и наибольший номер расходятся навсегда, стоит
     * появиться дыре в нумерации.
     */
    @Query("SELECT COALESCE(MAX(s.seq), 0) FROM Subscription s WHERE s.telegramId = :telegramId")
    int findMaxSeq(@Param("telegramId") Long telegramId);

    /**
     * Текущая подписка ПОД БЛОКИРОВКОЙ строки — для переключения. Порядок захвата замков в
     * проекте един: платёж → {@code customers} → {@code subscriptions}; нарушать его нельзя,
     * иначе переключение и входящий вебхук поймают взаимную блокировку.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.telegramId = :telegramId AND s.current = true")
    Optional<Subscription> findCurrentForUpdate(@Param("telegramId") Long telegramId);

    /**
     * Подписка по id, но только если она принадлежит этому человеку — иначе переключение
     * можно было бы навести на чужую строку, зная её id.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.id = :id AND s.telegramId = :telegramId")
    Optional<Subscription> findOwnedForUpdate(@Param("id") Long id,
                                              @Param("telegramId") Long telegramId);

    /**
     * Замороженные подписки, на которых лежат оплаченные дни. Вход для напоминания: человек
     * заплатил, но подписку так и не включил — деньги стоят, а доступ у него прежний.
     */
    List<Subscription> findByCurrentFalseAndBalanceSecondsGreaterThan(long minSeconds);

    /** Незакрытые намерения синхронизации с панелью — вход для реконсилера. */
    List<Subscription> findByProviderIntentIsNotNullOrderByProviderIntentAtAsc();

    /**
     * Люди, у которых есть строка в {@code customers}, но нет ни одной подписки — вход
     * для идемпотентного бэкфилла. Возвращает telegram_id, чтобы не тащить сущности.
     */
    @Query("""
            SELECT u.telegramId FROM Customer u
            WHERE u.providerUsername IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM Subscription s WHERE s.telegramId = u.telegramId)
            """)
    List<Long> findTelegramIdsWithoutSubscription();

    // ========================
    //  Оконные выборки планировщиков
    //
    //  ВСЕ — только по ТЕКУЩЕЙ подписке (current = true). Припаркованные сюда не попадут и
    //  без этого условия: у них expires_at = NULL, а любое сравнение с NULL ложно. Условие
    //  оставлено явно, чтобы намерение читалось, а не выводилось из устройства данных.
    // ========================

    /** Кандидаты на предупреждение «истекает» — которым его ещё не слали. */
    @Query("""
            SELECT s.telegramId FROM Subscription s
            WHERE s.current = true AND s.state = com.orchestra.subscriptions.model.Customer$Status.ACTIVE
              AND s.notified24h = false AND s.expiresAt BETWEEN :from AND :to
            """)
    List<Long> findTelegramIdsExpiringWithout24hNotice(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    @Query("""
            SELECT s.telegramId FROM Subscription s
            WHERE s.current = true AND s.state = com.orchestra.subscriptions.model.Customer$Status.ACTIVE
              AND s.notified1h = false AND s.expiresAt BETWEEN :from AND :to
            """)
    List<Long> findTelegramIdsExpiringWithout1hNotice(@Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to);

    /** Активные подписки, срок которых уже вышел — их пора отключать. */
    @Query("""
            SELECT s.telegramId FROM Subscription s
            WHERE s.current = true AND s.state = com.orchestra.subscriptions.model.Customer$Status.ACTIVE
              AND s.expiresAt < :now
            """)
    List<Long> findTelegramIdsExpired(@Param("now") LocalDateTime now);

    /** Отключённые, истёкшие в окне и ещё не получавшие winback. */
    @Query("""
            SELECT s.telegramId FROM Subscription s
            WHERE s.current = true AND s.state = com.orchestra.subscriptions.model.Customer$Status.DISABLED
              AND s.winbackSent = false AND s.expiresAt BETWEEN :from AND :to
            """)
    List<Long> findTelegramIdsForWinback(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /**
     * Кандидаты на автосписание. Наличие привязанной карты здесь НЕ проверяется: карта
     * принадлежит человеку, а не подписке, и живёт в {@code customers} — проверка остаётся
     * там же, где была, уже после загрузки юзера.
     */
    @Query("""
            SELECT s.telegramId FROM Subscription s
            WHERE s.current = true AND s.state = com.orchestra.subscriptions.model.Customer$Status.ACTIVE
              AND s.autorenewEnabled = true AND s.autorenewPlanId IS NOT NULL
              AND s.expiresAt BETWEEN :from AND :to
              AND (s.autorenewLastFailureAt IS NULL OR s.autorenewLastFailureAt < :failureCutoff)
            """)
    List<Long> findAutorenewCandidateTelegramIds(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to,
                                                 @Param("failureCutoff") LocalDateTime failureCutoff);

    /**
     * Расхождения зеркала с {@code customers} по сроку/статусу/лимиту/имени аккаунта.
     * Читается ночной сверкой и админ-командой {@code /subcheck}: пока источник правды —
     * старая таблица, любое расхождение означает пропущенную запись в зеркало.
     * <p>
     * Нативный запрос ради {@code IS DISTINCT FROM} — это NULL-безопасное «не равно».
     * На HQL пришлось бы расписывать четыре пары NULL-проверок на каждое поле, и любая
     * забытая пара давала бы ЛОЖНОЕ «расхождений нет» — худший исход для сверки, смысл
     * которой в том, чтобы заметить проблему раньше пользователя.
     */
    @Query(nativeQuery = true, value = """
            SELECT u.telegram_id
              FROM customers u
              JOIN subscriptions s ON s.telegram_id = u.telegram_id AND s.is_current
             WHERE u.expires_at    IS DISTINCT FROM s.expires_at
                OR u.status        IS DISTINCT FROM s.state
                OR u.device_limit  IS DISTINCT FROM s.device_limit
                OR u.provider_username  IS DISTINCT FROM s.provider_username
            """)
    List<Long> findDivergentTelegramIds();

    // ========================
    //  Админ-панель (только чтение)
    // ========================

    /**
     * Список подписок для панели.
     *
     * @param telegramId 0 — без фильтра по человеку
     * @param state      all | current (текущая) | parked (припаркованная)
     */
    @Query("""
            SELECT s FROM Subscription s
            WHERE (:telegramId = 0L OR s.telegramId = :telegramId)
              AND (:state = 'all'
                OR (:state = 'current' AND s.current = true)
                OR (:state = 'parked' AND s.current = false))
            """)
    org.springframework.data.domain.Page<Subscription> adminSearch(
            @Param("telegramId") long telegramId,
            @Param("state") String state,
            org.springframework.data.domain.Pageable pageable);

    /** Сколько подписок у каждого из переданных людей. {@code Object[]}: 0 = telegramId, 1 = count. */
    @Query("SELECT s.telegramId, COUNT(s) FROM Subscription s WHERE s.telegramId IN :ids GROUP BY s.telegramId")
    List<Object[]> countByTelegramIdIn(@Param("ids") java.util.Collection<Long> ids);
}
