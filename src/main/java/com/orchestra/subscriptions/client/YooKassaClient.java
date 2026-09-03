package com.orchestra.subscriptions.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.util.Money;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class YooKassaClient {

    private static final String BASE_URL = "https://api.yookassa.ru/v3";
    /** Потолок на рукопожатие CONNECT с прокси — единственная фаза мимо WebClientConfig. */
    private static final long PROXY_CONNECT_TIMEOUT_MS = 10_000L;

    private final AppConfig appConfig;
    private final HttpClient timeoutHttpClient;
    private WebClient webClient;

    @PostConstruct
    public void init() {
        AppConfig.YooKassaConfig cfg = appConfig.getYookassa();

        // База — общий HttpClient с таймаутами (см. WebClientConfig). Прокси навешиваем поверх
        // него, когда main не доносит данные до российских адресов напрямую: api.yookassa.ru
        // российский, и путь до него ломается вместе со всей остальной РФ. Тот же приём, что
        // Пусто — ходим напрямую, это обычный режим.
        HttpClient httpClient = timeoutHttpClient;
        if (cfg.getProxyHost() != null && !cfg.getProxyHost().isBlank()) {
            httpClient = httpClient.proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
                    .host(cfg.getProxyHost())
                    .port(cfg.getProxyPort())
                    // Обязательно: фаза CONNECT к прокси НЕ покрыта таймаутами из
                    // WebClientConfig — там responseTimeout и read/write-хендлеры, которые
                    // начинают действовать уже на туннеле. Без этой строки залипший прокси
                    // висит без потолка, а вызов идёт внутри транзакции, держащей блокировку
                    // строки платежа. 10 с — с большим запасом: через албанский мост ЮKassa
                    // отвечает за 0.6 с.
                    .connectTimeoutMillis(PROXY_CONNECT_TIMEOUT_MS));
            log.info("YooKassa client initialized with proxy {}:{}, shopId={}",
                    cfg.getProxyHost(), cfg.getProxyPort(), cfg.getShopId());
        } else {
            log.info("YooKassa client initialized without proxy, shopId={}", cfg.getShopId());
        }

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(BASE_URL)
                .defaultHeaders(h -> h.setBasicAuth(cfg.getShopId(), cfg.getSecretKey()))
                .build();
    }

    /**
     * Создать платёж в ЮKassa. Возвращает объект с confirmation_url для редиректа пользователя.
     * {@code savePaymentMethod} — сохранять ли payment_method для будущих рекуррентных
     * списаний (автопродление). Когда {@code savePaymentMethod=true}, после успешной оплаты
     * YooKassa вернёт в {@code payment.payment_method.id} идентификатор карты, который можно
     * использовать в {@link #chargeRecurrent} без участия пользователя.
     */
    public PaymentResponse createPayment(long amountKopeks,
                                         String description,
                                         String returnUrl,
                                         Map<String, String> metadata,
                                         String idempotenceKey,
                                         boolean savePaymentMethod) {
        String amountValue = Money.apiAmount(amountKopeks);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Map.of("value", amountValue, "currency", "RUB"));
        body.put("capture", true);
        body.put("description", description);
        body.put("confirmation", Map.of("type", "redirect", "return_url", returnUrl));
        if (savePaymentMethod) {
            body.put("save_payment_method", true);
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }

        try {
            PaymentResponse response = webClient.post()
                    .uri("/payments")
                    .header("Idempotence-Key", idempotenceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(PaymentResponse.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("YooKassa returned null payment");
            }
            log.info("Created YooKassa payment id={} status={} savePaymentMethod={}",
                    response.getId(), response.getStatus(), savePaymentMethod);
            return response;
        } catch (WebClientResponseException e) {
            // Дампим тело ответа от YooKassa — там JSON с конкретным {error_code, description}.
            // Без этого мы видим только HTTP-статус (403/400/...) и теряем причину.
            log.error("YooKassa createPayment failed status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new IllegalStateException(
                    "YooKassa createPayment failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("YooKassa createPayment failed", e);
            throw new IllegalStateException("YooKassa createPayment failed", e);
        }
    }

    /**
     * Merchant-initiated списание по ранее сохранённой карте. Вызывается планировщиком
     * автопродления за 24ч до истечения подписки. Не требует участия пользователя —
     * {@code payment_method_id} идентифицирует карту, YooKassa авторизует и списывает.
     * <p>
     * Ответ: {@code status} может быть {@code succeeded} (списание прошло),
     * {@code canceled} (карта отклонила / лимит / fraud) или {@code pending} (очень редко,
     * для 3DS-re-authorization — в v1 считаем это failure, не ретраим).
     */
    public PaymentResponse chargeRecurrent(String paymentMethodId,
                                           long amountKopeks,
                                           String description,
                                           String idempotenceKey) {
        String amountValue = Money.apiAmount(amountKopeks);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Map.of("value", amountValue, "currency", "RUB"));
        body.put("capture", true);
        body.put("payment_method_id", paymentMethodId);
        body.put("description", description);

        try {
            PaymentResponse response = webClient.post()
                    .uri("/payments")
                    .header("Idempotence-Key", idempotenceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(PaymentResponse.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("YooKassa returned null for recurrent charge");
            }
            log.info("Recurrent YooKassa charge id={} status={} methodId={}",
                    response.getId(), response.getStatus(), paymentMethodId);
            return response;
        } catch (WebClientResponseException e) {
            log.error("YooKassa chargeRecurrent failed methodId={} status={} body={}",
                    paymentMethodId, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new IllegalStateException(
                    "YooKassa chargeRecurrent failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("YooKassa chargeRecurrent failed methodId={}", paymentMethodId, e);
            throw new IllegalStateException("YooKassa chargeRecurrent failed", e);
        }
    }

    /**
     * Подтверждает (capture) платёж в статусе {@code waiting_for_capture}. Несмотря на то, что
     * мы создаём платежи с {@code capture=true} (auto-capture), YK иногда задерживает их в
     * waiting_for_capture для дополнительных проверок (3DS-callback, fraud-hold). Обычно
     * resolves сам за секунды, но бывает «застревает» — без этого endpoint'а юзер 24h ждёт
     * пока reconciler не пометит FAILED, хотя он реально оплатил.
     * <p>
     * Идемпотентно на стороне YK: если платёж уже {@code succeeded} — вернёт его текущее
     * состояние без ошибки. Если {@code canceled}/нет — вернёт ошибку, мы её ловим и log'аем.
     */
    public PaymentResponse capturePayment(String paymentId) {
        try {
            PaymentResponse response = webClient.post()
                    .uri("/payments/{id}/capture", paymentId)
                    .header("Idempotence-Key", paymentId + "-capture")
                    .contentType(MediaType.APPLICATION_JSON)
                    // Пустое тело = capture полной авторизованной суммы (стандартный путь).
                    // Указывать amount нужно только для частичного capture, чего у нас нет.
                    .bodyValue(Map.of())
                    .retrieve()
                    .bodyToMono(PaymentResponse.class)
                    .block();
            if (response == null) {
                throw new IllegalStateException("YooKassa returned null on capturePayment");
            }
            log.info("YooKassa capturePayment id={} → status={}", paymentId, response.getStatus());
            return response;
        } catch (WebClientResponseException e) {
            log.warn("YooKassa capturePayment failed paymentId={} status={} body={}",
                    paymentId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "YooKassa capturePayment failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.warn("YooKassa capturePayment failed paymentId={}", paymentId, e);
            throw new IllegalStateException("YooKassa capturePayment failed", e);
        }
    }

    /** Re-verification возврата по id (чтобы не доверять webhook'у слепо). */
    public RefundResponse getRefund(String refundId) {
        try {
            RefundResponse response = webClient.get()
                    .uri("/refunds/{id}", refundId)
                    .retrieve()
                    .bodyToMono(RefundResponse.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("YooKassa returned null for refund: " + refundId);
            }
            return response;
        } catch (WebClientResponseException e) {
            log.error("YooKassa getRefund failed refundId={} status={} body={}",
                    refundId, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new IllegalStateException(
                    "YooKassa getRefund failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("YooKassa getRefund failed for {}", refundId, e);
            throw new IllegalStateException("YooKassa getRefund failed", e);
        }
    }

    /**
     * Получить актуальное состояние платежа по id (для re-verification в webhook).
     */
    public PaymentResponse getPayment(String paymentId) {
        try {
            PaymentResponse response = webClient.get()
                    .uri("/payments/{id}", paymentId)
                    .retrieve()
                    .bodyToMono(PaymentResponse.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("YooKassa returned null for payment: " + paymentId);
            }
            return response;
        } catch (WebClientResponseException e) {
            log.error("YooKassa getPayment failed paymentId={} status={} body={}",
                    paymentId, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new IllegalStateException(
                    "YooKassa getPayment failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("YooKassa getPayment failed for {}", paymentId, e);
            throw new IllegalStateException("YooKassa getPayment failed", e);
        }
    }

    // ========== DTO ==========

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentResponse {
        private String id;
        private String status;
        private Boolean paid;
        private Amount amount;
        private Confirmation confirmation;
        private String description;
        private Map<String, String> metadata;
        @JsonProperty("payment_method")
        private PaymentMethod paymentMethod;
        /**
         * Заполняется YooKassa, когда платёж в статусе {@code canceled}: причина отмены
         * (карта отклонена, нет денег, юзер закрыл форму, и т.д.). Используется в
         * {@code payment_failed} аналитике для топ-ошибок.
         * <p>
         * Список значений: https://yookassa.ru/developers/payment-acceptance/after-the-payment/declined-payments
         */
        @JsonProperty("cancellation_details")
        private CancellationDetails cancellationDetails;
    }

    /** {@code payment_method} из ответа YooKassa. Id используется для рекуррентных списаний. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentMethod {
        private String id;
        private String type;
        /** true = карта сохранена и годится для merchant-initiated charges. */
        private Boolean saved;
        private String title;
    }

    /**
     * {@code cancellation_details} из YooKassa-ответа на canceled-платёж.
     * {@code party}: "yoo_money" / "payment_network" / "merchant" — кто инициировал отмену.
     * {@code reason}: машинный код причины ("card_expired", "insufficient_funds", и т.д.).
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CancellationDetails {
        private String party;
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Amount {
        private String value;
        private String currency;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Confirmation {
        private String type;
        @JsonProperty("confirmation_url")
        private String confirmationUrl;
        @JsonProperty("return_url")
        private String returnUrl;
    }

    /** Ответ YooKassa на GET /v3/refunds/{id}. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefundResponse {
        private String id;
        private String status;
        @JsonProperty("payment_id")
        private String paymentId;
        private Amount amount;
        private String description;
    }
}
