package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
// Ступень устройств задаётся только здесь: базовый test-конфиг их не описывает, а цена
// автосписания считается как план × множитель ступени — без неё не проверить, что в DM
// уходит фактически списанная сумма, а не цена плана. d3: 150 ₽ × 1.2 = 180 ₽.
// Ступеней ДВЕ, чтобы можно было развести ступень человека и ступень подписки: списывается
// вторая, и проверить это одной ступенью нельзя. d3: 150 ₽ × 1.2 = 180 ₽, d1: 150 ₽ × 0.8 = 120 ₽.
@org.springframework.test.context.TestPropertySource(properties = {
        "app.device-tiers[0].id=d3",
        "app.device-tiers[0].name=3 устройства",
        "app.device-tiers[0].devices=3",
        "app.device-tiers[0].price-multiplier=1.2",
        "app.device-tiers[1].id=d1",
        "app.device-tiers[1].name=1 устройство",
        "app.device-tiers[1].devices=1",
        "app.device-tiers[1].price-multiplier=0.8"
})
class AutoRenewServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AutoRenewService autoRenewService;

    @Autowired
    CustomerRepository userRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    YooKassaClient yooKassaClient;

    @MockitoBean
    AccessProvider accessProvider;



    @MockitoBean
    com.orchestra.subscriptions.bot.SubscriptionBot subscriptionBot;

    @MockitoBean
    NotificationService notificationService;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mockUser = new AccessProvider.AccessAccount();
        mockUser.setSubscriptionUrl("https://example.com/sub/test");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mockUser);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
    }

    // ========================
    // runOnce — window selection
    // ========================

    @Test
    void runOnce_shouldPickUpUsersInsideWindow() {
        // Истекает через 24.5 часа — внутри окна [24h, 25h].
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pm_yk_123"));

        autoRenewService.runOnce();

        verify(yooKassaClient, times(1))
                .chargeRecurrent(eq("pm_alice"), anyLong(), anyString(), anyString());
    }

    @Test
    void runOnce_shouldSkipUsersOutsideWindow() {
        // Слишком рано — 3 дня остатка
        save(autorenewReady(100L, "far", LocalDateTime.now().plusDays(3)));
        // Слишком поздно — уже истекает через час
        save(autorenewReady(200L, "close", LocalDateTime.now().plusHours(1)));

        autoRenewService.runOnce();

        verify(yooKassaClient, never()).chargeRecurrent(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void runOnce_shouldSkipUsersWithAutorenewDisabled() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        user.setAutorenewEnabled(false);
        save(user);

        autoRenewService.runOnce();

        verify(yooKassaClient, never()).chargeRecurrent(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void runOnce_shouldSkipUsersWithoutMethodId() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        user.setAutorenewMethodId(null);
        save(user);

        autoRenewService.runOnce();

        verify(yooKassaClient, never()).chargeRecurrent(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void runOnce_shouldSkipUsersWithRecentFailure() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        // Failure был 5 часов назад — внутри 25-часового cooldown'а.
        user.setAutorenewLastFailureAt(LocalDateTime.now().minusHours(5));
        save(user);

        autoRenewService.runOnce();

        verify(yooKassaClient, never()).chargeRecurrent(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void runOnce_shouldPickUpUserWhoseFailureIsOlderThanCooldown() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        // Failure был 30 часов назад — уже вышли за cooldown, можно ретраить.
        user.setAutorenewLastFailureAt(LocalDateTime.now().minusHours(30));
        save(user);
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pm_yk_123"));

        autoRenewService.runOnce();

        verify(yooKassaClient, times(1))
                .chargeRecurrent(anyString(), anyLong(), anyString(), anyString());
    }

    // ========================
    // chargeAndActivate — success
    // ========================

    @Test
    void runOnce_onSuccessShouldActivateAndCreateSucceededPayment() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_abc"));

        autoRenewService.runOnce();

        // Платёж сохранён с SUCCEEDED
        List<Payment> payments = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(payments.get(0).getYookassaPaymentId()).isEqualTo("pay_abc");
        assertThat(payments.get(0).getPaidAt()).isNotNull();

        // Подписка продлилась на длительность плана (month_1 = 30 дней) поверх текущих 24.5 часов.
        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(30));
        verify(accessProvider).enableUserUntil(eq("tg_alice"), any(), any());
    }

    @Test
    void runOnce_onSuccessShouldResetLastFailure() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        user.setAutorenewLastFailureAt(LocalDateTime.now().minusHours(30)); // старый failure
        save(user);
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_abc"));

        autoRenewService.runOnce();

        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.getAutorenewLastFailureAt()).isNull();
    }

    /**
     * Регрессия: деньги списаны, но активация упала → платёж обязан остаться PENDING.
     * <p>
     * Раньше SUCCEEDED проставлялся и коммитился ДО активации: при недоступном AccessProvider юзер
     * оставался без дней за свои деньги, а реконсилер такой платёж не подхватывал (он ищет
     * только PENDING) — чинить приходилось руками. Теперь строка остаётся PENDING с известным
     * yookassa_payment_id, то есть попадает в выборку реконсилера и активация догоняется.
     */
    @Test
    void runOnce_whenActivationFailsShouldLeavePaymentPendingForReconciler() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_provider_down"));
        // AccessProvider недоступен ровно в момент активации.
        org.mockito.Mockito.doThrow(new IllegalStateException("provider down"))
                .when(accessProvider).enableUserUntil(anyString(), any(), any());

        autoRenewService.runOnce();

        List<Payment> payments = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L);
        assertThat(payments).hasSize(1);
        Payment p = payments.get(0);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.PENDING);
        // id обязан быть сохранён — без него реконсилер не сможет опросить YooKassa.
        assertThat(p.getYookassaPaymentId()).isEqualTo("pay_provider_down");
        assertThat(p.isAutorenew()).isTrue();
        // Успешного DM быть не должно: дни не начислены.
        verify(notificationService, never()).sendAutoRenewSuccess(anyLong(), any(), anyLong());
    }

    /**
     * Регрессия: автосписание обязано проходить через общий путь обработки платежа. Раньше
     * оно шло мимо — и всё, что этот путь делает после начисления дней, для автопродления
     * просто не выполнялось.
     */
    @Test
    void runOnce_onSuccessShouldGoThroughCommonPaymentPath() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_common_path"));

        autoRenewService.runOnce();

        // Сумма платежа — фактически списанная (month_1 без ступени = цена плана).
        Payment p = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L).get(0);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(p.getAmountKopeks()).isEqualTo(15000L);
    }

    /**
     * Регрессия: DM об автосписании должен называть ФАКТИЧЕСКИ списанную сумму. У юзера со
     * ступенью «3 устройства» списывается цена ступени, а в сообщении стояла цена плана —
     * в проде это «списали 215 ₽, написали 179 ₽», читалось как обман.
     */
    @Test
    void runOnce_shouldReportActuallyChargedAmountForDeviceTier() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        user.setDeviceTierId("d3"); // ступень подписки; множитель 1.2 → 150 × 1.2 = 180 ₽
        save(user);
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_tier"));

        autoRenewService.runOnce();

        long charged = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L).get(0).getAmountKopeks();
        assertThat(charged).isEqualTo(18000L);
        // В DM уходит именно она, а не plan.priceKopeks (15000).
        verify(notificationService).sendAutoRenewSuccess(eq(100L), any(), eq(18000L));
        verify(notificationService, never()).sendAutoRenewSuccess(anyLong(), any(), eq(15000L));
    }

    /**
     * ⛔ Списывается ступень ПРОДЛЕВАЕМОЙ ПОДПИСКИ, а не ступень последней покупки, лежащая на
     * человеке (решение владельца 14.08.2026).
     * <p>
     * Пока подписка одна, эти две вещи совпадают. С несколькими они расходятся после
     * переключения, и расхождение било по деньгам дважды: списывалась цена чужой ступени, а
     * активация переписывала лимит устройств продлеваемой подписки под неё. Человек,
     * переключившийся на «1 устройство», ночью получал счёт за «3 устройства» и лимит 3.
     */
    @Test
    void runOnce_shouldChargeTierOfRenewedSubscriptionNotOfLastPurchase() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        user.setDeviceTierId("d1");           // ступень подписки: 150 × 0.8 = 120 ₽
        user.setAutorenewDeviceTierId("d3");  // ступень последней покупки: 180 ₽ — НЕ она
        save(user);
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_sub_tier"));

        autoRenewService.runOnce();

        Payment p = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L).get(0);
        assertThat(p.getAmountKopeks())
                .as("цена считается по ступени подписки, а не по ступени последней покупки")
                .isEqualTo(12000L);
        assertThat(p.getDeviceTierId())
                .as("активация применит эту же ступень — лимит устройств подписки не изменится")
                .isEqualTo("d1");
        verify(notificationService).sendAutoRenewSuccess(eq(100L), any(), eq(12000L));
    }

    /**
     * У подписки ступени нет вовсе (триал, покупка до появления ступеней): списывается базовая
     * цена плана, ступень человека не подставляется. Иначе автосписание навязало бы лимит
     * устройств подписке, которая жила без него.
     */
    @Test
    void runOnce_subscriptionWithoutTier_chargesPlainPlanPrice() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        user.setAutorenewDeviceTierId("d3"); // на человеке ступень есть, у подписки — нет
        save(user);
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_no_tier"));

        autoRenewService.runOnce();

        Payment p = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L).get(0);
        assertThat(p.getAmountKopeks()).isEqualTo(15000L);
        assertThat(p.getDeviceTierId()).isNull();
    }

    /**
     * Автосписание не должно слать юзеру «Оплата получена» — он ничего не покупал, деньги
     * ушли с карты сами. Текст про автопродление объясняет это и даёт путь к управлению.
     */
    @Test
    void runOnce_shouldSendAutorenewDmNotGenericPaymentDm() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(succeededResponse("pay_dm"));

        autoRenewService.runOnce();

        verify(notificationService).sendAutoRenewSuccess(eq(100L), any(), anyLong());
        verify(notificationService, never()).sendPaymentSuccess(anyLong(), any(), any());
    }

    // ========================
    // chargeAndActivate — failure
    // ========================

    @Test
    void runOnce_onChargeExceptionShouldMarkFailureAndCreateFailedPayment() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("card declined"));

        autoRenewService.runOnce();

        // Платёж помечен как FAILED
        List<Payment> payments = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(Payment.Status.FAILED);

        // last_failure_at проставлен
        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.getAutorenewLastFailureAt()).isNotNull();

        // Подписка НЕ продлилась — expires_at не ушла далеко в будущее
        assertThat(after.getExpiresAt()).isBefore(LocalDateTime.now().plusHours(26));

        // enableUserUntil в AccessProvider не вызывали (успешной активации не было)
        verify(accessProvider, never()).enableUserUntil(anyString(), any(), any());
    }

    @Test
    void runOnce_onNonSucceededStatusShouldTreatAsFailure() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        YooKassaClient.PaymentResponse canceled = new YooKassaClient.PaymentResponse();
        canceled.setId("pay_x");
        canceled.setStatus("canceled");
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(canceled);

        autoRenewService.runOnce();

        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.getAutorenewLastFailureAt()).isNotNull();
        assertThat(paymentRepository.findByTelegramIdOrderByCreatedAtDesc(100L).get(0).getStatus())
                .isEqualTo(Payment.Status.FAILED);
    }

    @Test
    void runOnce_onChargeFailureShouldAlertOwnerAndUser() {
        // Без этого алерта первые failure после включения recurrent payments будут невидимы:
        // владелец узнает о проблеме только когда юзер придёт в поддержку.
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("card declined"));

        autoRenewService.runOnce();

        verify(notificationService).sendAutoRenewFailed(100L);
        verify(notificationService).notifyOwnerAutorenewFailure(
                any(Customer.class), anyString());
    }

    @Test
    void runOnce_onNonSucceededStatusShouldAlertOwner() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));
        YooKassaClient.PaymentResponse canceled = new YooKassaClient.PaymentResponse();
        canceled.setId("pay_x");
        canceled.setStatus("canceled");
        when(yooKassaClient.chargeRecurrent(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(canceled);

        autoRenewService.runOnce();

        verify(notificationService).notifyOwnerAutorenewFailure(
                any(Customer.class), anyString());
    }

    // ========================
    // enableAutoRenew / disableAutoRenew
    // ========================

    @Test
    void disableAutoRenew_shouldKeepMethodId() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusDays(10)));

        autoRenewService.disableAutoRenew(100L);

        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.isAutorenewEnabled()).isFalse();
        assertThat(after.getAutorenewMethodId()).isEqualTo("pm_alice"); // сохранили
    }

    @Test
    void enableAutoRenew_shouldReturnTrueAndResetFailureWhenMethodSaved() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusDays(10));
        user.setAutorenewEnabled(false);
        user.setAutorenewLastFailureAt(LocalDateTime.now().minusHours(2));
        save(user);

        boolean ok = autoRenewService.enableAutoRenew(100L);

        assertThat(ok).isTrue();
        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.isAutorenewEnabled()).isTrue();
        assertThat(after.getAutorenewLastFailureAt()).isNull();
    }

    @Test
    void unbindCard_shouldClearMethodIdAndDisable() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusDays(10)));

        boolean result = autoRenewService.unbindCard(100L);

        assertThat(result).isTrue();
        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.getAutorenewMethodId()).isNull();
        assertThat(after.getAutorenewPlanId()).isNull();
        assertThat(after.isAutorenewEnabled()).isFalse();
        assertThat(after.getAutorenewLastFailureAt()).isNull();
        // Подписка и expires_at не трогаются — только автопродление.
        assertThat(after.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(after.getExpiresAt()).isNotNull();
    }

    @Test
    void unbindCard_shouldReturnFalseWhenNoCardBound() {
        // Юзер без method_id
        Customer user = Customer.builder()
                .telegramId(100L)
                .telegramUsername("alice")
                .providerUsername("tg_alice")
                .subscriptionLink("https://example.com/sub/alice")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build();
        userRepository.save(user);

        boolean result = autoRenewService.unbindCard(100L);

        assertThat(result).isFalse();
    }

    @Test
    void unbindCard_shouldReturnFalseForUnknownUser() {
        boolean result = autoRenewService.unbindCard(9999L);
        assertThat(result).isFalse();
    }

    @Test
    void runOnce_shouldSkipUsersAfterUnbind() {
        save(autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30)));

        autoRenewService.unbindCard(100L);
        autoRenewService.runOnce();

        verify(yooKassaClient, never()).chargeRecurrent(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void enableAutoRenew_shouldReturnFalseWhenNoMethodId() {
        Customer user = Customer.builder()
                .telegramId(100L)
                .telegramUsername("alice")
                .providerUsername("tg_alice")
                .subscriptionLink("https://example.com/sub/alice")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build();
        userRepository.save(user);

        boolean ok = autoRenewService.enableAutoRenew(100L);

        assertThat(ok).isFalse();
        assertThat(userRepository.findByTelegramId(100L).orElseThrow().isAutorenewEnabled()).isFalse();
    }

    // ========================
    // unknown plan protection
    // ========================

    @Test
    void runOnce_shouldDisableAutorenewWhenPlanNoLongerExists() {
        Customer user = autorenewReady(100L, "alice", LocalDateTime.now().plusHours(24).plusMinutes(30));
        user.setAutorenewPlanId("nonexistent_plan");
        save(user);

        autoRenewService.runOnce();

        // Autopay выключается, чтобы не спамить ошибки на каждом тике.
        Customer after = userRepository.findByTelegramId(100L).orElseThrow();
        assertThat(after.isAutorenewEnabled()).isFalse();
        verify(yooKassaClient, never()).chargeRecurrent(anyString(), anyLong(), anyString(), anyString());
    }

    // ========================
    // helpers
    // ========================

    private Customer save(Customer u) {
        return userRepository.save(u);
    }

    private Customer autorenewReady(Long telegramId, String username, LocalDateTime expiresAt) {
        return Customer.builder()
                .telegramId(telegramId)
                .telegramUsername(username)
                .providerUsername("tg_" + username)
                .subscriptionLink("https://example.com/sub/" + username)
                .status(Customer.Status.ACTIVE)
                .expiresAt(expiresAt)
                .autorenewMethodId("pm_" + username)
                .autorenewEnabled(true)
                .autorenewPlanId("month_1")
                .build();
    }

    private YooKassaClient.PaymentResponse succeededResponse(String paymentId) {
        YooKassaClient.PaymentResponse resp = new YooKassaClient.PaymentResponse();
        resp.setId(paymentId);
        resp.setStatus("succeeded");
        resp.setPaid(true);
        return resp;
    }

}
