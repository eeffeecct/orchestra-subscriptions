package com.orchestra.subscriptions.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.MultiSubscriptionPolicy;
import com.orchestra.subscriptions.service.SubscriptionTime;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-ЛК при нескольких подписках.
 * <p>
 * ЛК — канал для тех, кто не в Telegram, и переключаться отсюда нельзя сознательно. Но два
 * свойства он обязан держать:
 * <ol>
 *   <li><b>Показывать подписки.</b> Человек, у которого на замороженной подписке лежат его
 *       деньги, не должен видеть экран, где их нет.</li>
 *   <li><b>Не врать про то, куда уйдёт оплата.</b> Отсюда всегда продлевается работающая
 *       подписка — и это написано на экране, а не подразумевается.</li>
 * </ol>
 * Плюс регрессия, ради которой набор и написан: факты подписки читаются из ПОДПИСКИ, а не из
 * зеркала в {@code customers}. Отстающее зеркало здесь означает человека, который копирует со
 * страницы уже мёртвую ссылку.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Как в проде с 12.08.2026.
        "app.subscriptions.read-model-enabled=true",
        "app.subscriptions.sync-enabled=false",
        "app.multi-subscription.enabled=false",
        "app.telegram.public-base-url=https://sub.example",
        "app.plans[0].id=month_1",
        "app.plans[0].name=1 месяц",
        "app.plans[0].days=30",
        "app.plans[0].price-kopeks=17900",
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
class AccountSubscriptionsApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    AppConfig appConfig;
    @Autowired
    MultiSubscriptionPolicy policy;
    @Autowired
    AccountSessionService sessionService;
    @Autowired
    SubscriptionRepository subscriptionRepository;
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
    AccountMagicLinkAsyncSender asyncSender;

    private static final long TG_ID = 100200300L;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Cookie session() {
        return new Cookie(AccountSessionService.COOKIE_NAME, sessionService.issue(TG_ID, 0));
    }

    @Test
    @DisplayName("Страховка: ступени и режим чтения теста доехали до контекста")
    void sanityTestPropertiesArrived() {
        assertThat(appConfig.findDeviceTierOptional("d2")).isPresent();
        assertThat(appConfig.findPlanOptional("month_1")).isPresent();
        assertThat(policy.canOpenNew(TG_ID)).isFalse();
    }

    @Test
    @DisplayName("ЛК показывает все подписки; у замороженной ссылки нет")
    void accountMeListsSubscriptions() throws Exception {
        givenUserWithCurrentSubscription();
        givenParkedSubscription(40);

        mockMvc.perform(get("/api/account/me").cookie(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.length()").value(2))
                .andExpect(jsonPath("$.subscriptions[0].seq").value(1))
                .andExpect(jsonPath("$.subscriptions[0].current").value(true))
                .andExpect(jsonPath("$.subscriptions[0].tierLabel").value("2 устройства"))
                .andExpect(jsonPath("$.subscriptions[1].frozen").value(true))
                .andExpect(jsonPath("$.subscriptions[1].daysLeft").value(40))
                // Ссылка замороженной мертва до включения — отдать её значит послать человека
                // вставлять заведомо нерабочий конфиг.
                .andExpect(jsonPath("$.subscriptions[1].link").doesNotExist());
    }

    /**
     * До этого PR ссылка бралась из {@code customers}, то есть из ЗЕРКАЛА. Зеркало пишется
     * после коммита активации и может отстать — а человек в этот момент копирует со страницы
     * ссылку, которая уже не работает.
     */
    @Test
    @DisplayName("Ссылка берётся из подписки, а не из отставшего зеркала")
    void subscriptionLinkComesFromSubscriptionNotMirror() throws Exception {
        givenUserWithCurrentSubscription();
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        // Зеркало «отстало»: в customers лежит прошлая ссылка.
        user.setSubscriptionLink("https://example.com/sub/staletoken");
        userRepository.save(user);

        mockMvc.perform(get("/api/account/me").cookie(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionLink").value("https://sub.example/sub/firsttoken"));
    }

    /**
     * Экран «оплачено» показывается сразу после возврата из YooKassa и говорит «подписка
     * активна до X» — то есть читает состояние, которое только что изменил вебхук. Отстающее
     * зеркало встретит человека старой датой в самый тревожный для него момент.
     */
    @Test
    @DisplayName("Срок в статусе платежа берётся из подписки, а не из зеркала")
    void paymentStatusExpiryComesFromSubscription() throws Exception {
        givenUserWithCurrentSubscription();
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setExpiresAt(LocalDateTime.now().plusDays(3));   // зеркало отстало
        userRepository.save(user);
        Payment payment = paymentRepository.save(Payment.builder()
                .telegramId(TG_ID).planId("month_1").amountKopeks(17900)
                .provider(Payment.Provider.YOOKASSA).status(Payment.Status.SUCCEEDED)
                .paidAt(LocalDateTime.now())
                .build());

        String body = mockMvc.perform(get("/api/account/payments/" + payment.getId() + "/status")
                        .cookie(session()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long expiresAtEpoch = objectMapper.readTree(body).get("expiresAtEpoch").asLong();
        long fromSubscription = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID)
                .orElseThrow().getExpiresAt().toEpochSecond(java.time.ZoneOffset.UTC);
        assertThat(expiresAtEpoch).isEqualTo(fromSubscription);
    }

    /**
     * Главное обещание раздела про ЛК: отсюда деньги идут в работающую подписку — всегда,
     * при любом флаге и любой ступени. Выбора цели в этом канале нет и не будет, поэтому
     * «отдельную подписку» он не может завести структурно, а не по договорённости.
     */
    @Test
    @DisplayName("Оплата из ЛК всегда продлевает работающую подписку")
    void purchaseFromAccountAlwaysExtendsCurrent() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        givenParkedSubscription(40);
        givenYooKassaAccepts();
        policy.setEnabled(true);
        policy.setAllowedTelegramIds(List.of(TG_ID));
        try {
            mockMvc.perform(post("/api/account/payments/yookassa")
                            .cookie(session())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("planId", "month_1"))))
                    .andExpect(status().isOk());
        } finally {
            policy.setEnabled(false);
            policy.setAllowedTelegramIds(List.of());
        }

        Payment created = paymentRepository.findAll().get(0);
        assertThat(created.getTargetKind()).isEqualTo("EXTEND");
        assertThat(created.getSubscriptionId()).isEqualTo(current.getId());
    }

    // ========================
    //  Данные
    // ========================

    private Subscription givenUserWithCurrentSubscription() {
        userRepository.save(Customer.builder()
                .telegramId(TG_ID)
                .providerUsername("tg_" + TG_ID)
                .email("kabinet@example.com")
                .subscriptionLink("https://example.com/sub/firsttoken")
                .subscriptionToken("firsttoken")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(12))
                .deviceLimit(2)
                .deviceTierId("d2")
                .build());
        return subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID).seq(1).current(true)
                .providerUsername("tg_" + TG_ID)
                .subscriptionLink("https://example.com/sub/firsttoken")
                .subscriptionToken("firsttoken")
                .state(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(12))
                .deviceTierId("d2").deviceLimit(2)
                .build());
    }

    private Subscription givenParkedSubscription(long frozenDays) {
        return subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID).seq(2).current(false)
                .providerUsername("tg_second_" + TG_ID)
                .subscriptionLink("https://example.com/sub/secondtoken")
                .subscriptionToken("secondtoken")
                .state(Customer.Status.ACTIVE)
                .expiresAt(null)
                .balanceSeconds(SubscriptionTime.daysToSeconds(frozenDays))
                .deviceTierId("d1").deviceLimit(1)
                .build());
    }

    private void givenYooKassaAccepts() {
        YooKassaClient.Confirmation confirmation = new YooKassaClient.Confirmation();
        confirmation.setConfirmationUrl("https://yoomoney.example/confirm");
        YooKassaClient.PaymentResponse response = new YooKassaClient.PaymentResponse();
        response.setId("yk-1");
        response.setConfirmation(confirmation);
        when(yooKassaClient.createPayment(anyLong(), anyString(), anyString(), any(), anyString(),
                anyBoolean())).thenReturn(response);
    }
}
