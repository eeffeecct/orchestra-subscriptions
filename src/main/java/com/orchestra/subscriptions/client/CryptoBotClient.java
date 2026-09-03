package com.orchestra.subscriptions.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.util.Money;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Клиент для Crypto Pay API (@CryptoBot) — создание и проверка инвойсов в крипте.
 * Повторяет структуру {@link YooKassaClient}: единый WebClient с заранее вшитой аутентификацией,
 * методы createInvoice/getInvoice для создания и ре-верификации, {@code @JsonIgnoreProperties}
 * DTO. Отличия от YooKassa: header-авторизация вместо Basic Auth, и API оборачивает ответы
 * в конверт {@code {ok, result}} — распаковывается приватным помощником {@link #unwrap}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoBotClient {

    private final AppConfig appConfig;
    private final HttpClient timeoutHttpClient;
    private WebClient webClient;

    @PostConstruct
    public void init() {
        AppConfig.CryptoBotConfig cfg = appConfig.getCryptobot();
        if (cfg == null || cfg.getToken() == null || cfg.getToken().isBlank()) {
            // Без токена клиент всё равно собирается (чтобы Spring не падал на старте),
            // но вызовы API будут явно проваливаться с понятной ошибкой.
            this.webClient = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(timeoutHttpClient))
                    .baseUrl("https://pay.crypt.bot/api/")
                    .build();
            log.warn("CryptoBot token is not configured — createInvoice/getInvoice will fail");
            return;
        }
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(timeoutHttpClient))
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Crypto-Pay-API-Token", cfg.getToken())
                .build();
        log.info("CryptoBot client initialized, baseUrl={}", cfg.getBaseUrl());
    }

    /**
     * Создать инвойс. Сумма задаётся в рублях — CryptoBot сам конвертирует в крипту
     * по текущему курсу в момент создания (фиксирует на время жизни инвойса).
     *
     * @param amountKopeks    цена в копейках; в рубли переводит {@link Money#apiAmount(long)}
     * @param description     описание услуги, показывается пользователю в @CryptoBot
     * @param payload         произвольная строка ≤ 4 KB — сюда кладём id из таблицы payments
     * @param acceptedAssets  список активов через запятую, например "USDT,TON,BTC,ETH"
     * @param paidBtnUrl      ссылка на кнопку "Вернуться в бот" после успешной оплаты
     */
    public InvoiceResponse createInvoice(long amountKopeks,
                                         String description,
                                         String payload,
                                         String acceptedAssets,
                                         String paidBtnUrl) {
        String amountValue = Money.apiAmount(amountKopeks);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency_type", "fiat");
        body.put("fiat", "RUB");
        body.put("amount", amountValue);
        body.put("accepted_assets", acceptedAssets);
        body.put("description", description);
        body.put("payload", payload);
        if (paidBtnUrl != null && !paidBtnUrl.isBlank()) {
            body.put("paid_btn_name", "callback");
            body.put("paid_btn_url", paidBtnUrl);
        }

        try {
            Envelope<InvoiceResponse> env = webClient.post()
                    .uri("createInvoice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Envelope<InvoiceResponse>>() {})
                    .block();

            InvoiceResponse result = unwrap(env, "createInvoice");
            log.info("Created CryptoBot invoice id={} amount={} RUB payload={} status={}",
                    result.getInvoiceId(), amountValue, payload, result.getStatus());
            return result;
        } catch (Exception e) {
            log.error("CryptoBot createInvoice failed", e);
            throw new IllegalStateException("CryptoBot createInvoice failed", e);
        }
    }

    /**
     * Получить актуальное состояние инвойса — используется для ре-верификации в webhook,
     * по аналогии с {@link YooKassaClient#getPayment(String)}.
     */
    public InvoiceResponse getInvoice(String invoiceId) {
        try {
            Envelope<InvoicesList> env = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("getInvoices")
                            .queryParam("invoice_ids", invoiceId)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Envelope<InvoicesList>>() {})
                    .block();

            InvoicesList list = unwrap(env, "getInvoices");
            if (list == null || list.getItems() == null || list.getItems().isEmpty()) {
                log.warn("CryptoBot getInvoice returned no items for {}", invoiceId);
                return null;
            }
            return list.getItems().get(0);
        } catch (Exception e) {
            log.error("CryptoBot getInvoice failed for {}", invoiceId, e);
            throw new IllegalStateException("CryptoBot getInvoice failed", e);
        }
    }

    private <T> T unwrap(Envelope<T> env, String op) {
        if (env == null) {
            throw new IllegalStateException("CryptoBot returned null envelope for " + op);
        }
        if (!Boolean.TRUE.equals(env.getOk())) {
            throw new IllegalStateException("CryptoBot " + op + " returned ok=false: error=" + env.getError());
        }
        return env.getResult();
    }

    // ========== DTO ==========

    /** Обёртка {@code {ok, result, error}}, которой CryptoBot оборачивает все ответы. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Envelope<T> {
        private Boolean ok;
        private T result;
        private Map<String, Object> error;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvoicesList {
        private Long count;
        private java.util.List<InvoiceResponse> items;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvoiceResponse {
        @JsonProperty("invoice_id")
        private Long invoiceId;
        private String hash;
        private String status;                 // active | paid | expired
        private String amount;
        @JsonProperty("currency_type")
        private String currencyType;
        private String fiat;
        private String asset;
        @JsonProperty("paid_asset")
        private String paidAsset;
        @JsonProperty("paid_amount")
        private String paidAmount;
        @JsonProperty("paid_fiat_rate")
        private String paidFiatRate;
        @JsonProperty("pay_url")
        private String payUrl;
        @JsonProperty("bot_invoice_url")
        private String botInvoiceUrl;
        @JsonProperty("mini_app_invoice_url")
        private String miniAppInvoiceUrl;
        @JsonProperty("web_app_invoice_url")
        private String webAppInvoiceUrl;
        private String description;
        private String payload;
    }
}
