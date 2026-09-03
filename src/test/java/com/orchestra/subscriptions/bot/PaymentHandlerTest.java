package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.service.PaymentProcessingService;
import com.orchestra.subscriptions.util.StarsAmount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест pre-checkout валидации Stars-платежей — самая ошибкоопасная часть money-path
 * (в апреле 2026 баг со сверкой суммы молча резал ВСЕ Stars-оплаты с промокодом). После
 * выноса из god-class'а её можно проверить с моком сендера, не поднимая Spring. Ожидаемую
 * сумму считаем через реальный {@link StarsAmount}, чтобы тест не разъезжался с продакшеном.
 */
class PaymentHandlerTest {

    private PaymentRepository paymentRepository;
    private AppConfig appConfig;
    private PaymentProcessingService paymentProcessingService;
    private TelegramSender telegramSender;
    private PaymentHandler handler;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentProcessingService = mock(PaymentProcessingService.class);
        telegramSender = mock(TelegramSender.class);
        handler = new PaymentHandler(appConfig, paymentRepository,
                mock(com.orchestra.subscriptions.service.payment.PaymentFactory.class),
                mock(YooKassaClient.class), mock(CryptoBotClient.class),
                paymentProcessingService,
                mock(com.orchestra.subscriptions.service.AccessService.class),
                mock(com.orchestra.subscriptions.service.DeviceTrackingService.class),
                mock(com.orchestra.subscriptions.service.PromoCodeService.class),
                new PendingInputService(),
                telegramSender);
    }

    @Test
    void preCheckout_nonNumericPayload_rejects() throws Exception {
        handler.handlePreCheckout(query("not-a-number", "XTR", 100));
        assertThat(captureAnswer().getOk()).isFalse();
    }

    @Test
    void preCheckout_paymentNotFound_rejects() throws Exception {
        when(paymentRepository.findById(55L)).thenReturn(Optional.empty());
        handler.handlePreCheckout(query("55", "XTR", 100));
        assertThat(captureAnswer().getOk()).isFalse();
    }

    @Test
    void preCheckout_nonStarsProvider_rejects() throws Exception {
        when(paymentRepository.findById(55L)).thenReturn(Optional.of(
                Payment.builder().id(55L).provider(Payment.Provider.YOOKASSA)
                        .status(Payment.Status.PENDING).build()));
        handler.handlePreCheckout(query("55", "XTR", 100));
        assertThat(captureAnswer().getOk()).isFalse();
    }

    @Test
    void preCheckout_amountMismatch_rejects() throws Exception {
        stubPendingStarsPayment();
        int expected = (int) StarsAmount.compute(100L, 10_000L, 10_000L);
        handler.handlePreCheckout(query("55", "XTR", expected + 1)); // на 1 больше — не сходится
        assertThat(captureAnswer().getOk()).isFalse();
    }

    @Test
    void preCheckout_validStars_approves() throws Exception {
        stubPendingStarsPayment();
        int expected = (int) StarsAmount.compute(100L, 10_000L, 10_000L);
        handler.handlePreCheckout(query("55", "XTR", expected));
        AnswerPreCheckoutQuery answer = captureAnswer();
        assertThat(answer.getOk()).isTrue();
    }

    @Test
    void handleSuccessfulPayment_delegatesToProcessing() {
        Update update = mock(Update.class, RETURNS_DEEP_STUBS);
        SuccessfulPayment sp = mock(SuccessfulPayment.class);
        when(sp.getInvoicePayload()).thenReturn("55");
        when(sp.getTelegramPaymentChargeId()).thenReturn("charge_1");
        when(sp.getTotalAmount()).thenReturn(100);
        when(sp.getCurrency()).thenReturn("XTR");
        when(update.getMessage().getSuccessfulPayment()).thenReturn(sp);

        handler.handleSuccessfulPayment(update);

        verify(paymentProcessingService).handleStarsSucceeded("55", "charge_1", 100L);
    }

    // ===== helpers =====

    /** PENDING Stars-платёж id=55, тариф p1 (100 ⭐ / 100 ₽, без скидки). */
    private void stubPendingStarsPayment() {
        when(paymentRepository.findById(55L)).thenReturn(Optional.of(
                Payment.builder().id(55L).provider(Payment.Provider.STARS)
                        .status(Payment.Status.PENDING).planId("p1").amountKopeks(10_000L).build()));
        AppConfig.Plan plan = mock(AppConfig.Plan.class);
        when(plan.getPriceStars()).thenReturn(100L);
        when(plan.getPriceKopeks()).thenReturn(10_000L);
        when(appConfig.findPlan("p1")).thenReturn(plan);
    }

    private PreCheckoutQuery query(String payload, String currency, Integer total) {
        PreCheckoutQuery q = mock(PreCheckoutQuery.class);
        when(q.getId()).thenReturn("q1");
        when(q.getInvoicePayload()).thenReturn(payload);
        when(q.getCurrency()).thenReturn(currency);
        when(q.getTotalAmount()).thenReturn(total);
        return q;
    }

    private AnswerPreCheckoutQuery captureAnswer() throws Exception {
        ArgumentCaptor<AnswerPreCheckoutQuery> cap = ArgumentCaptor.forClass(AnswerPreCheckoutQuery.class);
        verify(telegramSender).execute(cap.capture());
        return cap.getValue();
    }
}
