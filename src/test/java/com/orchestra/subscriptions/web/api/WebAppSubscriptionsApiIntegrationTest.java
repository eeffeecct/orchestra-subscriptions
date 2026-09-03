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
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.MultiSubscriptionPolicy;
import com.orchestra.subscriptions.service.SubscriptionTime;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Подписки в Mini App: список в {@code /api/me}, предпросмотр переключения, активация и
 * цель платежа.
 * <p>
 * Три свойства, ради которых написан набор:
 * <ol>
 *   <li><b>Старый фронт не ломается.</b> Mini App живёт закешированным у людей на телефонах.
 *       Любое переименование поля верхнего уровня — это белый экран у тех, кто не обновился,
 *       и узнаём мы об этом не из логов, а из поддержки.</li>
 *   <li><b>Чужую подписку не видно и не включить.</b> id приезжает из адреса, то есть
 *       подделывается.</li>
 *   <li><b>Протухшая вкладка не переключает подписку, которую человек не видел.</b> Это
 *       класс жалоб «сам ничего не делал, доступ отвалился», который потом не разобрать.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Как в проде с 12.08.2026: подписочные факты читаются из новой таблицы.
        "app.subscriptions.read-model-enabled=true",
        // Реконсилер выключен — он досылал бы намерения в панель параллельно с проверками.
        "app.subscriptions.sync-enabled=false",
        // Флаг ВЫКЛЮЧЕН намеренно: список и переключение обязаны работать и без него —
        // у человека на замороженной подписке лежат его деньги.
        "app.multi-subscription.enabled=false",
        "app.multi-subscription.max-per-user=3",
        // Без публичного адреса ссылка сервиса не строится вовсе, и проверка «человеку отдают
        // ссылку с маршрутизацией» зеленела бы вхолостую на пустой строке.
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
class WebAppSubscriptionsApiIntegrationTest {

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
    SubscriptionRepository subscriptionRepository;
    @Autowired
    SubscriptionLedgerRepository ledgerRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    UserDeviceRepository deviceRepository;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    RequestRateLimiter requestRateLimiter;

    @MockitoBean
    TelegramInitDataValidator validator;
    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    YooKassaClient yooKassaClient;
    @MockitoBean
    CryptoBotClient cryptoBotClient;

    private static final long TG_ID = 100200300L;
    private static final long OTHER_TG_ID = 111222333L;
    private static final String INIT_DATA = "auth_date=1&hash=stub";

    @BeforeEach
    void setUp() {
        deviceRepository.deleteAll();
        ledgerRepository.deleteAll();
        paymentRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        requestRateLimiter.resetForTesting();

        when(validator.validateAndExtractUserId(INIT_DATA)).thenReturn(TG_ID);
        when(validator.validateAndExtractUser(INIT_DATA))
                .thenReturn(new TelegramInitDataValidator.TelegramUser(TG_ID, "tester"));

        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
        when(accessProvider.getUser(anyString())).thenReturn(java.util.Optional.empty());
        AccessProvider.AccessAccount created = new AccessProvider.AccessAccount();
        created.setUsername("tg_" + TG_ID);
        created.setSubscriptionUrl("https://example.com/sub/created");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(created);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any())).thenReturn(created);
    }

    // =====================================================================
    //  Страховка: настройки теста реально доехали
    // =====================================================================

    /**
     * Без этой проверки весь набор мог бы зеленеть вхолостую: не доехавшие ступени сделали бы
     * {@code tierLabel} пустым, а не доехавший флаг молча включил бы другую ветку логики.
     */
    @Test
    @DisplayName("Страховка: ступени и флаги теста доехали до контекста")
    void sanityTestPropertiesArrived() {
        assertThat(appConfig.findDeviceTierOptional("d2")).isPresent();
        assertThat(appConfig.findPlanOptional("month_1")).isPresent();
        assertThat(policy.canOpenNew(TG_ID))
                .as("флаг мультиподписки в этом наборе выключен — это часть проверки")
                .isFalse();
        assertThat(policy.maxPerUser()).isEqualTo(3);
    }

    // =====================================================================
    //  /api/me
    // =====================================================================

    @Test
    @DisplayName("/me сохраняет все прежние поля верхнего уровня")
    void meKeepsEveryLegacyField() throws Exception {
        givenUserWithCurrentSubscription();

        mockMvc.perform(get("/api/me").header("X-Telegram-Init-Data", INIT_DATA))
                .andExpect(status().isOk())
                // Полный список ключей дореформенного ответа. Он здесь перечислен поимённо
                // намеренно: закешированный фронт читает именно их, и «случайно» переименовать
                // любой из них не должно быть возможно молча.
                .andExpect(jsonPath("$.telegramId").value(TG_ID))
                .andExpect(jsonPath("$.hasSubscription").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isTrial").value(true))
                .andExpect(jsonPath("$.expiresAtEpoch").isNumber())
                .andExpect(jsonPath("$.autorenewEnabled").value(false))
                .andExpect(jsonPath("$.autorenewAvailable").value(false))
                .andExpect(jsonPath("$.subscriptionLink").value("https://example.com/sub/firsttoken"))
                .andExpect(jsonPath("$.accessLink").exists())
                .andExpect(jsonPath("$.email").value(""))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.deviceLimit").value(2))
                .andExpect(jsonPath("$.devicesUsed").value(0))
                .andExpect(jsonPath("$.config.botUsername").exists())
                .andExpect(jsonPath("$.config.recurrentEnabled").exists());
    }

    @Test
    @DisplayName("/me отдаёт подписки; у замороженной ссылки нет")
    void meListsSubscriptionsAndHidesFrozenLink() throws Exception {
        givenUserWithCurrentSubscription();
        givenParkedSubscription(40);

        mockMvc.perform(get("/api/me").header("X-Telegram-Init-Data", INIT_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.length()").value(2))
                .andExpect(jsonPath("$.subscriptions[0].seq").value(1))
                .andExpect(jsonPath("$.subscriptions[0].current").value(true))
                .andExpect(jsonPath("$.subscriptions[0].frozen").value(false))
                .andExpect(jsonPath("$.subscriptions[0].live").value(true))
                .andExpect(jsonPath("$.subscriptions[0].tierLabel").value("2 устройства"))
                .andExpect(jsonPath("$.subscriptions[0].tierId").value("d2"))
                .andExpect(jsonPath("$.subscriptions[0].link").isNotEmpty())
                .andExpect(jsonPath("$.subscriptions[1].seq").value(2))
                .andExpect(jsonPath("$.subscriptions[1].frozen").value(true))
                .andExpect(jsonPath("$.subscriptions[1].daysLeft").value(40))
                // Ссылка замороженной подписки мертва до включения: отдать её значит послать
                // человека вставлять в приложение заведомо нерабочий конфиг.
                .andExpect(jsonPath("$.subscriptions[1].link").doesNotExist())
                // Флаг выключен — завести новую нельзя, но смотреть на существующие можно.
                .andExpect(jsonPath("$.multiSub.canOpenNew").value(false))
                .andExpect(jsonPath("$.multiSub.switchingEnabled").value(true))
                .andExpect(jsonPath("$.multiSub.maxPerUser").value(3));
    }

    // =====================================================================
    //  switch-preview
    // =====================================================================

    @Test
    @DisplayName("Предпросмотр называет все последствия и отдаёт точку сверки")
    void previewNamesEveryConsequence() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        Subscription parked = givenParkedSubscription(40);

        mockMvc.perform(get("/api/subscriptions/" + parked.getId() + "/switch-preview")
                        .header("X-Telegram-Init-Data", INIT_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetSeq").value(2))
                .andExpect(jsonPath("$.emptyTarget").value(false))
                .andExpect(jsonPath("$.alreadyCurrent").value(false))
                // Фронту неоткуда взять точку сравнения, кроме как отсюда — без неё защита
                // от протухшей вкладки была бы фикцией.
                .andExpect(jsonPath("$.currentSubscriptionId").value(current.getId()))
                .andExpect(jsonPath("$.willActivate.daysLeft").value(40))
                .andExpect(jsonPath("$.willFreeze.seq").value(1))
                .andExpect(jsonPath("$.linkChanges").value(true));
    }

    @Test
    @DisplayName("Пустая подписка в предпросмотре — отказ, а не кнопка")
    void previewMarksEmptyTarget() throws Exception {
        givenUserWithCurrentSubscription();
        Subscription empty = givenParkedSubscription(0);

        mockMvc.perform(get("/api/subscriptions/" + empty.getId() + "/switch-preview")
                        .header("X-Telegram-Init-Data", INIT_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emptyTarget").value(true));
    }

    @Test
    @DisplayName("Чужую подписку не посмотреть")
    void previewOfForeignSubscriptionIs404() throws Exception {
        givenUserWithCurrentSubscription();
        Subscription foreign = givenForeignSubscription();

        mockMvc.perform(get("/api/subscriptions/" + foreign.getId() + "/switch-preview")
                        .header("X-Telegram-Init-Data", INIT_DATA))
                .andExpect(status().isNotFound());
    }

    // =====================================================================
    //  activate
    // =====================================================================

    @Test
    @DisplayName("Активация переключает и отдаёт свежее состояние целиком")
    void activateSwitchesAndReturnsFreshMe() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        Subscription parked = givenParkedSubscription(40);

        mockMvc.perform(post("/api/subscriptions/" + parked.getId() + "/activate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expect(current.getId())))
                .andExpect(status().isOk())
                // Ответ — тот же payload, что у /me: фронт перерисовывается из одного снимка,
                // иначе на экране окажется новая подписка со старой ссылкой.
                .andExpect(jsonPath("$.subscriptionLink").value("https://example.com/sub/secondtoken"))
                .andExpect(jsonPath("$.subscriptions[1].current").value(true))
                .andExpect(jsonPath("$.subscriptions[0].frozen").value(true));

        Subscription reloadedParked = subscriptionRepository.findById(parked.getId()).orElseThrow();
        Subscription reloadedCurrent = subscriptionRepository.findById(current.getId()).orElseThrow();
        assertThat(reloadedParked.isCurrent()).isTrue();
        assertThat(reloadedParked.getExpiresAt()).isNotNull();
        assertThat(reloadedCurrent.isCurrent()).isFalse();
        // Дни бывшей текущей не сгорели, а замёрзли.
        assertThat(reloadedCurrent.getBalanceSeconds()).isPositive();
    }

    @Test
    @DisplayName("Протухшая вкладка не переключает: 409 и состояние не тронуто")
    void staleExpectationIsRefused() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        Subscription parked = givenParkedSubscription(40);

        // Человек видел на экране «текущая — #2» (например, вкладка провисела сутки), а на
        // сервере текущая по-прежнему #1.
        mockMvc.perform(post("/api/subscriptions/" + parked.getId() + "/activate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expect(parked.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stale_state"));

        assertThat(subscriptionRepository.findById(current.getId()).orElseThrow().isCurrent())
                .as("отказ обязан быть полным: ни одна строка не должна поменяться")
                .isTrue();
        assertThat(subscriptionRepository.findById(parked.getId()).orElseThrow().isCurrent()).isFalse();
    }

    @Test
    @DisplayName("Повторное нажатие на уже включённую подписку — не ошибка")
    void activatingCurrentIsIdempotent() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();

        mockMvc.perform(post("/api/subscriptions/" + current.getId() + "/activate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expect(current.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions[0].current").value(true));
    }

    @Test
    @DisplayName("Пустую подписку не включить: остаться без доступа нельзя одним нажатием")
    void activatingEmptySubscriptionIsRefused() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        Subscription empty = givenParkedSubscription(0);

        mockMvc.perform(post("/api/subscriptions/" + empty.getId() + "/activate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expect(current.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("empty_target"));

        assertThat(subscriptionRepository.findById(current.getId()).orElseThrow().isCurrent()).isTrue();
    }

    @Test
    @DisplayName("Чужую подписку не включить")
    void activatingForeignSubscriptionIs404() throws Exception {
        givenUserWithCurrentSubscription();
        Subscription foreign = givenForeignSubscription();

        mockMvc.perform(post("/api/subscriptions/" + foreign.getId() + "/activate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expect(null)))
                .andExpect(status().isNotFound());

        assertThat(subscriptionRepository.findById(foreign.getId()).orElseThrow().isCurrent())
                .as("чужая строка не должна измениться ни в одном поле")
                .isFalse();
    }

    @Test
    @DisplayName("Активация без тела запроса отклоняется: сверять нечего")
    void activateWithoutBodyIsRejected() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        Subscription parked = givenParkedSubscription(40);

        mockMvc.perform(post("/api/subscriptions/" + parked.getId() + "/activate")
                        .header("X-Telegram-Init-Data", INIT_DATA))
                .andExpect(status().isBadRequest());

        assertThat(subscriptionRepository.findById(current.getId()).orElseThrow().isCurrent()).isTrue();
    }

    @Test
    @DisplayName("Частые нажатия упираются в лимит, а не раскачивают панель")
    void activateIsRateLimited() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        Subscription parked = givenParkedSubscription(40);

        // 10 попыток в минуту — потолок HTTP-пояса. Все они уходят в 409 (ожидание нарочно
        // протухшее), то есть состояние не меняется, но лимит расходуется.
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/subscriptions/" + parked.getId() + "/activate")
                            .header("X-Telegram-Init-Data", INIT_DATA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(expect(parked.getId())))
                    .andExpect(status().isConflict());
        }

        mockMvc.perform(post("/api/subscriptions/" + parked.getId() + "/activate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expect(current.getId())))
                .andExpect(status().isTooManyRequests());
    }

    // =====================================================================
    //  Цель платежа
    // =====================================================================

    @Test
    @DisplayName("Пустая цель = текущая подписка: старый фронт продолжает попадать деньгами")
    void nullTargetLandsInCurrentSubscription() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        givenParkedSubscription(40);
        givenYooKassaAccepts();

        Map<String, Object> body = new HashMap<>();
        body.put("planId", "month_1");
        body.put("deviceTierId", "d2");
        // target НЕ передаётся — ровно так делает закешированный фронт прошлой версии.

        mockMvc.perform(post("/api/payments/yookassa")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        List<Payment> payments = paymentRepository.findAll();
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getSubscriptionId()).isEqualTo(current.getId());
        assertThat(payments.get(0).getTargetKind()).isEqualTo("EXTEND");
    }

    @Test
    @DisplayName("Деньги можно направить в замороженную подписку по её id")
    void explicitTargetLandsInNamedSubscription() throws Exception {
        givenUserWithCurrentSubscription();
        Subscription parked = givenParkedSubscription(40);
        givenYooKassaAccepts();

        mockMvc.perform(post("/api/payments/yookassa")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "planId", "month_1",
                                "deviceTierId", "d2",
                                "target", String.valueOf(parked.getId())))))
                .andExpect(status().isOk());

        assertThat(paymentRepository.findAll().get(0).getSubscriptionId()).isEqualTo(parked.getId());
    }

    /**
     * Контракт, на который опирается строка «Зачислится в:» в Mini App.
     * <p>
     * Фронт отдаёт цель КОНКРЕТНЫМ id, а не литералом {@code "current"}, и вот почему: литерал
     * означает «канал цель не назвал, решай сам», а сервер в этом случае при включённом флаге
     * и другой ступени заводит ОТДЕЛЬНУЮ подписку. Чипс при этом говорит «Подписка #1 ·
     * работает сейчас» — то есть человек читает одно, а деньги уходят в другое. Тест
     * фиксирует, что названная по id подписка продлевается ВСЕГДА, при любом флаге и любой
     * ступени.
     */
    @Test
    @DisplayName("Названная по id текущая подписка продлевается, а не подменяется новой")
    void namedCurrentSubscriptionIsAlwaysExtended() throws Exception {
        Subscription current = givenUserWithCurrentSubscription();
        givenYooKassaAccepts();
        policy.setEnabled(true);
        policy.setAllowedTelegramIds(List.of(TG_ID));
        try {
            mockMvc.perform(post("/api/payments/yookassa")
                            .header("X-Telegram-Init-Data", INIT_DATA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "planId", "month_1",
                                    // Ступень ДРУГАЯ — ровно тот случай, когда «решай сам»
                                    // означало бы «заведи вторую подписку».
                                    "deviceTierId", "d1",
                                    "target", String.valueOf(current.getId())))))
                    .andExpect(status().isOk());
        } finally {
            policy.setEnabled(false);
            policy.setAllowedTelegramIds(List.of());
        }

        Payment created = paymentRepository.findAll().get(0);
        assertThat(created.getTargetKind()).isEqualTo("EXTEND");
        assertThat(created.getSubscriptionId()).isEqualTo(current.getId());
    }

    @Test
    @DisplayName("Непонятная цель — отказ, а не молчаливое зачисление в текущую")
    void garbageTargetIsRefusedBeforeProvider() throws Exception {
        givenUserWithCurrentSubscription();

        mockMvc.perform(post("/api/payments/yookassa")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "planId", "month_1",
                                "deviceTierId", "d2",
                                "target", "nwe"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_target"));

        // Ни счёта у провайдера, ни строки платежа: молчаливая подмена цели — это деньги
        // не в той подписке при правильной надписи на экране.
        verifyNoInteractions(yooKassaClient);
        assertThat(paymentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Новая подписка при выключенном флаге: отказ до обращения к провайдеру")
    void openingNewSubscriptionWhileFlagOffIsRefused() throws Exception {
        givenUserWithCurrentSubscription();

        mockMvc.perform(post("/api/payments/yookassa")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "planId", "month_1",
                                "deviceTierId", "d1",
                                "target", "new"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("MULTI_DISABLED"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verifyNoInteractions(yooKassaClient);
        assertThat(paymentRepository.findAll()).isEmpty();
    }

    // =====================================================================
    //  Данные
    // =====================================================================

    private String expect(Long currentId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("expectedCurrentSubscriptionId", currentId);
        return objectMapper.writeValueAsString(body);
    }

    private Subscription givenUserWithCurrentSubscription() {
        userRepository.save(Customer.builder()
                .telegramId(TG_ID)
                .telegramUsername("tester")
                .providerUsername("tg_" + TG_ID)
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

    private Subscription givenForeignSubscription() {
        userRepository.save(Customer.builder()
                .telegramId(OTHER_TG_ID)
                .providerUsername("tg_" + OTHER_TG_ID)
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build());
        return subscriptionRepository.save(Subscription.builder()
                .telegramId(OTHER_TG_ID).seq(2).current(false)
                .providerUsername("tg_foreign_" + OTHER_TG_ID)
                .subscriptionLink("https://example.com/sub/foreigntoken")
                .subscriptionToken("foreigntoken")
                .state(Customer.Status.ACTIVE)
                .expiresAt(null)
                .balanceSeconds(SubscriptionTime.daysToSeconds(30))
                .deviceTierId("d1").deviceLimit(1)
                .build());
    }

    // =====================================================================
    //  Валидация промокода: лимит попыток
    // =====================================================================

    /**
     * Ручка отдаёт {@code valid} вместе с суммой скидки, то есть работает оракулом: без лимита
     * действующий код находится перебором с любого телеграм-аккаунта. {@code max_uses} этому не
     * мешает — он ограничивает, сколько раз кодом воспользуются, а не сколько раз его проверят.
     */
    @Test
    @DisplayName("Перебор промокодов упирается в лимит попыток")
    void promoValidateIsRateLimited() throws Exception {
        givenUserWithCurrentSubscription();
        String body = "{\"code\":\"NOSUCHCODE\",\"planId\":\"month_1\"}";

        for (int i = 0; i < PromoResponses.VALIDATE_MAX_PER_HOUR; i++) {
            mockMvc.perform(post("/api/promos/validate")
                            .header("X-Telegram-Init-Data", INIT_DATA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false));
        }

        mockMvc.perform(post("/api/promos/validate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"));
    }

    /**
     * Лимит обязан считаться до разбора тела: иначе перебор с заведомо кривым телом идёт мимо
     * счётчика, а ответы 400 и 200 всё равно различают существующий план и промокод.
     */
    @Test
    @DisplayName("Лимит промокода считается раньше валидации тела")
    void promoValidateCountsMalformedBodiesToo() throws Exception {
        givenUserWithCurrentSubscription();

        for (int i = 0; i < PromoResponses.VALIDATE_MAX_PER_HOUR; i++) {
            mockMvc.perform(post("/api/promos/validate")
                            .header("X-Telegram-Init-Data", INIT_DATA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/api/promos/validate")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests());
    }

    private void givenYooKassaAccepts() {
        YooKassaClient.Confirmation confirmation = new YooKassaClient.Confirmation();
        confirmation.setConfirmationUrl("https://yoomoney.example/confirm");
        YooKassaClient.PaymentResponse response = new YooKassaClient.PaymentResponse();
        response.setId("yk-1");
        response.setConfirmation(confirmation);
        when(yooKassaClient.createPayment(anyLong(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(response);
    }
}
