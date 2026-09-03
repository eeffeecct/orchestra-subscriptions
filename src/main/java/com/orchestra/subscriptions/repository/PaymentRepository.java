package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByYookassaPaymentId(String yookassaPaymentId);
    Optional<Payment> findByCryptoInvoiceId(String cryptoInvoiceId);
    List<Payment> findByTelegramIdOrderByCreatedAtDesc(Long telegramId);

    // ===== Pessimistic-lock варианты для обработчиков вебхуков =====
    // Берём row-lock на входе в @Transactional-обработчик: параллельная повторная доставка
    // одного платежа блокируется до коммита первой и видит уже SUCCEEDED/CANCELED — это делает
    // проверку статуса атомарной и исключает дубликаты внешних эффектов (чек МойНалог, нотификации).

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.yookassaPaymentId = :id")
    Optional<Payment> findByYookassaPaymentIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.cryptoInvoiceId = :id")
    Optional<Payment> findByCryptoInvoiceIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /**
     * Атомарная идемпотентная запись telegram_charge_id (только если ещё пуст и платёж STARS).
     * Вызывается в REQUIRES_NEW-транзакции ДО активации, чтобы charge пережил откат активации
     * при сбое AccessProvider — Stars не передоставляются вебхуком, потерять id нельзя.
     */
    @Modifying
    @Query("UPDATE Payment p SET p.telegramChargeId = :chargeId " +
            "WHERE p.id = :id AND p.provider = com.orchestra.subscriptions.model.Payment.Provider.STARS " +
            "AND p.telegramChargeId IS NULL")
    int setStarsChargeIdIfAbsent(@Param("id") Long id, @Param("chargeId") String chargeId);

    /**
     * Ограниченная версия {@link #findByTelegramIdOrderByCreatedAtDesc} — берёт только
     * 20 самых свежих. Нужна там, где интересен
     * последний значимый платёж, а не вся история: экран «Мой доступ» и откат ступени
     * при возврате.
     * Экономит скан полной истории платежей у активных пользователей.
     */
    List<Payment> findTop20ByTelegramIdOrderByCreatedAtDesc(Long telegramId);

    // Для /stats: все платежи с указанным статусом, оплаченные после момента. Используется для
    // подсчёта выручки за период и группировки по провайдерам в памяти (объёмы маленькие,
    // пока без GROUP BY на уровне БД).
    List<Payment> findByStatusAndPaidAtAfter(Payment.Status status, LocalDateTime paidAfter);

    // Количество уникальных плательщиков (когда-либо совершивших успешный платёж).
    @Query("SELECT COUNT(DISTINCT p.telegramId) FROM Payment p WHERE p.status = :status")
    long countDistinctPayersByStatus(@Param("status") Payment.Status status);

    // Сколько платежей с указанным статусом у конкретного юзера. Используется в
    // ReferralService для определения "это первая SUCCEEDED-оплата" (count == 1).
    long countByTelegramIdAndStatus(Long telegramId, Payment.Status status);

    // Сколько платежей с любым из указанных статусов у конкретного юзера. Используется
    // в ReferralService для проверки "first-ever payment" с учётом REFUNDED, чтобы
    // закрыть эксплойт pay→refund→ref→pay (иначе после /refund статус уходит в REFUNDED
    // и следующий платёж снова считается первым).
    long countByTelegramIdAndStatusIn(Long telegramId, Collection<Payment.Status> statuses);

    /**
     * Платил ли юзер хоть раз. Используется в SubscriptionExpiryNotifier чтобы отличить
     * триал от платной подписки: префикс {@code provider_username} ненадёжен (trial→paid апгрейд
     * не переименовывает AccessProvider-аккаунт), а наличие SUCCEEDED/REFUNDED-платежа — надёжно.
     */
    boolean existsByTelegramIdAndStatusIn(Long telegramId, Collection<Payment.Status> statuses);

    /** Есть ли у юзера недавний refund. Нужно чтобы НЕ слать winback тем кто сам ушёл. */
    boolean existsByTelegramIdAndStatusAndRefundedAtAfter(
            Long telegramId, Payment.Status status, LocalDateTime refundedAfter);

    /**
     * Зависшие PENDING-платежи YooKassa, по которым уже создан yookassa_payment_id, но
     * webhook так и не пришёл (например, контейнер рестартовал в момент доставки).
     * Используется в {@link com.orchestra.subscriptions.service.PaymentReconcileService}: периодически
     * берём batch и опрашиваем YK API; если там succeeded — догоняем активацию вручную.
     * <p>
     * Фильтры:
     * <ul>
     *   <li>{@code status = PENDING} — только незавершённые;</li>
     *   <li>{@code provider = YOOKASSA} — у Stars и CryptoBot свои потоки/идемпотентность;</li>
     *   <li>{@code yookassa_payment_id IS NOT NULL} — без id опрашивать YK нечем
     *       (такие строки бывают, если createPayment упал между save() и API-вызовом);</li>
     *   <li>{@code created_at &lt; threshold} — слишком молодые ещё могут штатно дойти через webhook,
     *       не дёргаем их.</li>
     * </ul>
     */
    @Query("SELECT p FROM Payment p " +
            "WHERE p.status = com.orchestra.subscriptions.model.Payment.Status.PENDING " +
            "AND p.provider = com.orchestra.subscriptions.model.Payment.Provider.YOOKASSA " +
            "AND p.yookassaPaymentId IS NOT NULL " +
            "AND p.createdAt < :threshold " +
            "ORDER BY p.createdAt ASC")
    List<Payment> findStaleYookassaPending(@Param("threshold") LocalDateTime threshold,
                                           Pageable pageable);

    /**
     * Зависшие PENDING CryptoBot-платежи: invoice создан, но webhook invoice_paid не довёл
     * активацию (контейнер рестартовал / AccessProvider был недоступен → откат). Реконсилер опросит
     * Crypto Pay API по cryptoInvoiceId и догонит активацию. Раньше реконсилер крипту не видел
     * вовсе — оплаченный инвойс висел PENDING навсегда.
     */
    @Query("SELECT p FROM Payment p " +
            "WHERE p.status = com.orchestra.subscriptions.model.Payment.Status.PENDING " +
            "AND p.provider = com.orchestra.subscriptions.model.Payment.Provider.CRYPTOBOT " +
            "AND p.cryptoInvoiceId IS NOT NULL " +
            "AND p.createdAt < :threshold " +
            "ORDER BY p.createdAt ASC")
    List<Payment> findStaleCryptoPending(@Param("threshold") LocalDateTime threshold,
                                         Pageable pageable);

    /**
     * Stars-платежи, по которым Telegram уже списал звёзды (есть telegram_charge_id), но
     * активация не прошла и платёж застрял в PENDING. У Stars нет webhook-передоставки —
     * реконсилер повторяет активацию по сохранённому charge id, иначе деньги списаны, а
     * подписки нет, без авто-восстановления.
     */
    @Query("SELECT p FROM Payment p " +
            "WHERE p.status = com.orchestra.subscriptions.model.Payment.Status.PENDING " +
            "AND p.provider = com.orchestra.subscriptions.model.Payment.Provider.STARS " +
            "AND p.telegramChargeId IS NOT NULL " +
            "AND p.createdAt < :threshold " +
            "ORDER BY p.createdAt ASC")
    List<Payment> findStaleStarsPending(@Param("threshold") LocalDateTime threshold,
                                        Pageable pageable);

    /**
     * Все платежи указанного статуса для набора telegram_id, отсортированные по {@code paid_at} desc.
     * Используется в /metrics: для каждого активного платящего юзера нужно найти его последнюю
     * SUCCEEDED-оплату, чтобы по ней понять текущий тариф (для расчёта MRR). За один запрос
     * выгружаем все платежи и группируем по юзеру в памяти — на 20-30 активных юзерах с
     * историей по 5-10 платежей это меньше 300 строк.
     */
    List<Payment> findByStatusAndTelegramIdInOrderByPaidAtDesc(
            Payment.Status status, Collection<Long> telegramIds);

    /**
     * {@code (telegram_id, MIN(paid_at))} для каждого юзера, у которого есть хотя бы одна
     * SUCCEEDED-оплата с заполненным {@code paid_at}. Это «дата первой оплаты» — основа для
     * cohort retention (юзер попадает в когорту того месяца, в котором впервые заплатил).
     * Object[0] = telegramId (Long), Object[1] = firstPaidAt (LocalDateTime).
     */
    @Query("SELECT p.telegramId, MIN(p.paidAt) FROM Payment p " +
            "WHERE p.status = com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED " +
            "AND p.paidAt IS NOT NULL " +
            "GROUP BY p.telegramId")
    List<Object[]> findFirstSuccessfulPaymentDatePerUser();

    /**
     * История платежей для админ-панели: свободный поиск плюс фильтры по статусу и провайдеру.
     * Списки {@code statuses}/{@code providers} всегда непустые — «фильтр не задан» приходит как
     * полный набор значений enum'а (см. {@code AdminPanelService.enums}).
     *
     * @param q подстрока в нижнем регистре, обрамлённая {@code %}, либо {@code ''} = без поиска
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE (:q = '' OR LOWER(COALESCE(p.telegramUsername, '')) LIKE :q
                           OR LOWER(COALESCE(p.planId, '')) LIKE :q
                           OR LOWER(COALESCE(p.promoCodeUsed, '')) LIKE :q
                           OR LOWER(COALESCE(p.yookassaPaymentId, '')) LIKE :q
                           OR LOWER(COALESCE(p.cryptoInvoiceId, '')) LIKE :q
                           OR CAST(p.telegramId AS string) LIKE :q
                           OR CAST(p.id AS string) LIKE :q)
              AND p.status IN :statuses
              AND p.provider IN :providers
            """)
    org.springframework.data.domain.Page<Payment> adminSearch(
            @Param("q") String q,
            @Param("statuses") Collection<Payment.Status> statuses,
            @Param("providers") Collection<Payment.Provider> providers,
            Pageable pageable);

    /**
     * По каждому из переданных людей: сколько успешных оплат, на какую сумму и когда последняя.
     * Один запрос на страницу списка вместо трёх обращений на строку.
     * <p>
     * {@code Object[]}: 0 = telegramId (Long), 1 = count (Long), 2 = сумма копеек (Long),
     * 3 = последний paidAt (LocalDateTime, может быть null у старых строк без даты).
     */
    @Query("SELECT p.telegramId, COUNT(p), COALESCE(SUM(p.amountKopeks), 0), MAX(p.paidAt) FROM Payment p " +
            "WHERE p.status = com.orchestra.subscriptions.model.Payment.Status.SUCCEEDED " +
            "AND p.telegramId IN :ids GROUP BY p.telegramId")
    List<Object[]> aggregateSucceededByUser(@Param("ids") Collection<Long> ids);
}
