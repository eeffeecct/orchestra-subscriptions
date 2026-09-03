package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

    Optional<UserDevice> findByTelegramIdAndHwid(Long telegramId, String hwid);

    List<UserDevice> findAllByTelegramIdOrderByFirstSeenAtAsc(Long telegramId);

    /** Сколько слотов занято: ACTIVE-устройства, обновлявшие подписку после {@code seenAfter}. */
    long countByTelegramIdAndStatusAndLastSeenAtAfter(
            Long telegramId, UserDevice.Status status, LocalDateTime seenAfter);

    /** Всего строк устройств у юзера — для потолка MAX_TRACKED_DEVICES (анти-флуд по чужому токену). */
    long countByTelegramId(Long telegramId);

    /** Полная очистка устройств юзера — при смене ссылки подписки («выйти со всех устройств»). */
    void deleteByTelegramId(Long telegramId);

    // ========================
    //  Учёт В РАЗРЕЗЕ ПОДПИСКИ
    //
    //  Лимит устройств принадлежит подписке, а не человеку: у разных подписок он разный, и
    //  у каждой своя ссылка, то есть свой набор устройств. Считать «все устройства человека»
    //  было верно, пока подписка была одна; при нескольких это показывает «занято 3 из 1»
    //  и отключает не то.
    // ========================

    Optional<UserDevice> findBySubscriptionIdAndHwid(Long subscriptionId, String hwid);

    List<UserDevice> findAllBySubscriptionIdOrderByFirstSeenAtAsc(Long subscriptionId);

    long countBySubscriptionIdAndStatusAndLastSeenAtAfter(
            Long subscriptionId, UserDevice.Status status, LocalDateTime seenAfter);

    long countBySubscriptionId(Long subscriptionId);

    /** Очистка устройств одной подписки — при смене её ссылки. */
    void deleteBySubscriptionId(Long subscriptionId);

    /**
     * Устройства человека, не привязанные ни к одной подписке. Такие строки появляются, когда
     * учёт не смог определить подписку (зеркало не нашло строку и сработал фолбэк на
     * {@code customers}). Слот они не занимают, но остаются в списке «Мои устройства», поэтому
     * при смене ссылки работающей подписки их надо стирать вместе с её собственными — иначе
     * «выйти со всех устройств» оставляет на экране устройства, которые человек только что убрал.
     */
    void deleteByTelegramIdAndSubscriptionIdIsNull(Long telegramId);

    // ========================
    //  СРОК ХРАНЕНИЯ
    // ========================

    /**
     * Чистка по сроку хранения: удаляет отпечатки устройств, не появлявшихся с {@code cutoff}.
     * Отпечаток устройства — персональные данные, а до этой правки строки не удалялись ничем,
     * кроме «сменить ссылку» и вытеснения по потолку {@code MAX_TRACKED_DEVICES}.
     * Вызывается раз в сутки из {@link com.orchestra.subscriptions.service.RetentionCleanupService}.
     * <p>
     * Статус в условии НЕ участвует сознательно: срок хранения заведомо больше окна
     * {@code app.device-inactivity-days}, поэтому строка, попавшая под {@code cutoff}, слот
     * не занимает ни в одном статусе — удаление никому слот не отдаёт и не отнимает.
     * <p>
     * Bulk-DELETE одним запросом, без чтения сущностей: грузить ПДн в память ради удаления
     * незачем, а конкурировать за эти строки в момент чистки некому — последний фетч
     * подписки у них был больше полугода назад.
     */
    @Modifying
    @Query("DELETE FROM UserDevice d WHERE d.lastSeenAt < :cutoff")
    int deleteAllLastSeenBefore(@Param("cutoff") LocalDateTime cutoff);

    // ========================
    //  Админ-панель (только чтение)
    // ========================

    /**
     * Сколько устройств обновляло подписку после момента — грубый DAU/WAU/MAU для обзора.
     * ⚠️ Это «отметились на эндпоинте подписки», а не «пользовались сервисом»: клиент живёт на
     * закешированном конфиге сколько угодно, а поллить может и с выключенным туннелем.
     */
    long countByLastSeenAtAfter(LocalDateTime seenAfter);

    /** Список устройств для панели. {@code telegramId = 0} — без фильтра по человеку. */
    @Query("""
            SELECT d FROM UserDevice d
            WHERE (:telegramId = 0L OR d.telegramId = :telegramId)
              AND d.status IN :statuses
            """)
    org.springframework.data.domain.Page<UserDevice> adminSearch(
            @Param("telegramId") long telegramId,
            @Param("statuses") java.util.Collection<UserDevice.Status> statuses,
            org.springframework.data.domain.Pageable pageable);

    /**
     * По каждому из переданных людей: сколько отпечатков и когда самый свежий обновлял подписку.
     * {@code Object[]}: 0 = telegramId, 1 = count, 2 = MAX(lastSeenAt).
     */
    @Query("SELECT d.telegramId, COUNT(d), MAX(d.lastSeenAt) FROM UserDevice d "
            + "WHERE d.telegramId IN :ids GROUP BY d.telegramId")
    List<Object[]> aggregateByUser(@Param("ids") java.util.Collection<Long> ids);
}
