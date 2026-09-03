package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class AdminServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AdminService adminService;

    @Autowired
    CustomerRepository userRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;


    @MockitoBean
    ServerStatusService serverStatusService;


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

    @Test
    void getNodeStatuses_mapsPanelNodesAndProbesPort443() {
        AccessProvider.AccessNode ee = new AccessProvider.AccessNode();
        ee.setName("🇪🇪 Estonia");
        ee.setAddress("203.0.113.10");
        ee.setStatus("connected");
        ee.setCoreVersion("25.3.6");
        AccessProvider.AccessNode lv = new AccessProvider.AccessNode();
        lv.setName("🇱🇻 Latvia");
        lv.setAddress("203.0.113.11");
        lv.setStatus("connected");
        when(accessProvider.getNodes()).thenReturn(List.of(ee, lv));
        when(serverStatusService.isPortOpen("203.0.113.10", 443)).thenReturn(true);
        when(serverStatusService.isPortOpen("203.0.113.11", 443)).thenReturn(false);

        List<AdminService.NodeStatus> statuses = adminService.getNodeStatuses();

        assertThat(statuses).hasSize(2);
        assertThat(statuses.get(0).name()).isEqualTo("🇪🇪 Estonia");
        assertThat(statuses.get(0).port443Open()).isTrue();
        // Версия ядра должна доезжать из панели: по ней видно отставшую ноду, которая
        // выглядит здоровой, но не пускает свежих клиентов.
        assertThat(statuses.get(0).coreVersion()).isEqualTo("25.3.6");
        assertThat(statuses.get(1).coreVersion()).isNull();
        // панель считает ноду connected, но :443 закрыт снаружи — это и есть DPI-подозрение
        assertThat(statuses.get(1).status()).isEqualTo("connected");
        assertThat(statuses.get(1).port443Open()).isFalse();
    }

    @Test
    void getNodeStatuses_returnsEmptyWhenPanelReturnsNoNodes() {
        when(accessProvider.getNodes()).thenReturn(List.of());
        assertThat(adminService.getNodeStatuses()).isEmpty();
    }

    // ========================
    // buildStats
    // ========================

    @Test
    void buildStats_shouldCountUsersByCategory() {
        // 2 активных платных, 1 активный триал, 1 disabled
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "tg_bob", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));
        save(user(300L, "carol", "trial_carol", Customer.Status.ACTIVE, LocalDateTime.now().plusHours(12)));
        save(user(400L, "dave", "tg_dave", Customer.Status.DISABLED, LocalDateTime.now().minusDays(1)));

        AdminService.StatsSnapshot s = adminService.buildStats();

        assertThat(s.totalUsers()).isEqualTo(4);
        assertThat(s.activeUsers()).isEqualTo(3); // все трое у кого expiresAt в будущем и статус ACTIVE
        assertThat(s.disabledUsers()).isEqualTo(1);
        assertThat(s.trialOnlyUsers()).isEqualTo(1); // carol
    }

    @Test
    void buildStats_shouldCountNewUsers7dAnd30d() {
        // 1 пользователь старше 30д, 1 между 7 и 30, 1 свежий — после сохранения @PrePersist поставит now().
        // Перезапишем createdAt через вторичное сохранение.
        Customer old = save(user(1L, "old", "tg_old", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(10)));
        old.setCreatedAt(LocalDateTime.now().minusDays(60));
        userRepository.save(old);

        Customer medium = save(user(2L, "med", "tg_med", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(10)));
        medium.setCreatedAt(LocalDateTime.now().minusDays(15));
        userRepository.save(medium);

        save(user(3L, "fresh", "tg_fresh", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(10)));

        AdminService.StatsSnapshot s = adminService.buildStats();

        assertThat(s.newUsersLast7d()).isEqualTo(1);
        assertThat(s.newUsersLast30d()).isEqualTo(2); // medium + fresh
    }

    @Test
    void buildStats_shouldSumRevenueAndGroupByProvider() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "tg_bob", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));

        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 15000, LocalDateTime.now().minusDays(5));
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 40000, LocalDateTime.now().minusDays(3));
        saveSuccessfulPayment(200L, Payment.Provider.CRYPTOBOT, 15000, LocalDateTime.now().minusDays(1));
        saveSuccessfulPayment(200L, Payment.Provider.STARS, 15000, LocalDateTime.now().minusDays(10));
        // Старше 30 дней — не должен попасть
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 99999, LocalDateTime.now().minusDays(40));
        // FAILED — не должен попасть
        savePaymentWithStatus(200L, Payment.Provider.YOOKASSA, 1000, LocalDateTime.now().minusDays(2), Payment.Status.FAILED);

        AdminService.StatsSnapshot s = adminService.buildStats();

        assertThat(s.revenue30dKopeks()).isEqualTo(15000 + 40000 + 15000 + 15000);
        assertThat(s.successfulPayments30d()).isEqualTo(4);
        assertThat(s.revenue30dByProviderKopeks())
                .containsEntry(Payment.Provider.YOOKASSA, 55000L)
                .containsEntry(Payment.Provider.CRYPTOBOT, 15000L)
                .containsEntry(Payment.Provider.STARS, 15000L);
        assertThat(s.paidUniqueUsers()).isEqualTo(2); // alice, bob — у обоих есть SUCCEEDED платежи
    }

    @Test
    void buildStats_shouldReturnZeroesForEmptyDatabase() {
        AdminService.StatsSnapshot s = adminService.buildStats();

        assertThat(s.totalUsers()).isZero();
        assertThat(s.activeUsers()).isZero();
        assertThat(s.paidUniqueUsers()).isZero();
        assertThat(s.revenue30dKopeks()).isZero();
        assertThat(s.revenue30dByProviderKopeks()).isEmpty();
        assertThat(s.trialsTakenAllTime()).isZero();
        assertThat(s.trialsConvertedAllTime()).isZero();
        assertThat(s.trialConversionPct()).isZero();
        assertThat(s.paidUsersExpiredLast30d()).isZero();
        assertThat(s.averagePaymentKopeks30d()).isZero();
    }

    @Test
    void buildStats_shouldComputeTrialConversion() {
        // 4 юзера с триалом: 2 оплатили, 2 нет.
        // 1 юзер БЕЗ триала, оплатил — не должен попасть в воронку триалов.
        save(user(100L, "alice", "trial_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "trial_bob", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(300L, "carol", "trial_carol", Customer.Status.ACTIVE, LocalDateTime.now().plusHours(12)));
        save(user(400L, "dave", "trial_dave", Customer.Status.DISABLED, LocalDateTime.now().minusDays(5)));
        save(user(500L, "eve", "tg_eve", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));

        // alice + bob оплатили после триала; eve оплатила без триала
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 15000, LocalDateTime.now().minusDays(2));
        saveSuccessfulPayment(200L, Payment.Provider.STARS, 15000, LocalDateTime.now().minusDays(1));
        saveSuccessfulPayment(500L, Payment.Provider.YOOKASSA, 40000, LocalDateTime.now().minusDays(3));

        AdminService.StatsSnapshot s = adminService.buildStats();

        assertThat(s.trialsTakenAllTime()).isEqualTo(4);
        assertThat(s.trialsConvertedAllTime()).isEqualTo(2);
        assertThat(s.trialConversionPct()).isEqualTo(50.0);
    }

    @Test
    void buildStats_shouldCountPaidUsersExpiredInLast30d() {
        // 3 юзера DISABLED:
        //  - alice: истёк 5д назад, был платным → попадает
        //  - bob: истёк 60д назад → не попадает (за окном)
        //  - carol: истёк 10д назад, НИКОГДА не платил → не попадает (только триал)
        save(user(100L, "alice", "tg_alice", Customer.Status.DISABLED, LocalDateTime.now().minusDays(5)));
        save(user(200L, "bob", "tg_bob", Customer.Status.DISABLED, LocalDateTime.now().minusDays(60)));
        save(user(300L, "carol", "trial_carol", Customer.Status.DISABLED, LocalDateTime.now().minusDays(10)));

        // только alice и bob имеют SUCCEEDED-платежи
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 15000, LocalDateTime.now().minusDays(35));
        saveSuccessfulPayment(200L, Payment.Provider.YOOKASSA, 15000, LocalDateTime.now().minusDays(90));

        AdminService.StatsSnapshot s = adminService.buildStats();

        assertThat(s.paidUsersExpiredLast30d()).isEqualTo(1); // только alice
    }

    @Test
    void buildStats_shouldComputeAveragePayment() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 15000, LocalDateTime.now().minusDays(5));
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 40000, LocalDateTime.now().minusDays(3));
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 70000, LocalDateTime.now().minusDays(1));

        AdminService.StatsSnapshot s = adminService.buildStats();

        // (15000 + 40000 + 70000) / 3 = 41666
        assertThat(s.averagePaymentKopeks30d()).isEqualTo((15000 + 40000 + 70000) / 3);
    }

    // ========================
    // buildMetrics
    // ========================

    @Test
    void buildMetrics_shouldComputeMrrAcrossDifferentPlans() {
        // 3 активных платных юзера на разных тарифах. MRR суммирует месячный
        // эквивалент каждого: 1мес=150₽/мес, 3мес=400/3≈133₽/мес, 6мес=700/6≈117₽/мес.
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "tg_bob", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(80)));
        save(user(300L, "carol", "tg_carol", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(170)));

        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(200L, Payment.Provider.YOOKASSA, 40000, "month_3", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(300L, Payment.Provider.YOOKASSA, 70000, "month_6", LocalDateTime.now().minusDays(10));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        long expectedMrr = 15000L * 30 / 30 + 40000L * 30 / 90 + 70000L * 30 / 180;
        assertThat(m.mrrKopeks()).isEqualTo(expectedMrr);
        assertThat(m.activePayingUsers()).isEqualTo(3);
        assertThat(m.arpuKopeks()).isEqualTo(expectedMrr / 3);
    }

    @Test
    void buildMetrics_shouldExcludeTrialOnlyUsersFromMrr() {
        // Триальник без оплаты — не должен попасть в MRR/ARPU.
        save(user(100L, "alice", "trial_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusHours(20)));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        assertThat(m.mrrKopeks()).isZero();
        assertThat(m.activePayingUsers()).isZero();
        assertThat(m.arpuKopeks()).isZero();
    }

    @Test
    void buildMetrics_shouldExcludeExpiredPaidUsers() {
        // Платил месяц назад, подписка истекла — DISABLED → не активный платный → не в MRR.
        save(user(100L, "alice", "tg_alice", Customer.Status.DISABLED, LocalDateTime.now().minusDays(5)));
        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(40));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        assertThat(m.activePayingUsers()).isZero();
        assertThat(m.mrrKopeks()).isZero();
    }

    @Test
    void buildMetrics_shouldUseLatestPaymentForMrr() {
        // Юзер апгрейднулся с 1мес на 6мес — MRR должен считаться по последнему тарифу.
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(170)));
        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(40));
        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 70000, "month_6", LocalDateTime.now().minusDays(5));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        // 6мес = 70000 * 30 / 180 ≈ 11666 копеек, а НЕ 15000 (1мес).
        assertThat(m.mrrKopeks()).isEqualTo(70000L * 30 / 180);
    }

    @Test
    void buildMetrics_shouldComputeChurnRateAndLtv() {
        // 4 активных платных + 1 истёкший без продления за последние 30д.
        save(user(100L, "a", "tg_a", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "b", "tg_b", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(300L, "c", "tg_c", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(400L, "d", "tg_d", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(500L, "e", "tg_e", Customer.Status.DISABLED, LocalDateTime.now().minusDays(5)));

        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(200L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(300L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(400L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(500L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(35));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        assertThat(m.activePayingUsers()).isEqualTo(4);
        assertThat(m.paidUsersExpiredLast30d()).isEqualTo(1);
        // churn = 1 / (1 + 4) = 20%
        assertThat(m.churnRate30dPct()).isEqualTo(20.0);
        // LTV = ARPU / 0.20 = ARPU * 5
        assertThat(m.ltvDefined()).isTrue();
        assertThat(m.ltvKopeks()).isEqualTo(m.arpuKopeks() * 5);
    }

    @Test
    void buildMetrics_shouldReturnUndefinedLtvWhenNoChurn() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        assertThat(m.churnRate30dPct()).isZero();
        assertThat(m.ltvDefined()).isFalse();
        assertThat(m.ltvKopeks()).isZero();
    }

    @Test
    void buildMetrics_shouldGroupActiveSubscriptionsByPlan() {
        save(user(100L, "a", "tg_a", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "b", "tg_b", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(300L, "c", "tg_c", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(170)));

        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(200L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(10));
        savePaymentWithPlan(300L, Payment.Provider.YOOKASSA, 70000, "month_6", LocalDateTime.now().minusDays(5));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        assertThat(m.activePayingByPlan()).containsEntry("month_1", 2L);
        assertThat(m.activePayingByPlan()).containsEntry("month_6", 1L);
        // Тарифы без активных — остаются в карте с 0 (для стабильности UI).
        assertThat(m.activePayingByPlan()).containsEntry("month_3", 0L);
        assertThat(m.activePayingByPlan()).containsEntry("month_12", 0L);
    }

    @Test
    void buildMetrics_shouldComputeCohortRetention() {
        // alice — заплатила 60д назад, всё ещё активна (продлилась) → cohort 2 мес назад: retained
        // bob — заплатил 60д назад, истёк → cohort 2 мес назад: churned
        // carol — заплатила 5д назад, активна → cohort текущий месяц: retained
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "tg_bob", Customer.Status.DISABLED, LocalDateTime.now().minusDays(5)));
        save(user(300L, "carol", "tg_carol", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));

        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 70000, "month_6", LocalDateTime.now().minusDays(60));
        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(5));
        savePaymentWithPlan(200L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(60));
        savePaymentWithPlan(300L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(5));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        // 6 месяцев истории — все 6 строк должны быть в выводе (даже пустые).
        assertThat(m.cohorts()).hasSize(6);
        // Суммарно: 3 юзера в когортах, 2 всё ещё платят (alice + carol).
        long totalCohortMembers = m.cohorts().stream()
                .mapToLong(AdminService.CohortRow::cohortSize).sum();
        long totalStillPaying = m.cohorts().stream()
                .mapToLong(AdminService.CohortRow::stillPayingNow).sum();
        assertThat(totalCohortMembers).isEqualTo(3);
        assertThat(totalStillPaying).isEqualTo(2);
    }

    @Test
    void buildMetrics_shouldComputeTrialConversion() {
        save(user(100L, "alice", "trial_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "trial_bob", Customer.Status.ACTIVE, LocalDateTime.now().plusHours(12)));
        savePaymentWithPlan(100L, Payment.Provider.YOOKASSA, 15000, "month_1", LocalDateTime.now().minusDays(5));

        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        assertThat(m.trialsTakenAllTime()).isEqualTo(2);
        assertThat(m.trialsConvertedAllTime()).isEqualTo(1);
        assertThat(m.trialConversionPct()).isEqualTo(50.0);
    }

    @Test
    void buildMetrics_shouldReturnZeroesForEmptyDatabase() {
        AdminService.MetricsSnapshot m = adminService.buildMetrics();

        assertThat(m.mrrKopeks()).isZero();
        assertThat(m.activePayingUsers()).isZero();
        assertThat(m.arpuKopeks()).isZero();
        assertThat(m.churnRate30dPct()).isZero();
        assertThat(m.ltvDefined()).isFalse();
        assertThat(m.ltvKopeks()).isZero();
        assertThat(m.cohorts()).hasSize(6);
        assertThat(m.cohorts()).allSatisfy(row -> assertThat(row.cohortSize()).isZero());
    }

    // ========================
    // findUser
    // ========================

    @Test
    void findUser_shouldReturnCardWithRecent5Payments() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));

        // 7 платежей — ожидаем только 5 самых свежих в карточке (сортировка по createdAt desc)
        for (int i = 1; i <= 7; i++) {
            saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 15000L * i, LocalDateTime.now().minusDays(i));
        }

        Optional<AdminService.UserCard> card = adminService.findUser(100L);

        assertThat(card).isPresent();
        assertThat(card.get().user().getTelegramId()).isEqualTo(100L);
        assertThat(card.get().recentPayments()).hasSize(5);
        // Первый — самый свежий (i=1)
        assertThat(card.get().recentPayments().get(0).getAmountKopeks()).isEqualTo(15000);
    }

    @Test
    void findUser_shouldReturnEmptyWhenNotFound() {
        Optional<AdminService.UserCard> card = adminService.findUser(9999L);
        assertThat(card).isEmpty();
    }

    // ========================
    // extendSubscription
    // ========================

    @Test
    void extendSubscription_shouldStackDaysViaAccessService() {
        // Юзер с 10 днями остатка
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(10)));

        Customer updated = adminService.extendSubscription(100L, 30);

        // ожидаем ~40 дней (10 остаток + 30 новых), чуть-чуть плавает из-за rounding
        assertThat(updated.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(39),
                LocalDateTime.now().plusDays(41));
        verify(accessProvider).enableUserUntil(anyString(), any(), any());
    }

    @Test
    void extendSubscription_shouldReviveDisabledUser() {
        save(user(100L, "alice", "tg_alice", Customer.Status.DISABLED, LocalDateTime.now().minusDays(5)));

        Customer updated = adminService.extendSubscription(100L, 7);

        assertThat(updated.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        // DB expiresAt в прошлом, provider пустой → effectiveCurrent = now → +7 дней
        assertThat(updated.getExpiresAt()).isBetween(
                LocalDateTime.now().plusDays(6),
                LocalDateTime.now().plusDays(8));
    }

    @Test
    void extendSubscription_shouldThrowWhenUserNotFound() {
        assertThatThrownBy(() -> adminService.extendSubscription(9999L, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
        verifyNoInteractions(accessProvider);
    }

    // ========================
    // extendSubscriptionBySubject — multi-form identifier (numeric / @username / username)
    // ========================

    @Test
    void extendBySubject_shouldAcceptNumericId() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));

        Customer updated = adminService.extendSubscriptionBySubject("100", 30);

        assertThat(updated.getTelegramId()).isEqualTo(100L);
        assertThat(updated.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(34));
    }

    @Test
    void extendBySubject_shouldAcceptUsernameWithAt() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));

        Customer updated = adminService.extendSubscriptionBySubject("@alice", 30);

        assertThat(updated.getTelegramId()).isEqualTo(100L);
    }

    @Test
    void extendBySubject_shouldAcceptUsernameWithoutAt() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));

        Customer updated = adminService.extendSubscriptionBySubject("alice", 30);

        assertThat(updated.getTelegramId()).isEqualTo(100L);
    }

    @Test
    void extendBySubject_shouldBeCaseInsensitiveOnUsername() {
        save(user(100L, "AliCe", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));

        // Админ ввёл в любом регистре — должны найти
        Customer updated = adminService.extendSubscriptionBySubject("@ALICE", 30);

        assertThat(updated.getTelegramId()).isEqualTo(100L);
    }

    @Test
    void extendBySubject_shouldThrowWhenUsernameNotFound() {
        assertThatThrownBy(() -> adminService.extendSubscriptionBySubject("@ghost", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@ghost");
        verifyNoInteractions(accessProvider);
    }

    @Test
    void extendBySubject_shouldThrowOnEmptyIdentifier() {
        assertThatThrownBy(() -> adminService.extendSubscriptionBySubject("", 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adminService.extendSubscriptionBySubject(null, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // extendAllActivePayingUsers (массовая компенсация после инцидента)
    // ========================

    @Test
    void extendAllActivePayingUsers_shouldExtendOnlyActivePayingUsers() {
        // 2 active + paying → должны попасть в extend
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "tg_bob", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 15000L, LocalDateTime.now().minusDays(10));
        saveSuccessfulPayment(200L, Payment.Provider.YOOKASSA, 15000L, LocalDateTime.now().minusDays(3));

        // active без платежей (триал) → не должен попасть
        save(user(300L, "carol", "trial_carol", Customer.Status.ACTIVE, LocalDateTime.now().plusHours(12)));

        // disabled с платежом в прошлом → не должен попасть
        save(user(400L, "dave", "tg_dave", Customer.Status.DISABLED, LocalDateTime.now().minusDays(1)));
        saveSuccessfulPayment(400L, Payment.Provider.YOOKASSA, 15000L, LocalDateTime.now().minusDays(40));

        AdminService.BulkExtendResult result = adminService.extendAllActivePayingUsers(3);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();

        // Проверяем, что у обоих active payers дата сместилась — было +20 и +5, должно стать ~+23 и ~+8
        assertThat(userRepository.findByTelegramId(100L)).get()
                .extracting(Customer::getExpiresAt, org.assertj.core.api.InstanceOfAssertFactories.LOCAL_DATE_TIME)
                .isBetween(LocalDateTime.now().plusDays(22), LocalDateTime.now().plusDays(24));
        assertThat(userRepository.findByTelegramId(200L)).get()
                .extracting(Customer::getExpiresAt, org.assertj.core.api.InstanceOfAssertFactories.LOCAL_DATE_TIME)
                .isBetween(LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(9));

        // Триал и disabled — без изменений
        assertThat(userRepository.findByTelegramId(300L)).get()
                .extracting(Customer::getExpiresAt, org.assertj.core.api.InstanceOfAssertFactories.LOCAL_DATE_TIME)
                .isBefore(LocalDateTime.now().plusDays(1));
        assertThat(userRepository.findByTelegramId(400L)).get()
                .extracting(Customer::getStatus)
                .isEqualTo(Customer.Status.DISABLED);
    }

    @Test
    void extendAllActivePayingUsers_shouldReturnZeroCountsOnEmptyBase() {
        // Никого нет — extend не должен падать, должен вернуть нули
        AdminService.BulkExtendResult result = adminService.extendAllActivePayingUsers(3);

        assertThat(result.total()).isZero();
        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        verifyNoInteractions(accessProvider);
    }

    @Test
    void extendAllActivePayingUsers_shouldRejectInvalidDays() {
        assertThatThrownBy(() -> adminService.extendAllActivePayingUsers(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..3650");
        assertThatThrownBy(() -> adminService.extendAllActivePayingUsers(-5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adminService.extendAllActivePayingUsers(3651))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countActivePayingUsers_shouldMatchExtendBaseSize() {
        // 2 active+paying, 1 active без платежей, 1 disabled с платежом — counter должен видеть 2
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));
        save(user(200L, "bob", "tg_bob", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(5)));
        saveSuccessfulPayment(100L, Payment.Provider.YOOKASSA, 15000L, LocalDateTime.now().minusDays(10));
        saveSuccessfulPayment(200L, Payment.Provider.YOOKASSA, 15000L, LocalDateTime.now().minusDays(3));
        save(user(300L, "carol", "trial_carol", Customer.Status.ACTIVE, LocalDateTime.now().plusHours(12)));
        save(user(400L, "dave", "tg_dave", Customer.Status.DISABLED, LocalDateTime.now().minusDays(1)));
        saveSuccessfulPayment(400L, Payment.Provider.YOOKASSA, 15000L, LocalDateTime.now().minusDays(40));

        assertThat(adminService.countActivePayingUsers()).isEqualTo(2);
    }

    // ========================
    // disableSubscription
    // ========================

    @Test
    void disableSubscription_shouldDelegateToAccessService() {
        save(user(100L, "alice", "tg_alice", Customer.Status.ACTIVE, LocalDateTime.now().plusDays(20)));

        boolean result = adminService.disableSubscription(100L);

        assertThat(result).isTrue();
        Optional<Customer> after = userRepository.findByTelegramId(100L);
        assertThat(after).isPresent();
        assertThat(after.get().getStatus()).isEqualTo(Customer.Status.DISABLED);
        verify(accessProvider).disableUser("tg_alice");
    }

    @Test
    void disableSubscription_shouldReturnFalseForUnknownUser() {
        boolean result = adminService.disableSubscription(9999L);

        assertThat(result).isFalse();
        verifyNoInteractions(accessProvider);
    }

    // ========================
    // helpers
    // ========================

    private Customer save(Customer u) {
        return userRepository.save(u);
    }

    private Customer user(Long telegramId, String tgUsername, String providerUsername,
                        Customer.Status status, LocalDateTime expiresAt) {
        return Customer.builder()
                .telegramId(telegramId)
                .telegramUsername(tgUsername)
                .providerUsername(providerUsername)
                .subscriptionLink("https://example.com/sub/" + providerUsername)
                .status(status)
                .expiresAt(expiresAt)
                .build();
    }

    private Payment saveSuccessfulPayment(Long telegramId, Payment.Provider provider,
                                          long amountKopeks, LocalDateTime paidAt) {
        return savePaymentWithStatus(telegramId, provider, amountKopeks, paidAt, Payment.Status.SUCCEEDED);
    }

    private Payment savePaymentWithStatus(Long telegramId, Payment.Provider provider,
                                          long amountKopeks, LocalDateTime paidAt, Payment.Status status) {
        return savePayment(telegramId, provider, amountKopeks, "month_1", status, paidAt);
    }

    /** Вариант helper'а с явным planId — нужен для buildMetrics-тестов, где MRR
     *  считается по дням тарифа (1мес/3мес/6мес/12мес дают разный месячный эквивалент). */
    private Payment savePaymentWithPlan(Long telegramId, Payment.Provider provider,
                                        long amountKopeks, String planId, LocalDateTime paidAt) {
        return savePayment(telegramId, provider, amountKopeks, planId, Payment.Status.SUCCEEDED, paidAt);
    }

    private Payment savePayment(Long telegramId, Payment.Provider provider, long amountKopeks,
                                String planId, Payment.Status status, LocalDateTime paidAt) {
        Payment saved = paymentRepository.save(Payment.builder()
                .telegramId(telegramId)
                .provider(provider)
                .planId(planId)
                .amountKopeks(amountKopeks)
                .status(status)
                .paidAt(paidAt)
                .build());
        // @PrePersist проставил createdAt=now; для некоторых тестов важен сдвиг, имитируем
        saved.setCreatedAt(paidAt);
        return paymentRepository.save(saved);
    }
}
