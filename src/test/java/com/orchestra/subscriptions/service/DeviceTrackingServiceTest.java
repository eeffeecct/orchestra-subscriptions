package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.web.api.RequestRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тест учёта устройств по HWID с подписочного эндпоинта: регистрация нового устройства,
 * enforcement лимита (BLOCKED сверх лимита, авто-активация при освобождении слота),
 * passthrough для клиентов без HWID и fail-open поведение.
 */
class DeviceTrackingServiceTest {

    private static final String TOKEN = "tok_abc";
    private static final long TG_ID = 42L;
    private static final String HWID = "aaaaaaaa-1111-2222-3333-444444444444";

    private CustomerRepository userRepository;
    private com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository;
    private SubscriptionLookupService lookup;
    private UserDeviceRepository deviceRepository;
    private AppConfig appConfig;
    private DeviceNotificationService notifications;
    private DeviceTrackingService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(CustomerRepository.class);
        deviceRepository = mock(UserDeviceRepository.class);
        appConfig = new AppConfig(); // реальный бин: default-device-limit=0, inactivity=30
        notifications = mock(DeviceNotificationService.class);
        // Реальный limiter: 5 DM/час на юзера — в юнит-тестах лимит не выбирается,
        // кроме кейсов, которые проверяют его явно.
        // НАСТОЯЩИЙ фасад чтения поверх моков: проверки ниже остаются прежними, а источник
        // (старая таблица или новая) переключается флагом — ровно как на проде.
        subscriptionRepository = mock(com.orchestra.subscriptions.repository.SubscriptionRepository.class);
        lookup = new SubscriptionLookupService(userRepository, subscriptionRepository);
        readFromSubscriptions(false);
        service = new DeviceTrackingService(
                lookup, deviceRepository, appConfig, notifications, new RequestRateLimiter());
    }

    /** Переключает источник чтения так же, как {@code app.subscriptions.read-model-enabled}. */
    private void readFromSubscriptions(boolean enabled) {
        org.springframework.test.util.ReflectionTestUtils.setField(
                lookup, "readFromSubscriptions", enabled);
    }

    private Customer user(Integer deviceLimit) {
        return Customer.builder().telegramId(TG_ID).deviceLimit(deviceLimit).build();
    }

    private DeviceTrackingService.DeviceHeaders headers(String hwid) {
        return new DeviceTrackingService.DeviceHeaders(hwid, "iPhone15,2", "iOS", "18.3", "клиентское приложение/4.6.0");
    }

    private void stubUser(Integer deviceLimit) {
        when(userRepository.findBySubscriptionToken(TOKEN)).thenReturn(Optional.of(user(deviceLimit)));
    }

    private void stubActiveCount(long count) {
        when(deviceRepository.countByTelegramIdAndStatusAndLastSeenAtAfter(
                eq(TG_ID), eq(UserDevice.Status.ACTIVE), any())).thenReturn(count);
        // Слоты считаются в разрезе подписки: у разных подписок разный лимит и свой набор
        // устройств. Заглушка отвечает одинаково на оба среза, чтобы сценарии ниже
        // проверяли ЛОГИКУ лимита, а не то, какой счётчик оказался под рукой.
        when(deviceRepository.countBySubscriptionIdAndStatusAndLastSeenAtAfter(
                any(), eq(UserDevice.Status.ACTIVE), any())).thenReturn(count);
    }

    // ---------- passthrough ----------

    @Test
    void noHwid_allowsWithoutTracking() {
        assertThat(service.onSubscriptionFetch(TOKEN, headers(null)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verifyNoInteractions(userRepository, deviceRepository, notifications);
    }

    @Test
    void malformedHwid_allowsWithoutTracking() {
        assertThat(service.onSubscriptionFetch(TOKEN, headers("тьфу<script>")))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verifyNoInteractions(deviceRepository);
    }

    @Test
    void unknownToken_allows() {
        when(userRepository.findBySubscriptionToken(anyString())).thenReturn(Optional.empty());
        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verifyNoInteractions(deviceRepository);
    }

    @Test
    void repositoryBlowsUp_failsOpen() {
        when(userRepository.findBySubscriptionToken(anyString()))
                .thenThrow(new RuntimeException("db down"));
        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
    }

    // ---------- новое устройство ----------

    @Test
    void newDevice_underLimit_registersActiveAndNotifies() {
        stubUser(3);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        stubActiveCount(1);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);

        ArgumentCaptor<UserDevice> cap = ArgumentCaptor.forClass(UserDevice.class);
        verify(deviceRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(cap.getValue().getHwid()).isEqualTo(HWID);
        verify(notifications).sendDeviceConnected(eq(TG_ID), anyString(), eq(2L), eq(3));
    }

    @Test
    void newDevice_overLimit_registersBlockedAndNotifies() {
        stubUser(2);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        stubActiveCount(2);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.BLOCK);

        ArgumentCaptor<UserDevice> cap = ArgumentCaptor.forClass(UserDevice.class);
        verify(deviceRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
        verify(notifications).sendDeviceLimitReached(eq(TG_ID), anyString(), eq(2));
        verify(notifications, never()).sendDeviceConnected(anyLong(), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void newDevice_legacyUserWithoutLimit_registersActive() {
        // device_limit=null + default-device-limit=0 → лимита нет, но учёт и уведомление есть
        stubUser(null);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        stubActiveCount(7);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verify(notifications).sendDeviceConnected(eq(TG_ID), anyString(), eq(8L), eq(0));
    }

    // ---------- существующее устройство ----------

    private UserDevice existing(UserDevice.Status status, LocalDateTime lastSeen) {
        return UserDevice.builder()
                .id(5L).telegramId(TG_ID).hwid(HWID).status(status)
                .firstSeenAt(lastSeen).lastSeenAt(lastSeen)
                .build();
    }

    @Test
    void activeDevice_staleLastSeen_touchedAndAllowed() {
        stubUser(3);
        UserDevice device = existing(UserDevice.Status.ACTIVE, LocalDateTime.now().minusHours(5));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(device));

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verify(deviceRepository).save(device); // last_seen сдвинулся (>1ч)
        verifyNoInteractions(notifications);
    }

    @Test
    void activeDevice_freshLastSeen_notRewritten() {
        stubUser(3);
        UserDevice device = existing(UserDevice.Status.ACTIVE, LocalDateTime.now().minusMinutes(5));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(device));

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verify(deviceRepository, never()).save(any()); // полл чаще шага — БД не трогаем
    }

    @Test
    void revokedDevice_blocked() {
        stubUser(3);
        UserDevice device = existing(UserDevice.Status.REVOKED, LocalDateTime.now().minusDays(1));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(device));

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.BLOCK);
        assertThat(device.getStatus()).isEqualTo(UserDevice.Status.REVOKED); // само не возвращается
    }

    @Test
    void blockedDevice_slotFreed_promotedToActive() {
        stubUser(2);
        UserDevice device = existing(UserDevice.Status.BLOCKED, LocalDateTime.now().minusDays(1));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(device));
        stubActiveCount(1); // слот освободился

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        assertThat(device.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        verify(notifications).sendDeviceConnected(eq(TG_ID), anyString(), anyLong(), eq(2));
    }

    @Test
    void blockedDevice_stillOverLimit_staysBlocked() {
        stubUser(2);
        UserDevice device = existing(UserDevice.Status.BLOCKED, LocalDateTime.now().minusDays(1));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(device));
        stubActiveCount(2);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.BLOCK);
        assertThat(device.getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
    }

    // ---------- потолок хранимых устройств (анти-флуд по чужому токену) ----------

    private UserDevice rowWithStatus(long id, UserDevice.Status status, LocalDateTime lastSeen) {
        return UserDevice.builder()
                .id(id).telegramId(TG_ID).hwid("hwid-" + id).status(status)
                .firstSeenAt(lastSeen).lastSeenAt(lastSeen)
                .build();
    }

    @Test
    void deviceCap_reached_evictsOldestInactiveAndRegisters() {
        stubUser(3);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        when(deviceRepository.countByTelegramId(TG_ID)).thenReturn(20L);
        UserDevice activeRow = rowWithStatus(1, UserDevice.Status.ACTIVE, LocalDateTime.now().minusDays(40));
        UserDevice staleBlocked = rowWithStatus(2, UserDevice.Status.BLOCKED, LocalDateTime.now().minusDays(20));
        UserDevice revoked = rowWithStatus(3, UserDevice.Status.REVOKED, LocalDateTime.now().minusDays(60));
        when(deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(TG_ID))
                .thenReturn(java.util.List.of(activeRow, staleBlocked, revoked));
        stubActiveCount(1);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        // BLOCKED вытесняется раньше REVOKED (отключение юзером — осознанный выбор), ACTIVE не трогаем
        verify(deviceRepository).delete(staleBlocked);
        verify(deviceRepository).save(any(UserDevice.class));
    }

    @Test
    void deviceCap_reached_nothingEvictable_skipsRegistration() {
        stubUser(2);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        when(deviceRepository.countByTelegramId(TG_ID)).thenReturn(20L);
        when(deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(TG_ID))
                .thenReturn(java.util.List.of(rowWithStatus(1, UserDevice.Status.ACTIVE, LocalDateTime.now())));

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.BLOCK);
        verify(deviceRepository, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test
    void deviceDms_rateLimited_perUser() {
        // 7 новых устройств подряд → DM только на первые 5 (лимит 5/час)
        stubUser(null);
        when(deviceRepository.findByTelegramIdAndHwid(eq(TG_ID), anyString())).thenReturn(Optional.empty());
        stubActiveCount(0);

        for (int i = 0; i < 7; i++) {
            service.onSubscriptionFetch(TOKEN, new DeviceTrackingService.DeviceHeaders(
                    "aaaaaaaa-1111-2222-3333-44444444440" + i, "iPhone15,2", "iOS", "18.3", "клиентское приложение/4.6.0"));
        }
        verify(notifications, org.mockito.Mockito.times(5))
                .sendDeviceConnected(eq(TG_ID), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------- котировка докупки «+1 устройство» ----------

    private Customer tierUser(String tierId, Integer deviceLimit, LocalDateTime expiresAt) {
        return Customer.builder()
                .telegramId(TG_ID).deviceTierId(tierId).deviceLimit(deviceLimit)
                .status(Customer.Status.ACTIVE).expiresAt(expiresAt)
                .build();
    }

    private void enableAddon() {
        appConfig.getDeviceAddon().setPriceKopeksPerMonth(3900);
        appConfig.getDeviceAddon().setPriceStarsPerMonth(47);
        AppConfig.DeviceTier d2 = new AppConfig.DeviceTier();
        d2.setId("d2");
        d2.setDevices(2);
        appConfig.setDeviceTiers(java.util.List.of(d2));
    }

    @Test
    void addonQuote_activeTierUser_pricedProRata() {
        enableAddon();
        Customer user = tierUser("d2", 2, LocalDateTime.now().plusDays(30));

        var quote = service.addonQuote(user);

        assertThat(quote).isPresent();
        assertThat(quote.get().priceKopeks()).isEqualTo(3900); // ровно месяц
        assertThat(quote.get().priceStars()).isGreaterThan(0);
        assertThat(quote.get().currentLimit()).isEqualTo(2);
    }

    @Test
    void addonQuote_unavailable_forLegacyTrialExpiredAndMaxedOut() {
        enableAddon();
        // legacy (без ступени)
        assertThat(service.addonQuote(tierUser(null, null, LocalDateTime.now().plusDays(10)))).isEmpty();
        // истёкшая подписка
        assertThat(service.addonQuote(tierUser("d2", 2, LocalDateTime.now().minusDays(1)))).isEmpty();
        // выкуплен максимум доп-слотов (база 2 + max 5 = 7)
        assertThat(service.addonQuote(tierUser("d2", 7, LocalDateTime.now().plusDays(10)))).isEmpty();
        // фича выключена
        appConfig.getDeviceAddon().setPriceKopeksPerMonth(0);
        assertThat(service.addonQuote(tierUser("d2", 2, LocalDateTime.now().plusDays(10)))).isEmpty();
    }

    // ---------- переименование + owner-алерт об аномалии ----------

    @Test
    void rename_setsAndClearsCustomName() {
        UserDevice device = existing(UserDevice.Status.ACTIVE, LocalDateTime.now());
        device.setDeviceModel("iPhone15,2");
        device.setDeviceOs("iOS");
        device.setOsVersion("18.3");
        when(deviceRepository.findById(5L)).thenReturn(Optional.of(device));

        assertThat(service.rename(TG_ID, 5L, "  телефон мамы  ")).isTrue();
        assertThat(device.getCustomName()).isEqualTo("телефон мамы");
        assertThat(DeviceTrackingService.displayName(device)).isEqualTo("телефон мамы");

        assertThat(service.rename(TG_ID, 5L, "   ")).isTrue();
        assertThat(device.getCustomName()).isNull();
        assertThat(DeviceTrackingService.displayName(device)).isEqualTo("iPhone 14 Pro (iOS 18.3)");
    }

    @Test
    void rename_foreignDevice_rejected() {
        UserDevice foreign = UserDevice.builder()
                .id(9L).telegramId(999L).hwid(HWID).status(UserDevice.Status.ACTIVE)
                .firstSeenAt(LocalDateTime.now()).lastSeenAt(LocalDateTime.now())
                .build();
        when(deviceRepository.findById(9L)).thenReturn(Optional.of(foreign));

        assertThat(service.rename(TG_ID, 9L, "чужое")).isFalse();
        assertThat(foreign.getCustomName()).isNull();
    }

    @Test
    void ownerAnomalyAlert_firesAtThreshold() {
        // 10-е устройство (порог default 10) → owner-DM; лимита у юзера нет — просто ALLOW
        stubUser(null);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        when(deviceRepository.countByTelegramId(TG_ID)).thenReturn(9L);
        stubActiveCount(9);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verify(notifications).notifyOwnerDeviceAnomaly(eq(TG_ID), any(), eq(10L), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void ownerAnomalyAlert_silentBelowThreshold() {
        stubUser(3);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        when(deviceRepository.countByTelegramId(TG_ID)).thenReturn(2L);
        stubActiveCount(1);

        service.onSubscriptionFetch(TOKEN, headers(HWID));
        verify(notifications, never()).notifyOwnerDeviceAnomaly(anyLong(), any(), anyLong(), any());
    }

    // ---------- revoke / restore ----------

    @Test
    void revoke_foreignDevice_rejected() {
        UserDevice foreign = UserDevice.builder()
                .id(9L).telegramId(999L).hwid(HWID).status(UserDevice.Status.ACTIVE)
                .firstSeenAt(LocalDateTime.now()).lastSeenAt(LocalDateTime.now())
                .build();
        when(deviceRepository.findById(9L)).thenReturn(Optional.of(foreign));

        assertThat(service.revoke(TG_ID, 9L)).isFalse();
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void revoke_ownDevice_setsRevoked() {
        UserDevice device = existing(UserDevice.Status.ACTIVE, LocalDateTime.now());
        when(deviceRepository.findById(5L)).thenReturn(Optional.of(device));

        assertThat(service.revoke(TG_ID, 5L)).isTrue();
        assertThat(device.getStatus()).isEqualTo(UserDevice.Status.REVOKED);
    }

    @Test
    void restore_withFreeSlot_activates() {
        when(userRepository.findByTelegramId(TG_ID)).thenReturn(Optional.of(user(3)));
        UserDevice device = existing(UserDevice.Status.REVOKED, LocalDateTime.now());
        when(deviceRepository.findById(5L)).thenReturn(Optional.of(device));
        stubActiveCount(1);

        assertThat(service.restore(TG_ID, 5L)).isTrue();
        assertThat(device.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
    }

    @Test
    void restore_withoutFreeSlot_parksAsBlocked() {
        when(userRepository.findByTelegramId(TG_ID)).thenReturn(Optional.of(user(1)));
        UserDevice device = existing(UserDevice.Status.REVOKED, LocalDateTime.now());
        when(deviceRepository.findById(5L)).thenReturn(Optional.of(device));
        stubActiveCount(1);

        assertThat(service.restore(TG_ID, 5L)).isFalse();
        assertThat(device.getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
    }

    // ---------- переезд чтения на таблицу подписок ----------
    //
    // Пока подписка одна, оба источника обязаны давать одинаковый вердикт. Расхождение
    // означало бы, что переезд молча изменил, кому отдавать ноды.

    /** Готовит ту же подписку в новом источнике и переключает чтение на него. */
    private void serveFromSubscriptions(Integer deviceLimit) {
        when(subscriptionRepository.findBySubscriptionToken(TOKEN)).thenReturn(Optional.of(
                com.orchestra.subscriptions.model.Subscription.builder()
                        .id(7L).telegramId(TG_ID).seq(1).current(true)
                        .subscriptionToken(TOKEN)
                        .state(com.orchestra.subscriptions.model.Customer.Status.ACTIVE)
                        .expiresAt(LocalDateTime.now().plusDays(10))
                        .deviceLimit(deviceLimit)
                        .build()));
        when(userRepository.findByTelegramId(TG_ID)).thenReturn(Optional.of(user(deviceLimit)));
        readFromSubscriptions(true);
    }

    @Test
    void fromSubscriptions_newDeviceUnderLimit_registersActive() {
        serveFromSubscriptions(3);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        stubActiveCount(1);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);

        ArgumentCaptor<UserDevice> cap = ArgumentCaptor.forClass(UserDevice.class);
        verify(deviceRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
    }

    @Test
    void fromSubscriptions_newDeviceOverLimit_blocked() {
        serveFromSubscriptions(1);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        stubActiveCount(1);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.BLOCK);
    }

    /** Legacy-юзер (лимита нет) не должен ограничиваться и после переезда чтения. */
    @Test
    void fromSubscriptions_legacyUnlimited_allows() {
        serveFromSubscriptions(null);
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.empty());
        stubActiveCount(9);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
    }

    @Test
    void fromSubscriptions_unknownToken_allows() {
        readFromSubscriptions(true);
        when(subscriptionRepository.findBySubscriptionToken(anyString())).thenReturn(Optional.empty());

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        verifyNoInteractions(deviceRepository);
    }

    // ---------- понижение лимита (даунгрейд ступени / откат после возврата) ----------

    /** Устройство с собственным id и «возрастом» последнего визита — для ранжирования слотов. */
    private UserDevice device(long id, int minutesAgo) {
        LocalDateTime seen = LocalDateTime.now().minusMinutes(minutesAgo);
        return UserDevice.builder()
                .id(id).telegramId(TG_ID).hwid("hwid-" + id).status(UserDevice.Status.ACTIVE)
                .firstSeenAt(seen).lastSeenAt(seen)
                .build();
    }

    private void stubAllDevices(UserDevice... devices) {
        when(deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(TG_ID))
                .thenReturn(java.util.List.of(devices));
    }

    @Test
    void enforceLimit_demotesSurplus_keepingMostRecentlySeen() {
        UserDevice fresh = device(1L, 5);
        UserDevice middle = device(2L, 60);
        UserDevice stalest = device(3L, 600);
        stubAllDevices(stalest, fresh, middle); // порядок из БД роли не играет

        assertThat(service.enforceLimit(TG_ID, 2)).isEqualTo(1);

        assertThat(fresh.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(middle.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(stalest.getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
        verify(notifications).sendDevicesDemoted(TG_ID, 1, 2);
    }

    @Test
    void enforceLimit_withinLimit_doesNothing() {
        UserDevice only = device(1L, 5);
        stubAllDevices(only);

        assertThat(service.enforceLimit(TG_ID, 2)).isZero();
        assertThat(only.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        verifyNoInteractions(notifications);
    }

    /** Legacy-юзер (лимита нет вовсе) не должен терять устройства ни при каких откатах. */
    @Test
    void enforceLimit_unlimited_isNoOp() {
        assertThat(service.enforceLimit(TG_ID, 0)).isZero();
        verifyNoInteractions(deviceRepository, notifications);
    }

    /** Устройства вне окна активности слот не занимают — и «лишними» не считаются. */
    @Test
    void enforceLimit_ignoresDevicesOutsideInactivityWindow() {
        UserDevice fresh = device(1L, 5);
        UserDevice longGone = device(2L, 60 * 24 * 45); // 45 дней назад, окно 30
        stubAllDevices(fresh, longGone);

        assertThat(service.enforceLimit(TG_ID, 1)).isZero();
        assertThat(longGone.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
    }

    @Test
    void enforceLimit_repositoryFailure_failsOpen() {
        when(deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(TG_ID))
                .thenThrow(new RuntimeException("db down"));
        assertThat(service.enforceLimit(TG_ID, 1)).isZero();
    }

    /**
     * Главный регресс: лимит упал уже ПОСЛЕ регистрации устройства (юзер купил меньшую
     * ступень). Известное ACTIVE-устройство, не попавшее в число свежих, теряет ноды —
     * до фикса оно проходило по ветке ACTIVE без единой проверки лимита.
     */
    @Test
    void activeDevice_afterLimitDrop_losesSlot() {
        stubUser(1);
        UserDevice stale = existing(UserDevice.Status.ACTIVE, LocalDateTime.now().minusHours(10));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(stale));
        stubActiveCount(2); // занято 2 слота при лимите 1
        stubAllDevices(device(9L, 5), stale);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.BLOCK);
        assertThat(stale.getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
    }

    /**
     * Планшет пролежал в ящике дольше окна активности — его слот освободился сам. Включённый
     * на минуту, он НЕ должен занимать слот «в притык»: иначе {@code touch} делает его самым
     * свежим, и на следующем полле из ранжирования вылетает ежедневный телефон — платящий юзер
     * теряет доступ на рабочем устройстве из-за железки, которую включили один раз.
     */
    @Test
    void deviceReturningFromIdleness_doesNotEvictDailyDrivers() {
        stubUser(2);
        UserDevice fromDrawer = existing(UserDevice.Status.ACTIVE, LocalDateTime.now().minusDays(40));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(fromDrawer));
        stubActiveCount(2); // оба слота заняты живыми устройствами (планшет в счёт не входит)

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.BLOCK);
        assertThat(fromDrawer.getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
        // Ранжировать не понадобилось — решение принято по счётчику.
        verify(deviceRepository, never()).findAllByTelegramIdOrderByFirstSeenAtAsc(anyLong());
    }

    /** Тот же вернувшийся девайс, но слот реально свободен — пускаем, как и новое устройство. */
    @Test
    void deviceReturningFromIdleness_takesGenuinelyFreeSlot() {
        stubUser(2);
        UserDevice fromDrawer = existing(UserDevice.Status.ACTIVE, LocalDateTime.now().minusDays(40));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(fromDrawer));
        stubActiveCount(1); // один слот свободен

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        assertThat(fromDrawer.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
    }

    /** Обратная сторона: устройство В пределах лимита обычный полл не трогает. */
    @Test
    void activeDevice_withinLimit_staysAllowed() {
        stubUser(2);
        UserDevice device = existing(UserDevice.Status.ACTIVE, LocalDateTime.now().minusHours(10));
        when(deviceRepository.findByTelegramIdAndHwid(TG_ID, HWID)).thenReturn(Optional.of(device));
        stubActiveCount(2);

        assertThat(service.onSubscriptionFetch(TOKEN, headers(HWID)))
                .isEqualTo(DeviceTrackingService.Verdict.ALLOW);
        assertThat(device.getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        // Лимит не превышен — ранжировать слоты не понадобилось.
        verify(deviceRepository, never()).findAllByTelegramIdOrderByFirstSeenAtAsc(anyLong());
    }
}
