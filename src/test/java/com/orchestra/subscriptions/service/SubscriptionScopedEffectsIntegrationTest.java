package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Всё, что раньше считалось «по человеку», а теперь обязано считаться по подписке: возврат
 * денег, откат ступени устройств, смена ссылки, учёт устройств.
 * <p>
 * Общее свойство, ради которого написан весь набор: <b>действие с одной подпиской не задевает
 * другую</b>. До этого шага возврат за спящую подписку урезал бы срок работающей, откат ступени
 * переставил бы её лимит устройств, а смена ссылки выкинула бы из слотов телефон, которым
 * человек прямо сейчас пользуется. Каждое из трёх выглядит для человека как поломка сервиса,
 * а не как последствие его действия.
 * <p>
 * Флаг мультиподписки здесь НЕ нужен: эти пути ветвятся по строке подписки, а не по флагу.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        // Реконсилер выключен: он досылал бы намерения параллельно с проверками панели.
        "app.subscriptions.sync-enabled=false",
        // Как в проде с 12.08.2026: подписка читается из нового источника, и учёт устройств
        // получает id подписки. С выключенным флагом привязка устройств не проверялась бы вовсе.
        "app.subscriptions.read-model-enabled=true",
        "app.plans[0].id=month_1",
        "app.plans[0].name=1 месяц",
        "app.plans[0].days=30",
        "app.plans[0].price-kopeks=15000",
        "app.plans[0].description=Orchestra — 1 месяц",
        "app.device-tiers[0].id=d1",
        "app.device-tiers[0].name=1 устройство",
        "app.device-tiers[0].devices=1",
        "app.device-tiers[0].price-multiplier=0.6",
        "app.device-tiers[1].id=d2",
        "app.device-tiers[1].name=2 устройства",
        "app.device-tiers[1].devices=2",
        "app.device-tiers[1].price-multiplier=1.0",
})
class SubscriptionScopedEffectsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PaymentProcessingService paymentProcessingService;
    @Autowired
    AccessService accessService;
    @Autowired
    DeviceTrackingService deviceTrackingService;
    @Autowired
    com.orchestra.subscriptions.config.AppConfig appConfig;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    UserDeviceRepository deviceRepository;
    @Autowired
    CustomerRepository userRepository;

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    YooKassaClient yooKassaClient;
    @MockitoBean
    CryptoBotClient cryptoBotClient;
    @MockitoBean
    NotificationService notificationService;

    private static final long TG_ID = 909010203L;
    private static final String YK_ID = "yk-scoped";
    private static final String PARKED_USERNAME = "tg_scoped_" + TG_ID + "_s2";

    @BeforeEach
    void setUp() {
        deviceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount account = new AccessProvider.AccessAccount();
        account.setUsername("tg_scoped_" + TG_ID);
        account.setSubscriptionUrl("https://test.example.com/sub/currenttoken");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(account);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any())).thenReturn(account);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
        when(accessProvider.revokeSubscription(anyString()))
                .thenAnswer(inv -> rotated(inv.getArgument(0)));
    }

    /** Санити: ступени реально доехали — без них откат ступени не выполняется вовсе. */
    @Test
    @DisplayName("Контекст: ступени устройств видны")
    void tiersAreConfiguredForThisContext() {
        assertThat(appConfig.findDeviceTierOptional("d1")).isPresent();
        assertThat(appConfig.findDeviceTierOptional("d2")).isPresent();
    }

    // =====================================================================
    //  Возврат
    // =====================================================================

    @Test
    @DisplayName("Возврат по замороженной подписке списывает её остаток и не трогает работающую")
    void refundHitsItsOwnSubscription() {
        Subscription current = givenCurrentSubscription();
        LocalDateTime currentExpiry = current.getExpiresAt();
        Subscription parked = givenParkedSubscription(60, "d2", 2);

        refundPaymentTargeting(parked, "d2");

        Subscription parkedAfter = subscriptionRepository.findById(parked.getId()).orElseThrow();
        assertThat(parkedAfter.getBalanceSeconds())
                .as("списали ровно оплаченные 30 дней")
                .isEqualTo(SubscriptionTime.daysToSeconds(30));

        Subscription currentAfter = subscriptionRepository.findById(current.getId()).orElseThrow();
        assertThat(currentAfter.getExpiresAt()).isEqualTo(currentExpiry);
        assertThat(currentAfter.getState()).isEqualTo(Customer.Status.ACTIVE);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(user.getStatus()).as("человек не остался без доступа").isEqualTo(Customer.Status.ACTIVE);
        assertThat(user.getExpiresAt()).isEqualTo(currentExpiry);

        // Панель не трогаем: аккаунт замороженной и так выключен, а работающую отключать не за что.
        verify(accessProvider, never()).disableUser(anyString());
        verify(notificationService).sendRefundFromParked(eq(TG_ID), any(), any(), anyLong());
        verify(notificationService, never()).sendRefundDisabled(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("Повторный refund-вебхук списывает остаток один раз")
    void refundIsIdempotentPerSubscription() {
        givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(60, "d2", 2);

        Payment payment = refundPaymentTargeting(parked, "d2");
        paymentProcessingService.handleRefundSucceeded(refundBody(payment));

        assertThat(subscriptionRepository.findById(parked.getId()).orElseThrow().getBalanceSeconds())
                .isEqualTo(SubscriptionTime.daysToSeconds(30));
    }

    @Test
    @DisplayName("Откат ступени после возврата идёт по своей подписке")
    void tierRollbackStaysWithinSubscription() {
        Subscription current = givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(60, "d2", 2);

        refundPaymentTargeting(parked, "d2");

        Subscription parkedAfter = subscriptionRepository.findById(parked.getId()).orElseThrow();
        assertThat(parkedAfter.getDeviceTierId())
                .as("других оплат у этой подписки нет — ступень снимается").isNull();
        assertThat(parkedAfter.getDeviceLimit()).isNull();

        Subscription currentAfter = subscriptionRepository.findById(current.getId()).orElseThrow();
        assertThat(currentAfter.getDeviceTierId()).isEqualTo("d2");
        assertThat(currentAfter.getDeviceLimit()).isEqualTo(2);
        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getDeviceLimit())
                .as("лимит человека описывает работающую подписку").isEqualTo(2);
    }

    /** Остаток не уходит в минус: вернули больше, чем лежало — это разбор владельца. */
    @Test
    @DisplayName("Возврат больше остатка обнуляет баланс, а не уводит его в минус")
    void refundClampsBalanceAtZero() {
        givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(10, "d2", 2);

        refundPaymentTargeting(parked, "d2");

        assertThat(subscriptionRepository.findById(parked.getId()).orElseThrow().getBalanceSeconds())
                .isZero();
    }

    /**
     * Регресс-щит. Колонка {@code payments.subscription_id} появилась только на этапе 5, и все
     * платежи старше неё живут с {@code NULL}. Если фильтр «та же подписка» отбросит их,
     * возврат за вторую оплату решит, что оплат со ступенью не осталось, и снимет лимит
     * устройств совсем — у человека, который за него заплатил.
     */
    @Test
    @DisplayName("Откат ступени видит исторические платежи без привязки к подписке")
    void tierRollbackSeesLegacyPaymentsWithoutTarget() {
        Subscription current = givenCurrentSubscription();
        // Оплата «до этапа 5»: цели на строке нет.
        paymentRepository.save(Payment.builder()
                .telegramId(TG_ID)
                .telegramUsername("scoped")
                .provider(Payment.Provider.YOOKASSA)
                .yookassaPaymentId("yk-legacy")
                .planId("month_1")
                .deviceTierId("d2")
                .amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(20))
                .build());

        refundPaymentTargeting(current, "d2");

        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(user.getDeviceTierId())
                .as("ступень откатывается на прошлую оплату, а не обнуляется").isEqualTo("d2");
        assertThat(user.getDeviceLimit()).isEqualTo(2);
    }

    /**
     * Ядро шага: понижение лимита у замороженной подписки снимает со слотов ЕЁ устройства и
     * не трогает устройства работающей.
     */
    @Test
    @DisplayName("Понижение лимита замороженной подписки снимает со слотов только её устройства")
    void limitEnforcementStaysWithinSubscription() {
        Subscription current = givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(60, "d2", 2);
        UserDevice currentDevice = givenDevice(current, "hwid-current-07");
        UserDevice parkedOld = givenDevice(parked, "hwid-parked-07");
        UserDevice parkedFresh = givenDevice(parked, "hwid-parked-08");
        parkedOld.setLastSeenAt(LocalDateTime.now().minusDays(5));
        deviceRepository.save(parkedOld);

        // Прошлая оплата этой же подписки — на ступень поменьше: после возврата лимит станет 1.
        paymentRepository.save(Payment.builder()
                .telegramId(TG_ID)
                .telegramUsername("scoped")
                .provider(Payment.Provider.YOOKASSA)
                .yookassaPaymentId("yk-parked-older")
                .planId("month_1")
                .deviceTierId("d1")
                .amountKopeks(9000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(15))
                .subscriptionId(parked.getId())
                .targetKind("EXTEND")
                .build());

        refundPaymentTargeting(parked, "d2");

        assertThat(subscriptionRepository.findById(parked.getId()).orElseThrow().getDeviceLimit())
                .as("лимит откатился на прошлую ступень").isEqualTo(1);
        assertThat(deviceRepository.findById(parkedOld.getId()).orElseThrow().getStatus())
                .as("самое давно молчащее устройство теряет слот первым")
                .isEqualTo(UserDevice.Status.BLOCKED);
        assertThat(deviceRepository.findById(parkedFresh.getId()).orElseThrow().getStatus())
                .isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(deviceRepository.findById(currentDevice.getId()).orElseThrow().getStatus())
                .as("устройство работающей подписки не тронуто")
                .isEqualTo(UserDevice.Status.ACTIVE);
    }

    // =====================================================================
    //  Смена ссылки
    // =====================================================================

    @Test
    @DisplayName("Смена ссылки чистит устройства только своей подписки")
    void rotateLinkIsLocalToSubscription() {
        Subscription current = givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(60, "d2", 2);
        UserDevice currentDevice = givenDevice(current, "hwid-current-01");
        UserDevice parkedDevice = givenDevice(parked, "hwid-parked-01");

        accessService.rotateSubscriptionLink(TG_ID);

        assertThat(deviceRepository.findById(currentDevice.getId()))
                .as("устройства подписки, чью ссылку сменили, стираются").isEmpty();
        assertThat(deviceRepository.findById(parkedDevice.getId()))
                .as("устройства другой подписки живы").isPresent();
        verify(accessProvider).revokeSubscription(current.getProviderUsername());
        verify(accessProvider, never()).revokeSubscription(PARKED_USERNAME);

        assertThat(subscriptionRepository.findById(parked.getId()).orElseThrow().getSubscriptionLink())
                .as("ссылка другой подписки не изменилась")
                .isEqualTo("https://test.example.com/sub/parkedtoken");
    }

    @Test
    @DisplayName("Смена ссылки замороженной подписки не трогает работающую")
    void rotateLinkOfParkedKeepsCurrentIntact() {
        Subscription current = givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(60, "d2", 2);
        UserDevice currentDevice = givenDevice(current, "hwid-current-02");
        String currentLink = userRepository.findByTelegramId(TG_ID).orElseThrow().getSubscriptionLink();

        accessService.rotateSubscriptionLink(TG_ID, parked.getId());

        assertThat(deviceRepository.findById(currentDevice.getId()))
                .as("работающая подписка своих устройств не теряет").isPresent();
        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getSubscriptionLink())
                .as("ссылка работающей подписки не менялась").isEqualTo(currentLink);
        Subscription parkedAfter = subscriptionRepository.findById(parked.getId()).orElseThrow();
        assertThat(parkedAfter.getSubscriptionLink()).contains("rotated");
        assertThat(parkedAfter.getSubscriptionToken()).isNotEqualTo("parkedtoken");
        verify(accessProvider).revokeSubscription(PARKED_USERNAME);
        verify(accessProvider, never()).revokeSubscription(current.getProviderUsername());
    }

    // =====================================================================
    //  Устройства
    // =====================================================================

    @Test
    @DisplayName("Слоты и список устройств считаются в разрезе подписки")
    void deviceCountsAreScopedToSubscription() {
        Subscription current = givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(60, "d1", 1);
        givenDevice(current, "hwid-current-03");
        givenDevice(parked, "hwid-parked-03");
        givenDevice(parked, "hwid-parked-04");

        assertThat(deviceTrackingService.usedSlots(SubscriptionView.of(current, null))).isEqualTo(1);
        assertThat(deviceTrackingService.usedSlots(SubscriptionView.of(parked, null))).isEqualTo(2);
        assertThat(deviceTrackingService.listDevices(SubscriptionView.of(parked, null)))
                .extracting(UserDevice::getHwid)
                .containsExactlyInAnyOrder("hwid-parked-03", "hwid-parked-04");
        // По человеку — весь парк: эта версия остаётся для админ-карточки.
        assertThat(deviceTrackingService.listDevices(TG_ID)).hasSize(3);
    }

    /**
     * Строка устройства без {@code subscription_id} невидима для подсчёта слотов подписки —
     * человек получил бы больше устройств, чем оплатил, а на экране бота при этом было бы
     * «занято 3 из 2». Разовый бэкфилл эту дыру не закрывает (он отрабатывает только у тех,
     * у кого зеркальной строки ещё не было), поэтому привязка чинится на лету.
     */
    @Test
    @DisplayName("Устройство без привязки получает подписку при первом же появлении")
    void unlinkedDeviceIsAdoptedBySubscription() {
        Subscription current = givenCurrentSubscription();
        UserDevice orphan = deviceRepository.save(UserDevice.builder()
                .telegramId(TG_ID)
                .subscriptionId(null)
                .hwid("hwid-orphan-0001")
                .status(UserDevice.Status.ACTIVE)
                .firstSeenAt(LocalDateTime.now().minusDays(2))
                .lastSeenAt(LocalDateTime.now().minusDays(2))
                .build());
        assertThat(deviceTrackingService.usedSlots(SubscriptionView.of(current, null)))
                .as("до привязки строка не видна подписке").isZero();

        deviceTrackingService.onSubscriptionFetch(
                currentToken(),
                new DeviceTrackingService.DeviceHeaders(
                        "hwid-orphan-0001", "iPhone15,2", "iOS", "18.3", "клиентское приложение/4.6.0"));

        assertThat(deviceRepository.findById(orphan.getId()).orElseThrow().getSubscriptionId())
                .isEqualTo(current.getId());
        assertThat(deviceTrackingService.usedSlots(SubscriptionView.of(current, null))).isEqualTo(1);
    }

    @Test
    @DisplayName("Предпросмотр отключения называет устройства, которые потеряют слот")
    void previewDemotionNamesDevicesThatLoseSlot() {
        givenCurrentSubscription();
        Subscription parked = givenParkedSubscription(60, "d1", 1);
        givenDevice(parked, "hwid-parked-05");
        UserDevice younger = givenDevice(parked, "hwid-parked-06");
        // Самое давно молчащее теряет слот первым — делаем таким второе устройство.
        younger.setLastSeenAt(LocalDateTime.now().minusDays(3));
        deviceRepository.save(younger);

        List<UserDevice> demoted = deviceTrackingService.previewDemotion(parked);

        assertThat(demoted).extracting(UserDevice::getHwid).containsExactly("hwid-parked-06");
    }

    // =====================================================================
    //  Триал
    // =====================================================================

    @Test
    @DisplayName("Выдача триала проставляет отметку «триал израсходован»")
    void trialStampIsWrittenOnCreate() {
        long freshId = TG_ID + 1;
        accessService.createTrialAccess(freshId, "freshuser");

        assertThat(userRepository.findByTelegramId(freshId).orElseThrow().getTrialUsedAt())
                .as("на этой отметке стоит гейт триала — без неё бесплатная неделя вернулась бы "
                        + "к каждому, у кого нет активной подписки")
                .isNotNull();
    }

    // ===== helpers =====

    private Subscription givenCurrentSubscription() {
        accessService.activatePaidSubscription(TG_ID, "scoped", 20,
                appConfig.findDeviceTierOptional("d2").orElseThrow());
        return subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
    }

    private Subscription givenParkedSubscription(int frozenDays, String tierId, int deviceLimit) {
        return subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID)
                .seq(2)
                .current(false)
                .providerUsername(PARKED_USERNAME)
                .subscriptionLink("https://test.example.com/sub/parkedtoken")
                .subscriptionToken("parkedtoken")
                .state(Customer.Status.ACTIVE)
                .expiresAt(null)
                .balanceSeconds(SubscriptionTime.daysToSeconds(frozenDays))
                .deviceTierId(tierId)
                .deviceLimit(deviceLimit)
                .origin(Subscription.Origin.PAID)
                .providerAttempts(0)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** Токен работающей подписки — по нему клиент забирает конфиг. */
    private String currentToken() {
        return subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow()
                .getSubscriptionToken();
    }

    private UserDevice givenDevice(Subscription sub, String hwid) {
        return deviceRepository.save(UserDevice.builder()
                .telegramId(TG_ID)
                .subscriptionId(sub.getId())
                .hwid(hwid)
                .status(UserDevice.Status.ACTIVE)
                .firstSeenAt(LocalDateTime.now().minusDays(1))
                .lastSeenAt(LocalDateTime.now())
                .build());
    }

    /** SUCCEEDED-платёж, нацеленный в подписку, и пришедший по нему возврат. */
    private Payment refundPaymentTargeting(Subscription target, String tierId) {
        Payment payment = paymentRepository.save(Payment.builder()
                .telegramId(TG_ID)
                .telegramUsername("scoped")
                .provider(Payment.Provider.YOOKASSA)
                .yookassaPaymentId(YK_ID)
                .planId("month_1")
                .deviceTierId(tierId)
                .amountKopeks(15000L)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now())
                .subscriptionId(target.getId())
                .targetKind("EXTEND")
                .build());

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref-scoped");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_ID);
        when(yooKassaClient.getRefund("ref-scoped")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(refundBody(payment));
        return payment;
    }

    private static Map<String, Object> refundBody(Payment payment) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", "ref-scoped");
        map.put("status", "succeeded");
        map.put("payment_id", payment.getYookassaPaymentId());
        map.put("amount", Map.of("value", "150.00", "currency", "RUB"));
        return map;
    }

    private static AccessProvider.AccessAccount rotated(String username) {
        AccessProvider.AccessAccount u = new AccessProvider.AccessAccount();
        u.setUsername(username);
        u.setSubscriptionUrl("https://test.example.com/sub/rotated-" + username);
        return u;
    }
}
