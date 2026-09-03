package com.orchestra.subscriptions.web;

import com.orchestra.subscriptions.client.CryptoBotSignatureVerifier;
import com.orchestra.subscriptions.service.PaymentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты эндпоинта {@code POST /webhook/cryptobot}.
 * <p>
 * Проверяют только web-слой (подпись + роутинг в сервис), без базы данных.
 * Логика активации/идемпотентности покрыта в {@link com.orchestra.subscriptions.service.PaymentProcessingServiceIntegrationTest}.
 */
@WebMvcTest(WebhookController.class)
class WebhookControllerCryptoTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentProcessingService paymentProcessingService;

    @MockitoBean
    CryptoBotSignatureVerifier cryptoBotSignatureVerifier;

    @BeforeEach
    void setUp() {
        // По умолчанию подпись невалидна — каждый тест явно переопределит, если нужно.
        when(cryptoBotSignatureVerifier.verify(anyString(), any())).thenReturn(false);
    }

    @Test
    void validSignatureInvoicePaid_callsHandleCryptoSucceeded() throws Exception {
        String body = "{\"update_type\":\"invoice_paid\",\"payload\":{\"invoice_id\":528890,\"status\":\"paid\"}}";
        when(cryptoBotSignatureVerifier.verify(body, "goodsig")).thenReturn(true);

        mockMvc.perform(post("/webhook/cryptobot")
                        .header("crypto-pay-api-signature", "goodsig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(paymentProcessingService).handleCryptoSucceeded(eq("528890"));
    }

    @Test
    void invalidSignature_returns401_andDoesNotCallService() throws Exception {
        String body = "{\"update_type\":\"invoice_paid\",\"payload\":{\"invoice_id\":528890}}";
        // Verifier по умолчанию вернёт false (см. setUp)

        mockMvc.perform(post("/webhook/cryptobot")
                        .header("crypto-pay-api-signature", "badsig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentProcessingService);
    }

    @Test
    void missingSignatureHeader_returns401() throws Exception {
        String body = "{\"update_type\":\"invoice_paid\",\"payload\":{\"invoice_id\":528890}}";

        mockMvc.perform(post("/webhook/cryptobot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentProcessingService);
    }

    @Test
    void emptyBody_returns200_butDoesNotCallService() throws Exception {
        mockMvc.perform(post("/webhook/cryptobot")
                        .header("crypto-pay-api-signature", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isOk());

        verifyNoInteractions(paymentProcessingService);
    }

    @Test
    void malformedJson_withValidSignature_returns200_doesNotCallService() throws Exception {
        String body = "not-json-at-all";
        when(cryptoBotSignatureVerifier.verify(body, "goodsig")).thenReturn(true);

        mockMvc.perform(post("/webhook/cryptobot")
                        .header("crypto-pay-api-signature", "goodsig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verifyNoInteractions(paymentProcessingService);
    }

    @Test
    void unknownUpdateType_returns200_doesNotCallService() throws Exception {
        String body = "{\"update_type\":\"invoice_expired\",\"payload\":{\"invoice_id\":528890}}";
        when(cryptoBotSignatureVerifier.verify(body, "goodsig")).thenReturn(true);

        mockMvc.perform(post("/webhook/cryptobot")
                        .header("crypto-pay-api-signature", "goodsig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(paymentProcessingService, never()).handleCryptoSucceeded(anyString());
    }

    @Test
    void missingPayload_returns200_doesNotCallService() throws Exception {
        String body = "{\"update_type\":\"invoice_paid\"}";
        when(cryptoBotSignatureVerifier.verify(body, "goodsig")).thenReturn(true);

        mockMvc.perform(post("/webhook/cryptobot")
                        .header("crypto-pay-api-signature", "goodsig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verifyNoInteractions(paymentProcessingService);
    }

    @Test
    void serviceThrows_returns200_forWebhookIdempotency() throws Exception {
        String body = "{\"update_type\":\"invoice_paid\",\"payload\":{\"invoice_id\":528890}}";
        when(cryptoBotSignatureVerifier.verify(body, "goodsig")).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(paymentProcessingService).handleCryptoSucceeded(anyString());

        // Даже если сервис упадёт — webhook отвечает 200, чтобы CryptoBot не ретраил в цикле.
        mockMvc.perform(post("/webhook/cryptobot")
                        .header("crypto-pay-api-signature", "goodsig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(paymentProcessingService).handleCryptoSucceeded(eq("528890"));
    }
}
