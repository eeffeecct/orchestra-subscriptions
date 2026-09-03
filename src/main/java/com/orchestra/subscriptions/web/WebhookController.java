package com.orchestra.subscriptions.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestra.subscriptions.client.CryptoBotSignatureVerifier;
import com.orchestra.subscriptions.service.PaymentProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Приём webhook-уведомлений от платёжных провайдеров (ЮKassa и CryptoBot).
 * <p>
 * ЮKassa-эндпоинт всегда возвращает 200 (идемпотентность + ре-верификация).<br>
 * CryptoBot-эндпоинт проверяет HMAC-подпись и возвращает 401 при невалидной — это единственный
 * вектор, который нам нужно прикрыть (ЮKassa присылает без подписи, но с TLS-origin'а, который
 * невозможно подделать снаружи без компромата Caddy).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhook")
public class WebhookController {

    private final PaymentProcessingService paymentProcessingService;
    private final CryptoBotSignatureVerifier cryptoBotSignatureVerifier;
    private final ObjectMapper objectMapper;

    @PostMapping("/yookassa")
    public ResponseEntity<Void> yookassa(@RequestBody(required = false) Map<String, Object> notification) {
        if (notification == null) {
            log.warn("YooKassa webhook: empty body");
            return ResponseEntity.ok().build();
        }

        String event = (String) notification.get("event");
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) notification.get("object");
        if (event == null || object == null) {
            log.warn("YooKassa webhook: malformed body {}", notification);
            return ResponseEntity.ok().build();
        }

        String objectId = (String) object.get("id");
        log.info("YooKassa webhook: event={} objectId={}", event, objectId);

        try {
            switch (event) {
                case "payment.succeeded" -> paymentProcessingService.handleSucceeded(objectId);
                case "payment.canceled" -> paymentProcessingService.handleCanceled(objectId);
                // Для refund.succeeded object.id = id возврата, а не платежа. Поэтому передаём
                // весь object — внутри сервиса будем доставать object.payment_id.
                case "refund.succeeded" -> paymentProcessingService.handleRefundSucceeded(object);
                default -> log.info("Ignoring YooKassa event {}", event);
            }
        } catch (Exception e) {
            log.error("Failed handling YooKassa webhook for object {}", objectId, e);
            // Возвращаем 200 всё равно — повторные доставки обрабатываются идемпотентно.
        }
        return ResponseEntity.ok().build();
    }

    /**
     * CryptoBot (Crypto Pay API) webhook.
     * Тело принимаем строкой, а не Map, потому что HMAC считается от сырых байт —
     * Spring-овая десериализация + re-serialize поменяет порядок ключей и подпись не сойдётся.
     */
    @PostMapping("/cryptobot")
    public ResponseEntity<Void> cryptobot(
            @RequestHeader(value = "crypto-pay-api-signature", required = false) String signature,
            @RequestBody(required = false) String rawBody) {

        if (rawBody == null || rawBody.isBlank()) {
            log.warn("CryptoBot webhook: empty body");
            return ResponseEntity.ok().build();
        }
        if (!cryptoBotSignatureVerifier.verify(rawBody, signature)) {
            log.warn("CryptoBot webhook: bad or missing signature");
            return ResponseEntity.status(401).build();
        }

        // Парсим уже после проверки подписи.
        Map<String, Object> notification;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(rawBody, Map.class);
            notification = parsed;
        } catch (Exception e) {
            log.warn("CryptoBot webhook: malformed JSON", e);
            return ResponseEntity.ok().build();
        }

        String updateType = (String) notification.get("update_type");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) notification.get("payload");
        if (updateType == null || payload == null) {
            log.warn("CryptoBot webhook: missing update_type or payload in {}", notification);
            return ResponseEntity.ok().build();
        }

        Object invoiceIdRaw = payload.get("invoice_id");
        String invoiceId = invoiceIdRaw == null ? null : String.valueOf(invoiceIdRaw);
        log.info("CryptoBot webhook: update_type={} invoiceId={}", updateType, invoiceId);

        try {
            if ("invoice_paid".equals(updateType) && invoiceId != null) {
                paymentProcessingService.handleCryptoSucceeded(invoiceId);
            } else {
                log.info("Ignoring CryptoBot update {}", updateType);
            }
        } catch (Exception e) {
            log.error("Failed handling CryptoBot webhook for invoice {}", invoiceId, e);
            // 200 — webhook-доставки идемпотентны, повтор обработается тем же образом.
        }
        return ResponseEntity.ok().build();
    }
}
