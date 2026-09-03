package com.orchestra.subscriptions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.web.api.AccountSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Жизненный цикл лимита устройств: понижение ступени, откат ступени после возврата и покупка
 * из web-ЛК. Отдельный класс со СВОИМ набором ступеней — базовый test-конфиг {@code app.device-tiers}
 * не описывает вовсе, а без них вся эта логика вырождается в no-op и тесты проходили бы вхолостую
 * (ровно так и было замечено при написании: assert на «ступень сохранилась» зеленел, потому что
 * ступени не существовало).
 * <p>
 * Ступени намеренно повторяют прод: d2 — базовая (множитель 1.0), f5 — семейная.
 */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.device-tiers[0].id=d2",
        "app.device-tiers[0].name=2 устройства",
        "app.device-tiers[0].devices=2",
        "app.device-tiers[0].price-multiplier=1.0",
        "app.device-tiers[1].id=f5",
        "app.device-tiers[1].name=Семейная",
        "app.device-tiers[1].devices=5",
        "app.device-tiers[1].price-multiplier=1.8",
})
class DeviceTierLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AccessService accessService;
    @Autowired
    PaymentProcessingService paymentProcessingService;
    @Autowired
    DeviceTrackingService deviceTrackingService;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    UserDeviceRepository deviceRepository;
    @Autowired
    AccountSessionService sessionService;
    @Autowired
    com.orchestra.subscriptions.config.AppConfig appConfig;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    com.orchestra.subscriptions.access.AccessProvider accessProvider;
    @MockitoBean
    YooKassaClient yooKassaClient;
    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;

    private static final long TG_ID = 555000111L;

    @BeforeEach
    void setUp() {
        deviceRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Customer saveUser(Integer deviceLimit, String tierId) {
        return userRepository.save(Customer.builder()
                .telegramId(TG_ID)
                .telegramUsername("devicetest")
                .providerUsername("tg_devicetest_" + TG_ID)
                .subscriptionLink("https://example.com/sub/tok")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(60))
                .deviceLimit(deviceLimit)
                .deviceTierId(tierId)
                .email("devices@example.com")
                .build());
    }

    /** Устройство, которое последний раз выходило на связь {@code minutesAgo} минут назад. */
    private UserDevice saveDevice(String hwid, int minutesAgo) {
        LocalDateTime seen = LocalDateTime.now().minusMinutes(minutesAgo);
        return deviceRepository.save(UserDevice.builder()
                .telegramId(TG_ID).hwid(hwid)
                .status(UserDevice.Status.ACTIVE)
                .firstSeenAt(seen).lastSeenAt(seen)
                .build());
    }

    private UserDevice reload(UserDevice device) {
        return deviceRepository.findById(device.getId()).orElseThrow();
    }

    // ================================================================
    //  Понижение ступени реально отключает лишние устройства
    // ================================================================

    /**
     * Главный регресс. Юзер с семейной ступенью (5 устройств) покупает базовую (2). Экран
     * покупки обещает «лишние будут отключены» — до фикса лимит менялся только в БД, а все
     * пять устройств продолжали получать ноды бессрочно.
     */
    @Test
    void downgradingTier_disconnectsSurplusDevices_keepingMostRecentlyUsed() {
        saveUser(5, "f5");
        UserDevice freshest = saveDevice("hwid-fresh-0000000001", 2);
        UserDevice recent = saveDevice("hwid-recent-000000001", 30);
        UserDevice older = saveDevice("hwid-older-0000000001", 300);
        UserDevice oldest = saveDevice("hwid-oldest-000000001", 900);

        accessService.activatePaidSubscription(TG_ID, "devicetest", 30,
                new com.orchestra.subscriptions.config.AppConfig.DeviceTier() {{
                    setId("d2");
                    setName("2 устройства");
                    setDevices(2);
                }});

        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getDeviceLimit()).isEqualTo(2);
        // Свежие два сохраняют доступ, давно молчавшие — теряют слот.
        assertThat(reload(freshest).getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(reload(recent).getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(reload(older).getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
        assertThat(reload(oldest).getStatus()).isEqualTo(UserDevice.Status.BLOCKED);
    }

    /** Апгрейд (2 → 5) никого не трогает: слотов стало больше, отключать нечего. */
    @Test
    void upgradingTier_keepsAllDevicesConnected() {
        saveUser(2, "d2");
        UserDevice a = saveDevice("hwid-aaaa-00000000001", 5);
        UserDevice b = saveDevice("hwid-bbbb-00000000001", 500);

        accessService.activatePaidSubscription(TG_ID, "devicetest", 30,
                new com.orchestra.subscriptions.config.AppConfig.DeviceTier() {{
                    setId("f5");
                    setName("Семейная");
                    setDevices(5);
                }});

        assertThat(reload(a).getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(reload(b).getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
    }

    /**
     * Legacy-юзер (лимита нет) получает дни без выбора ступени — реф-бонус, грант, /extend.
     * Его устройства трогать нельзя ни при каких обстоятельствах.
     */
    @Test
    void legacyUserGettingBonusDays_keepsAllDevices() {
        saveUser(null, null);
        UserDevice a = saveDevice("hwid-legacy-000000001", 5);
        UserDevice b = saveDevice("hwid-legacy-000000002", 5000);

        accessService.activatePaidSubscription(TG_ID, "devicetest", 14);

        Customer after = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(after.getDeviceLimit()).isNull();
        assertThat(reload(a).getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
        assertThat(reload(b).getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
    }

    // ================================================================
    //  Возврат тарифа не отбирает докупленные слоты
    // ================================================================

    /**
     * Возврат ТАРИФА не должен отбирать докупленные «+1 устройство»: за них платили отдельным
     * платежом, который здесь не возвращается (деньги остаются у нас). До фикса откат ставил
     * «голый» размер ступени и молча съедал оплаченные слоты.
     */
    @Test
    void refundingPlan_keepsSeparatelyPaidAddonSlots() {
        saveUser(4, "d2"); // d2 (2) + 2 докупленных слота
        paymentRepository.save(Payment.builder()
                .yookassaPaymentId("yk_older_d2")
                .telegramId(TG_ID).telegramUsername("devicetest")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").deviceTierId("d2").amountKopeks(17900L)
                .status(Payment.Status.SUCCEEDED).paidAt(LocalDateTime.now().minusDays(30))
                .build());
        paymentRepository.save(Payment.builder()
                .yookassaPaymentId("yk_refunded_d2")
                .telegramId(TG_ID).telegramUsername("devicetest")
                .provider(Payment.Provider.YOOKASSA)
                .planId("month_1").deviceTierId("d2").amountKopeks(17900L)
                .status(Payment.Status.SUCCEEDED).paidAt(LocalDateTime.now())
                .build());

        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_keep_addons");
        refund.setStatus("succeeded");
        refund.setPaymentId("yk_refunded_d2");
        when(yooKassaClient.getRefund("ref_keep_addons")).thenReturn(refund);

        paymentProcessingService.handleRefundSucceeded(Map.of(
                "id", "ref_keep_addons",
                "payment_id", "yk_refunded_d2",
                "status", "succeeded",
                "amount", Map.of("value", "179.00", "currency", "RUB")));

        Customer after = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(after.getDeviceTierId()).isEqualTo("d2");
        // 2 от ступени + 2 докупленных = 4, а НЕ «голые» 2.
        assertThat(after.getDeviceLimit()).isEqualTo(4);
    }

    // ================================================================
    //  Покупка из web-ЛК: триал vs legacy
    // ================================================================

    /**
     * Триал ({@code device_limit=1}, ступени нет) покупает из ЛК, где ступени не выбираются.
     * Списывается БАЗОВАЯ цена плана — значит и ступень в платёж должна уехать базовая, иначе
     * он платит за «2 устройства», а вебхук навсегда оставляет ему триальный лимит 1 (и слот
     * не докупить — докупка требует ступени).
     */
    @Test
    void trialBuyingFromWebAccount_getsBaseTier() throws Exception {
        userRepository.save(Customer.builder()
                .telegramId(TG_ID)
                .providerUsername("trial_" + TG_ID)
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(3))
                .email("devices@example.com")
                .subscriptionLink("https://example.com/sub/test-token")
                .deviceLimit(1)   // триальный лимит, deviceTierId намеренно null
                .build());
        stubYooKassaCreate("yk-trial-lk");

        payFromAccount("month_1");

        assertThat(paymentRepository.findAll())
                .singleElement()
                .satisfies(p -> assertThat(p.getDeviceTierId()).isEqualTo("d2"));
    }

    /**
     * Legacy-юзер (лимита нет вовсе) ступень получать НЕ должен — иначе покупка из ЛК
     * ретроактивно ограничила бы того, кому обещали «до 5 устройств».
     */
    @Test
    void legacyBuyingFromWebAccount_staysWithoutTier() throws Exception {
        saveUser(null, null);
        stubYooKassaCreate("yk-legacy-lk");

        payFromAccount("month_1");

        assertThat(paymentRepository.findAll())
                .singleElement()
                .satisfies(p -> assertThat(p.getDeviceTierId()).isNull());
    }

    private void payFromAccount(String planId) throws Exception {
        String cookie = sessionService.issue(TG_ID, 0);
        mockMvc.perform(post("/api/account/payments/yookassa")
                        .cookie(new Cookie(AccountSessionService.COOKIE_NAME, cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("planId", planId))))
                .andExpect(status().isOk());
    }

    private void stubYooKassaCreate(String paymentId) {
        YooKassaClient.PaymentResponse resp = new YooKassaClient.PaymentResponse();
        resp.setId(paymentId);
        YooKassaClient.Confirmation conf = new YooKassaClient.Confirmation();
        conf.setConfirmationUrl("https://yoomoney.test/checkout/" + paymentId);
        resp.setConfirmation(conf);
        when(yooKassaClient.createPayment(anyLong(), anyString(), anyString(),
                anyMap(), anyString(), anyBoolean())).thenReturn(resp);
    }

    // ================================================================
    //  Слот, освободившийся после отключения, забирает заблокированный
    // ================================================================

    /**
     * Обратная сторона понижения: юзер отключил лишнее устройство — заблокированное должно
     * вернуться в строй само, без обращения в поддержку.
     */
    @Test
    void freeingASlot_letsBlockedDeviceBecomeActiveAgain() {
        saveUser(1, "d2");
        UserDevice active = saveDevice("hwid-active-000000001", 5);
        UserDevice blocked = saveDevice("hwid-blocked-00000001", 10);
        blocked.setStatus(UserDevice.Status.BLOCKED);
        deviceRepository.save(blocked);

        deviceTrackingService.revoke(TG_ID, active.getId());

        assertThat(deviceTrackingService.restore(TG_ID, blocked.getId())).isTrue();
        assertThat(reload(blocked).getStatus()).isEqualTo(UserDevice.Status.ACTIVE);
    }

    /** Слоты считаются только по устройствам в окне активности — молчащие не мешают. */
    @Test
    void usedSlots_ignoresDevicesOutsideInactivityWindow() {
        saveUser(2, "d2");
        saveDevice("hwid-online-000000001", 5);
        saveDevice("hwid-silent-000000001", 60 * 24 * 60); // 60 дней, окно 30

        assertThat(deviceTrackingService.usedSlots(TG_ID)).isEqualTo(1);
        assertThat(deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(TG_ID))
                .extracting(UserDevice::getStatus)
                .containsOnly(UserDevice.Status.ACTIVE);
    }

    /**
     * Санити на сам тест-класс: ступени из {@code @TestPropertySource} реально доехали до
     * контекста. Без этого весь класс зеленел бы вхолостую — базовый test-конфиг ступеней не
     * описывает, {@code findDeviceTierOptional} возвращал бы empty, и проверяемые ветки просто
     * не выполнялись бы (именно так и случилось в первой редакции этих тестов).
     */
    @Test
    void tiersAreConfiguredForThisContext() {
        assertThat(appConfig.findDeviceTierOptional("d2")).isPresent()
                .get().satisfies(t -> assertThat(t.getDevices()).isEqualTo(2));
        assertThat(appConfig.findDeviceTierOptional("f5")).isPresent()
                .get().satisfies(t -> assertThat(t.getDevices()).isEqualTo(5));
        // Базовая ступень (множитель 1.0) — та, что выдаётся при покупке из ЛК.
        assertThat(appConfig.baseDeviceTier()).isPresent()
                .get().satisfies(t -> assertThat(t.getId()).isEqualTo("d2"));
    }
}
