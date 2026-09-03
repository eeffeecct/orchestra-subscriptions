package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Возврат за докупленный слот устройства («+1 устройство»).
 * <p>
 * Слот неделим: пропорция к нему неприменима — но именно поэтому частичный возврат не вправе
 * забрать его целиком. До фикса {@code applyAddonRefund} уменьшал {@code device_limit} при ЛЮБОЙ
 * сумме возврата: 50 ₽, возвращённые с оплаченного слота, отбирали устройство на весь оставшийся
 * срок. Порог тот же, что у остальных необратимых эффектов возврата ({@code FULL_REFUND_PERCENT}).
 */
@SpringBootTest
@Testcontainers
class DeviceAddonRefundIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PaymentProcessingService paymentProcessingService;
    @Autowired
    PaymentRepository paymentRepository;
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

    private static final long TG_ID = 880001L;
    private static final long OWNER_ID = 999999999L;
    private static final String YK_ID = "yk_addon_refund";
    private static final long ADDON_KOPEKS = 30_000L;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Полный возврат за слот забирает слот")
    void fullRefundTakesTheSlot() {
        saveUserWithSlots();
        saveAddonPayment();
        stubRefund("300.00");

        paymentProcessingService.handleRefundSucceeded(refundWebhook());

        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getDeviceLimit())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Частичный возврат за слот слот НЕ забирает, а зовёт владельца")
    void partialRefundKeepsTheSlot() {
        saveUserWithSlots();
        saveAddonPayment();
        stubRefund("50.00");

        paymentProcessingService.handleRefundSucceeded(refundWebhook());

        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getDeviceLimit())
                .isEqualTo(3);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notificationService, atLeastOnce()).sendPlainText(eq(OWNER_ID), text.capture());
        assertThat(text.getAllValues())
                .anyMatch(t -> t.contains("НЕ изменён") && t.contains("ЧАСТИЧНЫЙ"));
    }

    // ===== helpers =====

    /** Человек с базовыми 2 устройствами и одним докупленным слотом. */
    private void saveUserWithSlots() {
        userRepository.save(Customer.builder()
                .telegramId(TG_ID)
                .telegramUsername("addontest")
                .providerUsername("tg_addontest_" + TG_ID)
                .subscriptionLink("https://example.com/sub/tok")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .deviceLimit(3)
                .build());
    }

    private void saveAddonPayment() {
        paymentRepository.save(Payment.builder()
                .yookassaPaymentId(YK_ID)
                .telegramId(TG_ID)
                .telegramUsername("addontest")
                .provider(Payment.Provider.YOOKASSA)
                .planId(Payment.DEVICE_ADDON_PLAN_ID)
                .amountKopeks(ADDON_KOPEKS)
                .status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now().minusDays(1))
                .build());
    }

    /**
     * Сумму возврата обработчик берёт из ПОДТВЕРЖДЁННОГО ответа API, а не из неподписанного
     * вебхука — поэтому она задаётся именно здесь.
     */
    private void stubRefund(String rubles) {
        YooKassaClient.Amount amount = new YooKassaClient.Amount();
        amount.setValue(rubles);
        amount.setCurrency("RUB");
        YooKassaClient.RefundResponse refund = new YooKassaClient.RefundResponse();
        refund.setId("ref_addon");
        refund.setStatus("succeeded");
        refund.setPaymentId(YK_ID);
        refund.setAmount(amount);
        when(yooKassaClient.getRefund("ref_addon")).thenReturn(refund);
    }

    private Map<String, Object> refundWebhook() {
        return Map.of(
                "id", "ref_addon",
                "payment_id", YK_ID,
                "status", "succeeded");
    }
}
