package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.AdminLoginTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Веб-админка end-to-end: вход по ссылке из бота, гейт доступа и сами срезы.
 * <p>
 * Postgres настоящий (Testcontainers) — иначе смысла мало: половина проверяемого здесь это
 * JPQL с {@code CAST(... AS string)}, {@code IN} по enum'ам и {@code GROUP BY}, то есть ровно
 * то, что H2 проглотил бы иначе, чем прод.
 * <p>
 * {@code owner-id} в {@code src/test/resources/application.yml} = 999999999.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminPanelIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long OWNER_ID = 999999999L;
    private static final long PAYING_ID = 111111111L;
    private static final long TRIAL_ID = 222222222L;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    UserDeviceRepository deviceRepository;
    @Autowired
    AdminSessionService sessionService;
    @Autowired
    AdminLoginTokenService loginTokenService;
    @Autowired
    RequestRateLimiter rateLimiter;

    @MockitoBean
    com.orchestra.subscriptions.access.AccessProvider accessProvider;
    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;
    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;
    /*
     * Мок обязателен, а не удобство: настоящий сервис на срезе «Ноды» открывает TCP-сокет к
     * каждому адресу из ответа панели. С живым бином тест ходил бы в сеть и ждал таймаут по
     * две секунды на ноду.
     */
    @MockitoBean
    com.orchestra.subscriptions.service.ServerStatusService serverStatusService;

    @BeforeEach
    void setUp() {
        deviceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();
        rateLimiter.resetForTesting();

        userRepository.save(Customer.builder()
                .telegramId(OWNER_ID).telegramUsername("owner").providerUsername("tg_owner_" + OWNER_ID)
                .status(Customer.Status.ACTIVE).expiresAt(LocalDateTime.now().plusDays(365))
                .sessionVersion(0).build());

        userRepository.save(Customer.builder()
                .telegramId(PAYING_ID).telegramUsername("Payer").providerUsername("tg_payer_" + PAYING_ID)
                .email("payer@example.com").emailVerifiedAt(LocalDateTime.now().minusDays(3))
                .status(Customer.Status.ACTIVE).expiresAt(LocalDateTime.now().plusDays(10))
                .deviceLimit(3).deviceTierId("d3").autorenewEnabled(true).autorenewMethodId("pm_1")
                .sessionVersion(0).build());

        userRepository.save(Customer.builder()
                .telegramId(TRIAL_ID).telegramUsername("triallo").providerUsername("trial_" + TRIAL_ID)
                .status(Customer.Status.DISABLED).expiresAt(LocalDateTime.now().minusDays(2))
                .sessionVersion(0).build());

        paymentRepository.save(Payment.builder()
                .telegramId(PAYING_ID).telegramUsername("Payer").planId("1m")
                .provider(Payment.Provider.YOOKASSA).status(Payment.Status.SUCCEEDED)
                .amountKopeks(17900).paidAt(LocalDateTime.now().minusDays(5))
                .yookassaPaymentId("yk-1").build());
        paymentRepository.save(Payment.builder()
                .telegramId(PAYING_ID).telegramUsername("Payer").planId("3m")
                .provider(Payment.Provider.STARS).status(Payment.Status.SUCCEEDED)
                .amountKopeks(50000).paidAt(LocalDateTime.now().minusDays(1))
                .telegramChargeId("stars-1").build());
        paymentRepository.save(Payment.builder()
                .telegramId(TRIAL_ID).planId("1m")
                .provider(Payment.Provider.CRYPTOBOT).status(Payment.Status.PENDING)
                .amountKopeks(17900).cryptoInvoiceId("cb-1").build());

        subscriptionRepository.save(Subscription.builder()
                .telegramId(PAYING_ID).seq(1).current(true).state(Customer.Status.ACTIVE)
                .origin(Subscription.Origin.PAID).expiresAt(LocalDateTime.now().plusDays(10))
                .providerUsername("tg_payer_" + PAYING_ID).createdAt(LocalDateTime.now().minusDays(30))
                .build());

        deviceRepository.save(UserDevice.builder()
                .telegramId(PAYING_ID).hwid("HWID-0123456789-ABCD").deviceModel("iPhone15,2")
                .deviceOs("iOS").osVersion("18.3").userAgent("клиентское приложение/4.6.0")
                .status(UserDevice.Status.ACTIVE)
                .firstSeenAt(LocalDateTime.now().minusDays(20))
                .lastSeenAt(LocalDateTime.now().minusHours(2))
                .build());
    }

    private Cookie ownerCookie() {
        return new Cookie(AdminSessionService.COOKIE_NAME, sessionService.issue(OWNER_ID, 0));
    }

    // =====================================================================
    //  Вход
    // =====================================================================

    @Test
    void enter_withOwnerToken_setsSessionCookieAndRedirectsToPanel() throws Exception {
        String token = loginTokenService.issue(OWNER_ID);

        MvcResult result = mockMvc.perform(get("/account/admin/enter").param("token", token))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/admin/"))
                .andReturn();

        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(AdminSessionService.COOKIE_NAME + "="))
                .findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");

        String value = setCookie.substring((AdminSessionService.COOKIE_NAME + "=").length(),
                setCookie.indexOf(';'));
        assertThat(sessionService.parse(value).telegramId()).isEqualTo(OWNER_ID);
    }

    @Test
    void enter_withGarbageToken_redirectsToLoginWithoutSession() throws Exception {
        mockMvc.perform(get("/account/admin/enter").param("token", "not-a-real-token"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/admin/login.html?e=token"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void enter_withTokenIssuedForSomeoneElse_isRejected() throws Exception {
        // Токен есть, он валидный, но выпущен не на владельца — доступ всё равно закрыт.
        String foreignToken = loginTokenService.issue(PAYING_ID);

        mockMvc.perform(get("/account/admin/enter").param("token", foreignToken))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account/admin/login.html?e=token"));
    }

    // =====================================================================
    //  Гейт доступа
    // =====================================================================

    @Test
    void api_withoutCookie_is401() throws Exception {
        mockMvc.perform(get("/api/account/admin/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/account/admin/overview"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/account/admin/users/" + PAYING_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_withValidCookieOfNonOwner_is401() throws Exception {
        // Подпись настоящая — отличается только id внутри. Панель обязана смотреть на владельца,
        // а не на «cookie вообще валидна».
        Cookie foreign = new Cookie(AdminSessionService.COOKIE_NAME,
                sessionService.issue(PAYING_ID, 0));

        mockMvc.perform(get("/api/account/admin/users").cookie(foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_withTamperedCookie_is401() throws Exception {
        String valid = sessionService.issue(OWNER_ID, 0);
        String tampered = valid.substring(0, valid.length() - 2) + "00";

        mockMvc.perform(get("/api/account/admin/users")
                        .cookie(new Cookie(AdminSessionService.COOKIE_NAME, tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_afterOwnerSessionVersionBump_is401() throws Exception {
        Cookie issued = ownerCookie();
        mockMvc.perform(get("/api/account/admin/users").cookie(issued))
                .andExpect(status().isOk());

        // «Выйти на всех устройствах» в кабинете инкрементит session_version — панель закрывается.
        Customer owner = userRepository.findByTelegramId(OWNER_ID).orElseThrow();
        owner.setSessionVersion(owner.getSessionVersion() + 1);
        userRepository.save(owner);

        mockMvc.perform(get("/api/account/admin/users").cookie(issued))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    //  Люди
    // =====================================================================

    @Test
    void users_returnsEveryoneWithPaymentAndDeviceAggregates() throws Exception {
        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie())
                        .param("sort", "telegramId").param("dir", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].telegramId").value(PAYING_ID))
                .andExpect(jsonPath("$.items[0].telegramUsername").value("Payer"))
                .andExpect(jsonPath("$.items[0].paymentsCount").value(2))
                .andExpect(jsonPath("$.items[0].paidKopeks").value(67900))
                .andExpect(jsonPath("$.items[0].devices").value(1))
                .andExpect(jsonPath("$.items[0].subscriptions").value(1))
                .andExpect(jsonPath("$.items[0].cardBound").value(true))
                .andExpect(jsonPath("$.items[0].lastSeenAt").isNotEmpty());
    }

    @Test
    void users_searchMatchesIdUsernameAndEmail() throws Exception {
        // Регистр не важен, «@» перед ником допустим, числовой id ищется как подстрока.
        for (String query : new String[]{"@payer", "PAYER", "payer@example.com", String.valueOf(PAYING_ID)}) {
            mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie()).param("q", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].telegramId").value(PAYING_ID));
        }
    }

    @Test
    void users_modeFiltersSplitBaseCorrectly() throws Exception {
        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie()).param("mode", "expired"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].telegramId").value(TRIAL_ID));

        // «Платившие» — только SUCCEEDED: у TRIAL_ID платёж есть, но он PENDING.
        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie()).param("mode", "paying"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].telegramId").value(PAYING_ID));

        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie()).param("mode", "trial"))
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie()).param("mode", "email"))
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie()).param("mode", "autorenew"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void users_searchThatMatchesNobodyReturnsEmptyPage() throws Exception {
        // Пустая страница — штатный ответ поиска. Агрегаты по платежам, устройствам и
        // подпискам получают при этом пустой набор id, и запрос с пустым IN не должен
        // ни падать, ни выполняться.
        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie())
                        .param("q", "нет-такого-человека"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void export_appliesTheSameFiltersAsTheScreen() throws Exception {
        // Файл обязан совпадать с таблицей, из которой его выгрузили.
        String all = mockMvc.perform(get("/api/account/admin/export/devices").cookie(ownerCookie()))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(all).contains("HWID-012").contains("\"iPhone15,2\"");

        String revokedOnly = mockMvc.perform(get("/api/account/admin/export/devices")
                        .cookie(ownerCookie()).param("status", "REVOKED"))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(revokedOnly).doesNotContain("HWID-012");

        String parkedOnly = mockMvc.perform(get("/api/account/admin/export/subscriptions")
                        .cookie(ownerCookie()).param("state", "parked"))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(parkedOnly).doesNotContain("tg_payer_");

        String currentOnly = mockMvc.perform(get("/api/account/admin/export/subscriptions")
                        .cookie(ownerCookie()).param("state", "current"))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(currentOnly).contains("tg_payer_");
    }

    @Test
    void users_unknownSortFieldFallsBackInsteadOfBlowingUp() throws Exception {
        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie())
                        .param("sort", "; DROP TABLE customers --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
        assertThat(userRepository.count()).isEqualTo(3);
    }

    // =====================================================================
    //  Платежи
    // =====================================================================

    @Test
    void payments_filtersByStatusAndProvider() throws Exception {
        mockMvc.perform(get("/api/account/admin/payments").cookie(ownerCookie()))
                .andExpect(jsonPath("$.total").value(3));

        mockMvc.perform(get("/api/account/admin/payments").cookie(ownerCookie())
                        .param("status", "SUCCEEDED"))
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get("/api/account/admin/payments").cookie(ownerCookie())
                        .param("provider", "STARS"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].externalId").value("stars-1"));

        mockMvc.perform(get("/api/account/admin/payments").cookie(ownerCookie()).param("q", "cb-1"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
    }

    // =====================================================================
    //  Карточка
    // =====================================================================

    @Test
    void userCard_collectsEverythingAboutThePerson() throws Exception {
        mockMvc.perform(get("/api/account/admin/users/" + PAYING_ID).cookie(ownerCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.telegramId").value(PAYING_ID))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.subscriptions.length()").value(1))
                .andExpect(jsonPath("$.devices.length()").value(1))
                // HWID показывается только краями — это квази-секрет идентичности устройства.
                .andExpect(jsonPath("$.devices[0].hwid").value("HWID-012…ABCD"))
                .andExpect(jsonPath("$.invitedCount").value(0));
    }

    @Test
    void panelView_returnsWhatProviderKnows() throws Exception {
        com.orchestra.subscriptions.access.AccessProvider.AccessAccount panelUser =
                new com.orchestra.subscriptions.access.AccessProvider.AccessAccount();
        panelUser.setUsername("tg_payer_" + PAYING_ID);
        panelUser.setStatus("active");
        panelUser.setOnlineAt("2026-08-15T09:12:33");
        panelUser.setUsedTraffic(1_073_741_824L);
        org.mockito.Mockito.when(accessProvider.getUser("tg_payer_" + PAYING_ID))
                .thenReturn(java.util.Optional.of(panelUser));

        mockMvc.perform(get("/api/account/admin/users/" + PAYING_ID + "/panel").cookie(ownerCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.onlineAt").value("2026-08-15T09:12:33"))
                .andExpect(jsonPath("$.usedTrafficBytes").value(1073741824L));
    }

    @Test
    void panelView_whenPanelSilentOrAccountMissing_saysUnavailableInsteadOfFailing() throws Exception {
        // getUser отдаёт пустоту и на 404, и на таймаут — карточка обязана открыться в обоих
        // случаях, а не отдать 500.
        mockMvc.perform(get("/api/account/admin/users/" + TRIAL_ID + "/panel").cookie(ownerCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void userCard_forUnknownPerson_is404() throws Exception {
        mockMvc.perform(get("/api/account/admin/users/424242").cookie(ownerCookie()))
                .andExpect(status().isNotFound());
    }

    // =====================================================================
    //  Обзор и выгрузка
    // =====================================================================

    @Test
    void overview_countsRowsOfEveryTable() throws Exception {
        mockMvc.perform(get("/api/account/admin/overview").cookie(ownerCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableRows.customers").value(3))
                .andExpect(jsonPath("$.tableRows.payments").value(3))
                .andExpect(jsonPath("$.tableRows.subscriptions").value(1))
                .andExpect(jsonPath("$.tableRows.user_devices").value(1))
                .andExpect(jsonPath("$.stats.totalUsers").value(3))
                .andExpect(jsonPath("$.devicesSeen24h").value(1))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());
    }

    @Test
    void export_returnsCsvWithHeaderRowFromRecordComponents() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/account/admin/export/users").cookie(ownerCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"orchestra-users.csv\""))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn();

        String csv = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿\"telegramId\";\"telegramUsername\"");
        assertThat(csv.lines().count()).isEqualTo(4); // заголовок + три человека
        assertThat(csv).contains("\"payer@example.com\"");
    }

    @Test
    void export_ofUnknownTable_is400() throws Exception {
        mockMvc.perform(get("/api/account/admin/export/passwords").cookie(ownerCookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_withoutCookie_is401() throws Exception {
        mockMvc.perform(get("/api/account/admin/export/users"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    //  Остальные срезы отвечают и не падают на пустых таблицах
    // =====================================================================

    @Test
    void everyTableEndpointAnswers() throws Exception {
        String[] paths = {"/subscriptions", "/devices", "/ledger", "/referrals", "/promocodes",
                "/promousages", "/grants", "/magiclinks", "/offers"};
        for (String path : paths) {
            mockMvc.perform(get("/api/account/admin" + path).cookie(ownerCookie()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void devices_and_subscriptions_filterByTelegramId() throws Exception {
        mockMvc.perform(get("/api/account/admin/devices").cookie(ownerCookie())
                        .param("telegramId", String.valueOf(PAYING_ID)))
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/account/admin/devices").cookie(ownerCookie())
                        .param("telegramId", String.valueOf(TRIAL_ID)))
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/account/admin/subscriptions").cookie(ownerCookie())
                        .param("state", "parked"))
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/account/admin/subscriptions").cookie(ownerCookie())
                        .param("state", "current"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        mockMvc.perform(get("/api/account/admin/users").cookie(ownerCookie())
                        .param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(com.orchestra.subscriptions.service.AdminPanelService.MAX_PAGE_SIZE));
    }

    // =====================================================================================
    // Ноды
    // =====================================================================================

    /**
     * Ради чего раздел вообще заведён: панель считает ноду живой, а {@code :443} снаружи
     * закрыт. Ни один из двух источников по отдельности этого не показывает, поэтому
     * расхождение обязано доезжать до JSON посчитанным, а не «пусть фронт сам сообразит».
     */
    @Test
    void nodes_flagPortSuspicionAndCarryCoreVersion() throws Exception {
        com.orchestra.subscriptions.access.AccessProvider.AccessNode healthy =
                new com.orchestra.subscriptions.access.AccessProvider.AccessNode();
        healthy.setName("🇪🇪 Estonia");
        healthy.setAddress("203.0.113.10");
        healthy.setStatus("connected");
        healthy.setCoreVersion("25.3.6");

        com.orchestra.subscriptions.access.AccessProvider.AccessNode suspect =
                new com.orchestra.subscriptions.access.AccessProvider.AccessNode();
        suspect.setName("🇱🇻 Latvia");
        suspect.setAddress("203.0.113.11");
        suspect.setStatus("connected");

        org.mockito.Mockito.when(accessProvider.getNodes())
                .thenReturn(java.util.List.of(healthy, suspect));
        org.mockito.Mockito.when(serverStatusService.isPortOpen("203.0.113.10", 443)).thenReturn(true);
        org.mockito.Mockito.when(serverStatusService.isPortOpen("203.0.113.11", 443)).thenReturn(false);
        org.mockito.Mockito.when(serverStatusService.getStatuses()).thenReturn(java.util.List.of(
                new com.orchestra.subscriptions.service.ServerStatusService.ServerStatus(
                        "Estonia", "🇪🇪", true, true, 99.9, 41.0),
                new com.orchestra.subscriptions.service.ServerStatusService.ServerStatus(
                        "Reserve", "🏳", false, false, null, null)));

        mockMvc.perform(get("/api/account/admin/nodes").cookie(ownerCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.connected").value(2))
                .andExpect(jsonPath("$.dpiSuspects").value(1))
                .andExpect(jsonPath("$.nodes[0].coreVersion").value("25.3.6"))
                .andExpect(jsonPath("$.nodes[0].port443Open").value(true))
                .andExpect(jsonPath("$.nodes[0].dpiSuspect").value(false))
                .andExpect(jsonPath("$.nodes[1].dpiSuspect").value(true))
                .andExpect(jsonPath("$.nodes[1].port443Open").value(false))
                // Панель версию не отдала — это null, а не пустая строка: «неизвестно» и
                // «нет версии» на экране выглядят по-разному.
                .andExpect(jsonPath("$.nodes[1].coreVersion").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.monitors[0].up").value(true))
                // known=false обязано доехать как null. Превратить его в false значит показать
                // «недоступен» по ноде, о которой мониторинг просто промолчал.
                .andExpect(jsonPath("$.monitors[1].up").value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * Недоступная панель — это ровно то, зачем владелец открыл экран. Пятисотка в пустое
     * окно вместо ответа скрыла бы диагноз.
     */
    @Test
    void nodes_panelUnreachable_returnsEmptySnapshotNotError() throws Exception {
        org.mockito.Mockito.when(accessProvider.getNodes())
                .thenThrow(new RuntimeException("panel is down"));
        org.mockito.Mockito.when(serverStatusService.getStatuses()).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/account/admin/nodes").cookie(ownerCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.connected").value(0))
                .andExpect(jsonPath("$.dpiSuspects").value(0))
                .andExpect(jsonPath("$.nodes").isEmpty());
    }

    /** Гейт у среза тот же, что у остальных: без owner-cookie ничего не отдаём. */
    @Test
    void nodes_requireOwnerSession() throws Exception {
        mockMvc.perform(get("/api/account/admin/nodes"))
                .andExpect(status().isUnauthorized());
    }
}
