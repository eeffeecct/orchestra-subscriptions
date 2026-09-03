package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
class AccessServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AccessService accessService;

    @Autowired
    CustomerRepository userRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    com.orchestra.subscriptions.repository.UserDeviceRepository userDeviceRepository;

    @MockitoBean
    AccessProvider accessProvider;

    // Мокаем YooKassaClient чтобы его @PostConstruct не пытался соединяться с реальным API
    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;


    // Бот не регистрируется в тестах: см. spring.autoconfigure.exclude в test application.yml

    // NotificationService инжектится в AccessService (через @Lazy) для алертов владельцу
    // о AccessProvider sync failure. Мокаем, чтобы не тянуть реальный SubscriptionBot в контекст.
    @MockitoBean
    NotificationService notificationService;

    private static final Long TELEGRAM_ID = 123456789L;
    private static final String TELEGRAM_USERNAME = "testuser";

    @BeforeEach
    void setUp() {
        userDeviceRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mockUser = new AccessProvider.AccessAccount();
        mockUser.setSubscriptionUrl("https://example.com/sub/test");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mockUser);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(Instant.class), any());
        doNothing().when(accessProvider).disableUser(anyString());
    }

    /**
     * Матчер момента истечения, отданного AccessProvider: попадает ли он в окно [now+min, now+max] суток.
     * Проверяем окном, а не точным равенством — между вызовом и проверкой проходит время.
     */
    private static Instant expiryWithinDays(double minDays, double maxDays) {
        return argThat(actual -> {
            if (actual == null) return false;
            double days = Duration.between(Instant.now(), actual).toMinutes() / 1440.0;
            return days >= minDays && days <= maxDays;
        });
    }

    @Test
    void rotateSubscriptionLink_updatesLinkTokenAndClearsDevices() {
        // Юзер с подпиской + одно замеченное устройство
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        userDeviceRepository.save(com.orchestra.subscriptions.model.UserDevice.builder()
                .telegramId(TELEGRAM_ID)
                .hwid("aaaaaaaa-1111-2222-3333-444444444444")
                .status(com.orchestra.subscriptions.model.UserDevice.Status.ACTIVE)
                .build());

        AccessProvider.AccessAccount rotated = new AccessProvider.AccessAccount();
        rotated.setSubscriptionUrl("https://example.com/sub/NEWTOKEN");
        when(accessProvider.revokeSubscription("tg_testuser_123456789")).thenReturn(rotated);

        Customer user = accessService.rotateSubscriptionLink(TELEGRAM_ID);

        assertThat(user.getSubscriptionLink()).isEqualTo("https://example.com/sub/NEWTOKEN");
        // Токен пересчитан JPA-хуком — лукап детекта устройств сразу видит новый
        Customer fromDb = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(fromDb.getSubscriptionToken()).isEqualTo("NEWTOKEN");
        // Старые устройства очищены — с новой ссылкой перерегистрируются сами
        assertThat(userDeviceRepository.countByTelegramId(TELEGRAM_ID)).isZero();
    }

    @Test
    void rotateSubscriptionLink_unknownUser_throws() {
        assertThatThrownBy(() -> accessService.rotateSubscriptionLink(999999L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createTrialAccess_shouldCreateUser() {
        Customer user = accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME);

        assertThat(user.getTelegramId()).isEqualTo(TELEGRAM_ID);
        assertThat(user.getProviderUsername()).isEqualTo("trial_testuser_123456789");
        assertThat(user.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(user.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(user.getSubscriptionLink()).isEqualTo("https://example.com/sub/test");
    }

    @Test
    void createTrialAccess_shouldFallbackToIdWhenNoUsername() {
        Customer user = accessService.createTrialAccess(TELEGRAM_ID, null);

        assertThat(user.getProviderUsername()).isEqualTo("trial_" + TELEGRAM_ID);
    }

    @Test
    void createTrialAccess_shouldThrowIfAlreadyExists() {
        accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME);

        assertThatThrownBy(() -> accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Trial already used");
    }

    @Test
    void activatePaidSubscription_shouldCreateNewPaidUser() {
        Customer user = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        assertThat(user.getProviderUsername()).isEqualTo("tg_testuser_123456789");
        assertThat(user.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29),
                LocalDateTime.now().plusDays(31));
        verify(accessProvider).createUser("tg_testuser_123456789", 30, 0);
    }

    @Test
    void activatePaidSubscription_shouldUpgradeTrialAndReplaceExpiry() {
        accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME);

        Customer user = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        // Триал замещается: expiry ≈ now + 30 дней, а не now + 7d + 30d
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29),
                LocalDateTime.now().plusDays(31));
        assertThat(user.getProviderUsername()).isEqualTo("trial_testuser_123456789"); // имя в AccessProvider остаётся тем же
        verify(accessProvider).enableUserUntil(
                eq("trial_testuser_123456789"), expiryWithinDays(29, 31), isNull());
    }

    @Test
    void activatePaidSubscription_paidUserWithTrialPrefixShouldStackNearExpiry() {
        // Регрессия: после апгрейда trial→paid provider_username остаётся trial_*, а expires_at
        // уезжает на N дней вперёд. В последние ~8 дней такого «бывшего триала» старая
        // эвристика (hasTrialPrefix + remaining <= 8d) ложно срабатывала → дни «поглощались»
        // вместо стэка. Кусало автопродление (за 24h до истечения), реф-бонусы,
        // admin /extend, ручную покупку.
        // Фикс: триал детектится не по префиксу, а по отсутствию SUCCEEDED/REFUNDED-оплат.
        accessService.createTrialAccess(TELEGRAM_ID, TELEGRAM_USERNAME);
        // Симулируем: уже была успешная оплата (юзер давно платник, имя осталось trial_*)
        Payment paid = Payment.builder()
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .planId("month_1")
                .amountKopeks(15000L)
                .provider(Payment.Provider.YOOKASSA)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(25))
                .build();
        paymentRepository.save(paid);
        // У него осталось 5 дней — то самое «опасное» окно, где старый код терял остаток
        Customer u = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        u.setExpiresAt(LocalDateTime.now().plusDays(5));
        userRepository.save(u);

        Customer user = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        // Ожидаем ~35 дней (5 остатка + 30 новых), а НЕ 30 (как было до фикса)
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(34),
                LocalDateTime.now().plusDays(36));
        assertThat(user.getProviderUsername()).isEqualTo("trial_testuser_123456789");
    }

    @Test
    void activatePaidSubscription_shouldStackDaysOnActivePaidRenewal() {
        // Первый платёж: 30 дней
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        // Продление: ещё 30 дней поверх остатка — итого ≈ 60 дней
        Customer user = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(59),
                LocalDateTime.now().plusDays(61));
        verify(accessProvider).enableUserUntil(
                eq("tg_testuser_123456789"), expiryWithinDays(59, 61), isNull());
    }

    @Test
    void activatePaidSubscription_shouldSendExactExpiryToProviderNotWholeDays() {
        // Регрессия «в Mini App осталось 5 часов, а в клиенте подписка уже истекла»:
        // в AccessProvider уходило целое число суток от сейчас (floor от Duration), т.е. до 23:59
        // МЕНЬШЕ, чем в expires_at. Хватает любого остатка с дробной частью суток.
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        Customer withOddRemainder = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        withOddRemainder.setExpiresAt(LocalDateTime.now().plusHours(5));
        userRepository.save(withOddRemainder);
        clearInvocations(accessProvider);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());

        // Продление: 5 часов остатка + 30 суток. Старый код отдавал AccessProvider «30 суток»
        // и терял те самые 5 часов — панель гасила юзера раньше бота и Mini App.
        Customer after = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        long dbEpochSecond = after.getExpiresAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        verify(accessProvider).enableUserUntil(
                eq("tg_testuser_123456789"),
                argThat(sent -> sent != null && sent.getEpochSecond() == dbEpochSecond),
                isNull());
    }

    @Test
    void activatePaidSubscription_shouldStartFreshWhenDisabled() {
        // Когда юзер в статусе DISABLED — следующая оплата стартует с now,
        // даже если в БД expires_at в будущем. Это нужно для refund-кейса:
        // applyRefundEffects → disableAccess сбрасывает status, но не trim'ит
        // expires_at в БД и не сбрасывает AccessProvider-side expire. Без этой логики
        // refund + быстрая повторная оплата удваивала бы срок (см. regression
        // test ниже activatePaidSubscription_refundThenRebuyShouldNotInheritOldExpiry).
        // Бонус: то же поведение для admin-disable — даём ровно days с момента
        // оплаты, не накручиваем за заблокированный период.
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        Customer beforeDisable = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        beforeDisable.setStatus(Customer.Status.DISABLED);
        userRepository.save(beforeDisable);

        Customer user = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        assertThat(user.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        // Ожидаем: ровно 30 дней от now, без наследования прошлого expires_at
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29),
                LocalDateTime.now().plusDays(31));
        verify(accessProvider, atLeastOnce()).enableUserUntil(
                eq("tg_testuser_123456789"), expiryWithinDays(29, 31), isNull());
    }

    @Test
    void activatePaidSubscription_refundThenRebuyShouldNotInheritOldExpiry() {
        // Регрессионный тест: симулируем последовательность refund → быстрая повторная оплата.
        // 1. Юзер платит 30 дней → expires_at ≈ now+30, status=ACTIVE.
        // 2. Refund → disableAccess: status=DISABLED, но expires_at в БД остаётся ≈ now+30
        //    (старая ошибка: applyRefundEffects.past-branch не trim'ит этот expires_at,
        //     AccessProvider-side expire тоже не сбрасывается).
        // 3. Юзер сразу платит ещё раз 30 дней. Должен получить ровно 30, а не 60.
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        LocalDateTime expiryAfterFirstPay = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow().getExpiresAt();
        assertThat(expiryAfterFirstPay).isAfter(LocalDateTime.now().plusDays(29));

        // Симулируем refund → disableAccess: status=DISABLED, expires_at оставляем как было
        Customer u = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        u.setStatus(Customer.Status.DISABLED);
        userRepository.save(u);

        // AccessProvider отдаёт старый expire (refund не сбрасывает его на стороне AccessProvider)
        AccessProvider.AccessAccount stale = new AccessProvider.AccessAccount();
        stale.setExpire(expiryAfterFirstPay.atZone(java.time.ZoneId.systemDefault()).toEpochSecond());
        when(accessProvider.getUser("tg_testuser_123456789")).thenReturn(Optional.of(stale));

        // Повторная оплата
        Customer afterRebuy = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        assertThat(afterRebuy.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        // Должен быть ≈ now+30, а НЕ now+60 (как было до фикса)
        assertThat(afterRebuy.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(29),
                LocalDateTime.now().plusDays(31));
    }

    @Test
    void activatePaidSubscription_shouldStackOnProviderExpiryWhenDbIsOutOfSync() {
        // Регрессионный тест на Tribute-миграцию:
        // в БД есть старая запись с expires_at = null (или в прошлом), но в AccessProvider у пользователя
        // ещё есть ~28 дней. При покупке нового тарифа бот должен уважать AccessProvider как источник правды.
        Customer legacyRow = Customer.builder()
                .telegramId(TELEGRAM_ID)
                .telegramUsername(TELEGRAM_USERNAME)
                .providerUsername("tg_testuser_123456789")
                .subscriptionLink("https://example.com/sub/test")
                .status(Customer.Status.ACTIVE)
                .expiresAt(null) // Tribute-эра: колонка не заполнялась
                .build();
        userRepository.save(legacyRow);

        // AccessProvider говорит: у юзера ещё ~28 дней
        AccessProvider.AccessAccount accessAccount = new AccessProvider.AccessAccount();
        accessAccount.setExpire(java.time.Instant.now().plus(28, java.time.temporal.ChronoUnit.DAYS).getEpochSecond());
        when(accessProvider.getUser("tg_testuser_123456789")).thenReturn(Optional.of(accessAccount));

        Customer user = accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        // Ожидаем: 28 оставшихся + 30 новых = ~58 дней
        assertThat(user.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(57),
                LocalDateTime.now().plusDays(59));
        verify(accessProvider).enableUserUntil(
                eq("tg_testuser_123456789"), expiryWithinDays(57, 59), isNull());
    }

    @Test
    void disableAccess_shouldDisableUser() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        accessService.disableAccess(TELEGRAM_ID);

        Optional<Customer> user = userRepository.findByTelegramId(TELEGRAM_ID);
        assertThat(user).isPresent();
        assertThat(user.get().getStatus()).isEqualTo(Customer.Status.DISABLED);
        verify(accessProvider).disableUser("tg_testuser_123456789");
    }

    @Test
    void disableAccess_shouldDoNothingIfUserNotFound() {
        accessService.disableAccess(999999L);

        verifyNoInteractions(accessProvider);
    }

    @Test
    void getUser_shouldReturnUser() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        Optional<Customer> user = accessService.getUser(TELEGRAM_ID);

        assertThat(user).isPresent();
        assertThat(user.get().getTelegramId()).isEqualTo(TELEGRAM_ID);
    }

    @Test
    void getSubscriptionLink_shouldReturnLink() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);

        String link = accessService.getSubscriptionLink(TELEGRAM_ID).orElse(null);

        assertThat(link).isEqualTo("https://example.com/sub/test");
    }

    @Test
    void getSubscriptionLink_shouldReturnNullIfUserNotFound() {
        String link = accessService.getSubscriptionLink(999999L).orElse(null);

        assertThat(link).isNull();
    }

    // ========================
    // Owner alerts on AccessProvider sync failure
    // ========================

    @Test
    void activatePaidSubscription_whenCreateUserFailsShouldAlertOwnerAndRethrow() {
        when(accessProvider.createUser(anyString(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("AccessProvider panel down"));

        assertThatThrownBy(() -> accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30))
                .hasMessageContaining("AccessProvider panel down");

        verify(notificationService).notifyOwnerAccessSyncFailure(
                eq("activatePaidSubscription/createUser"), eq(TELEGRAM_ID), anyString(), anyString());
    }

    @Test
    void activatePaidSubscription_whenEnableUserFailsShouldAlertOwnerAndRethrow() {
        // Юзер уже существует (платный), чтобы шёл path enableUser, а не createUser.
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        reset(accessProvider);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("AccessProvider enable failed"))
                .when(accessProvider).enableUserUntil(anyString(), any(Instant.class), any());

        assertThatThrownBy(() -> accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30))
                .hasMessageContaining("AccessProvider enable failed");

        verify(notificationService).notifyOwnerAccessSyncFailure(
                eq("activatePaidSubscription/enableUser"), eq(TELEGRAM_ID), anyString(), anyString());
    }

    @Test
    void setExpiry_whenProviderFailsShouldAlertOwnerButKeepDbChange() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        doThrow(new RuntimeException("AccessProvider unreachable"))
                .when(accessProvider).enableUserUntil(anyString(), any(Instant.class), any());

        LocalDateTime newExpiry = LocalDateTime.now().plusDays(10);
        accessService.setExpiry(TELEGRAM_ID, newExpiry);

        // DB всё равно обновилась — политика setExpiry: DB source of truth, AccessProvider eventually consistent.
        // (isBetween вместо isEqualTo — Postgres TIMESTAMP truncates наносекунды, см. другие тесты.)
        Customer after = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(after.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(9), LocalDateTime.now().plusDays(11));
        // Owner получил алерт — иначе расхождение осталось бы скрытым.
        verify(notificationService).notifyOwnerAccessSyncFailure(
                eq("setExpiry"), eq(TELEGRAM_ID), anyString(), anyString());
    }

    @Test
    void disableAccess_whenProviderFailsShouldAlertOwner() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        doThrow(new RuntimeException("AccessProvider disable failed"))
                .when(accessProvider).disableUser(anyString());

        accessService.disableAccess(TELEGRAM_ID);

        verify(notificationService).notifyOwnerAccessSyncFailure(
                eq("disableAccess"), eq(TELEGRAM_ID), anyString(), anyString());
    }

    // ========================
    // ip_limit при продлении без явной ступени (регрессия)
    // ========================

    /**
     * Продление БЕЗ ступени (реф-бонус, грант, админский /extend) не должно сбрасывать
     * per-user {@code ip_limit} семейного тарифа.
     * <p>
     * Раньше сюда уходил {@code null}, а {@code null} в {@link AccessProvider#enableUserUntil}
     * означает «глобальный app.provider.ip-limit» (5). Юзер с семейной подпиской на 10
     * устройств (ip_limit 11) после бонуса за друга молча начинал упираться в 5
     * одновременных подключений, причём {@code device_limit} в БД оставался правильным —
     * то есть расхождение никак себя не проявляло, кроме жалоб «шестое устройство не
     * подключается».
     */
    @Test
    void activatePaidSubscription_withoutTierShouldKeepFamilyIpLimit() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        Customer user = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        user.setDeviceLimit(10); // семейная ступень
        userRepository.save(user);
        clearInvocations(accessProvider);

        // Реф-бонус / грант / /extend — ступень не передаётся.
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 14);

        // Конвенция ip_limit = devices + 1, а не глобальные 5.
        verify(accessProvider).enableUserUntil(anyString(), any(Instant.class), eq(11));
    }

    /** Тот же сброс происходил и при урезании срока после возврата — там был жёсткий null. */
    @Test
    void setExpiry_shouldKeepFamilyIpLimit() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        Customer user = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        user.setDeviceLimit(5);
        userRepository.save(user);
        clearInvocations(accessProvider);

        accessService.setExpiry(TELEGRAM_ID, LocalDateTime.now().plusDays(10));

        verify(accessProvider).enableUserUntil(anyString(), any(Instant.class), eq(6));
    }

    /**
     * Legacy-юзер без {@code device_limit} по-прежнему получает глобальный лимит: поведение
     * «старые юзеры подтягивают ip_limit при первом продлении» было задумано и сохраняется.
     */
    @Test
    void activatePaidSubscription_legacyUserWithoutDeviceLimitStillGetsGlobalIpLimit() {
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        clearInvocations(accessProvider);

        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 14);

        verify(accessProvider).enableUserUntil(anyString(), any(Instant.class), isNull());
    }

    @Test
    void disableAccess_whenProviderFailsShouldStillPersistDisabledStatusInDb() {
        // Регрессия на порядок операций: раньше AccessProvider-вызов был ПЕРЕД save(), и при
        // exception save() не выполнялся → БД оставалась ACTIVE при отключённом туннеле.
        // /status в боте показывал «Активна», а юзер не мог подключиться. Теперь
        // фиксируем DISABLED в БД до AccessProvider-вызова, в одной @Transactional границе.
        accessService.activatePaidSubscription(TELEGRAM_ID, TELEGRAM_USERNAME, 30);
        doThrow(new RuntimeException("AccessProvider disable failed"))
                .when(accessProvider).disableUser(anyString());

        accessService.disableAccess(TELEGRAM_ID);

        Customer user = userRepository.findByTelegramId(TELEGRAM_ID).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(Customer.Status.DISABLED);
    }
}
