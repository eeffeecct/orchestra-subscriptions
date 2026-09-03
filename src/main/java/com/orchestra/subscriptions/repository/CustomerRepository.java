package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByTelegramId(Long telegramId);
    Optional<Customer> findByProviderUsername(String providerUsername);

    /** Лукап «подписочный токен → юзер» для /sub/<token> (детект устройств). */
    Optional<Customer> findBySubscriptionToken(String subscriptionToken);
    /** Поиск по email (нормализованный lowercase). Используется при сохранении email
     *  для детекции коллизии до того как UNIQUE-constraint выкинет DataIntegrityViolation,
     *  и в magic-link флоу — найти юзера по адресу из письма. */
    Optional<Customer> findByEmail(String email);
    /** Case-insensitive поиск по @username Telegram'а. Используется в админ-командах,
     *  где админ помнит ник, а не numeric ID. Username может быть в любом регистре в БД
     *  (Telegram возвращает как пользователь установил), поэтому LOWER на обеих сторонах. */
    Optional<Customer> findByTelegramUsernameIgnoreCase(String telegramUsername);
    boolean existsByTelegramId(Long telegramId);
    List<Customer> findAllByStatusAndExpiresAtBefore(Customer.Status status, LocalDateTime time);
    List<Customer> findAllByStatusAndNotified24hFalseAndExpiresAtBetween(Customer.Status status, LocalDateTime from, LocalDateTime to);
    List<Customer> findAllByStatusAndNotified1hFalseAndExpiresAtBetween(Customer.Status status, LocalDateTime from, LocalDateTime to);

    /**
     * Человек ПОД БЛОКИРОВКОЙ строки — точка сериализации между переключением подписок и
     * входящим вебхуком оплаты. Порядок захвата замков в проекте един:
     * платёж → customers → subscriptions.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM Customer u WHERE u.telegramId = :telegramId")
    Optional<Customer> findByTelegramIdForUpdate(@org.springframework.data.repository.query.Param("telegramId") Long telegramId);

    List<Customer> findAllByStatus(Customer.Status status);

    /**
     * Кандидаты для post-expiry winback'а: disabled-юзеры, чья подписка истекла
     * N дней назад и которые ещё не получили напоминание.
     */
    List<Customer> findAllByStatusAndWinbackSentFalseAndExpiresAtBetween(
            Customer.Status status, LocalDateTime from, LocalDateTime to);

    // Для /stats: счётчики юзеров по разным измерениям.
    long countByStatus(Customer.Status status);
    long countByStatusAndExpiresAtAfter(Customer.Status status, LocalDateTime time);
    long countByStatusAndProviderUsernameStartingWith(Customer.Status status, String prefix);
    long countByCreatedAtAfter(LocalDateTime time);
    long countByProviderUsernameStartingWith(String prefix);

    /**
     * Сколько юзеров, когда-либо взявших триал, ВПОСЛЕДСТВИИ оплатили подписку.
     * providerUsername остаётся "trial_*" даже после апгрейда на платный — см. handleSucceeded
     * в PaymentProcessingService. Поэтому ищем по префиксу + наличию SUCCEEDED-платежа.
     */
    @Query("SELECT COUNT(DISTINCT u.telegramId) FROM Customer u WHERE u.providerUsername LIKE 'trial\\_%' ESCAPE '\\' " +
            "AND EXISTS (SELECT 1 FROM Payment p WHERE p.telegramId = u.telegramId AND p.status = com.orchestra.subscriptions.model.Payment$Status.SUCCEEDED)")
    long countTrialUsersWhoPaid();

    /**
     * Сколько платных подписок истекло в окне [from, to] — юзер DISABLED, expires_at попал
     * в окно, и в истории есть хотя бы один SUCCEEDED-платёж. Прокси для churn'а.
     */
    @Query("SELECT COUNT(DISTINCT u.telegramId) FROM Customer u WHERE u.status = com.orchestra.subscriptions.model.Customer$Status.DISABLED " +
            "AND u.expiresAt BETWEEN :from AND :to " +
            "AND EXISTS (SELECT 1 FROM Payment p WHERE p.telegramId = u.telegramId AND p.status = com.orchestra.subscriptions.model.Payment$Status.SUCCEEDED)")
    long countPaidUsersExpiredBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Telegram ID юзеров, у которых СЕЙЧАС активная платная подписка: status=ACTIVE,
     * expires_at в будущем, есть хотя бы один SUCCEEDED-платёж в истории. Используется
     * в /metrics для расчёта MRR (для каждого такого юзера берём его последний SUCCEEDED
     * платёж и нормализуем сумму к месячному эквиваленту).
     */
    @Query("SELECT u.telegramId FROM Customer u " +
            "WHERE u.status = com.orchestra.subscriptions.model.Customer$Status.ACTIVE " +
            "AND u.expiresAt > :now " +
            "AND EXISTS (SELECT 1 FROM Payment p WHERE p.telegramId = u.telegramId " +
            "AND p.status = com.orchestra.subscriptions.model.Payment$Status.SUCCEEDED)")
    List<Long> findActivePayingTelegramIds(@Param("now") LocalDateTime now);

    /**
     * Кандидаты для автосписания: активные юзеры с привязанной картой, включённым toggle,
     * истекающие в окне {@code [from, to]}, и не имеющие недавнего failure (чтобы не
     * ретраить одно и то же истечение в течение одного окна планировщика).
     */
    @Query("SELECT u FROM Customer u WHERE u.status = com.orchestra.subscriptions.model.Customer$Status.ACTIVE " +
            "AND u.autorenewEnabled = true " +
            "AND u.autorenewMethodId IS NOT NULL " +
            "AND u.autorenewPlanId IS NOT NULL " +
            "AND u.expiresAt BETWEEN :from AND :to " +
            "AND (u.autorenewLastFailureAt IS NULL OR u.autorenewLastFailureAt < :failureCutoff)")
    List<Customer> findAutorenewCandidates(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("failureCutoff") LocalDateTime failureCutoff);

    /**
     * Кандидаты на авто-release «застрявшего» неподтверждённого email: адрес
     * задан, верификация не прошла, а консент-таймстамп старше cutoff. Guard
     * {@code emailConsentAt IS NOT NULL} защищает legacy-юзеров без consent-
     * timestamp (если такие остались до 016-миграции) — их пусть админ ручкой.
     * <p>
     * Используется {@link com.orchestra.subscriptions.service.EmailLifecycleService} раз в
     * сутки. Корневой кейс: юзер A опечатался и привязал email юзера B, magic-
     * link улетел B, A никогда не подтвердит. Без cleanup'а B навсегда не может
     * claim'нуть свой адрес.
     */
    @Query("SELECT u FROM Customer u WHERE u.email IS NOT NULL " +
            "AND u.emailVerifiedAt IS NULL " +
            "AND u.emailConsentAt IS NOT NULL " +
            "AND u.emailConsentAt < :cutoff")
    List<Customer> findStaleUnverifiedEmails(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Постраничная выборка людей для админ-панели: свободный поиск + один из
     * взаимоисключающих срезов. Сортировку задаёт {@link org.springframework.data.domain.Pageable}
     * (список допустимых полей закрыт в {@code AdminPanelService}).
     * <p>
     * Пустые значения вместо {@code null} — сознательно: {@code :param IS NULL} в Postgres
     * требует явного приведения типа параметра, а сравнение с литералом {@code ''} типизирует
     * его само. {@code :q} приходит уже в виде {@code %подстрока%} в нижнем регистре.
     *
     * @param mode all | active | expired | paying | trial | email | autorenew
     */
    @Query("""
            SELECT u FROM Customer u
            WHERE (:q = '' OR LOWER(COALESCE(u.telegramUsername, '')) LIKE :q
                           OR LOWER(COALESCE(u.email, '')) LIKE :q
                           OR LOWER(COALESCE(u.providerUsername, '')) LIKE :q
                           OR CAST(u.telegramId AS string) LIKE :q)
              AND (:mode = 'all'
                OR (:mode = 'active' AND u.status = com.orchestra.subscriptions.model.Customer.Status.ACTIVE
                                     AND u.expiresAt > :now)
                OR (:mode = 'expired' AND (u.status = com.orchestra.subscriptions.model.Customer.Status.DISABLED
                                       OR u.expiresAt IS NULL OR u.expiresAt <= :now))
                OR (:mode = 'paying' AND EXISTS (SELECT 1 FROM Payment p WHERE p.telegramId = u.telegramId
                                       AND p.status = com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED))
                OR (:mode = 'trial' AND NOT EXISTS (SELECT 1 FROM Payment p2 WHERE p2.telegramId = u.telegramId
                                       AND p2.status = com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED))
                OR (:mode = 'email' AND u.email IS NOT NULL)
                OR (:mode = 'autorenew' AND u.autorenewEnabled = true AND u.autorenewMethodId IS NOT NULL))
            """)
    org.springframework.data.domain.Page<Customer> adminSearch(
            @Param("q") String q,
            @Param("mode") String mode,
            @Param("now") LocalDateTime now,
            org.springframework.data.domain.Pageable pageable);

    /** Юзеры набора id — чтобы дописать @username к строкам платежей/подписок одним запросом. */
    List<Customer> findByTelegramIdIn(java.util.Collection<Long> telegramIds);
}
