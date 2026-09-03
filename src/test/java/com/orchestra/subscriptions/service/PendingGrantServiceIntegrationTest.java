package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.PendingGrant;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PendingGrantRepository;
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
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class PendingGrantServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PendingGrantService pendingGrantService;

    @Autowired
    AdminService adminService;

    @Autowired
    PendingGrantRepository pendingGrantRepository;

    @Autowired
    CustomerRepository userRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;



    @BeforeEach
    void setUp() {
        pendingGrantRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mockUser = new AccessProvider.AccessAccount();
        mockUser.setSubscriptionUrl("https://example.com/sub/test");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mockUser);
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
    }

    // ========================
    // createGrant
    // ========================

    @Test
    void createGrant_shouldStoreUsernameLowercase() {
        PendingGrant g = pendingGrantService.createGrant("AliceWonderland", 30, 999L);

        assertThat(g.getId()).isNotNull();
        assertThat(g.getUsername()).isEqualTo("alicewonderland");
        assertThat(g.getDays()).isEqualTo(30);
        assertThat(g.isUsed()).isFalse();
        assertThat(g.getGrantedByTelegramId()).isEqualTo(999L);
    }

    @Test
    void createGrant_shouldStripLeadingAt() {
        PendingGrant g = pendingGrantService.createGrant("@bob", 15, 999L);
        assertThat(g.getUsername()).isEqualTo("bob");
    }

    @Test
    void createGrant_shouldRejectInvalidArgs() {
        assertThatThrownBy(() -> pendingGrantService.createGrant("", 30, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pendingGrantService.createGrant("alice", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pendingGrantService.createGrant("alice", 5000, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // applyIfAny
    // ========================

    @Test
    void applyIfAny_returnsZeroWhenNoGrants() {
        int applied = pendingGrantService.applyIfAny(12345L, "ghost");
        assertThat(applied).isZero();
        assertThat(userRepository.findByTelegramId(12345L)).isEmpty();
    }

    @Test
    void applyIfAny_createsNewUserAndExtends() {
        pendingGrantService.createGrant("winner", 30, 999L);

        int applied = pendingGrantService.applyIfAny(555L, "winner");

        assertThat(applied).isEqualTo(30);
        Optional<Customer> user = userRepository.findByTelegramId(555L);
        assertThat(user).isPresent();
        // Без триала — activatePaidSubscription создаёт tg_* имя.
        assertThat(user.get().getProviderUsername()).startsWith("tg_");
        assertThat(user.get().getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
        assertThat(user.get().getStatus()).isEqualTo(Customer.Status.ACTIVE);
    }

    @Test
    void applyIfAny_marksGrantUsed() {
        PendingGrant g = pendingGrantService.createGrant("winner", 30, 999L);

        pendingGrantService.applyIfAny(555L, "winner");

        PendingGrant reloaded = pendingGrantRepository.findById(g.getId()).orElseThrow();
        assertThat(reloaded.isUsed()).isTrue();
        assertThat(reloaded.getUsedAt()).isNotNull();
        assertThat(reloaded.getUsedByTelegramId()).isEqualTo(555L);
    }

    @Test
    void applyIfAny_isIdempotent() {
        pendingGrantService.createGrant("winner", 30, 999L);

        int first = pendingGrantService.applyIfAny(555L, "winner");
        int second = pendingGrantService.applyIfAny(555L, "winner");

        assertThat(first).isEqualTo(30);
        assertThat(second).isZero();
    }

    @Test
    void applyIfAny_caseInsensitive() {
        pendingGrantService.createGrant("Winner", 30, 999L);

        int applied = pendingGrantService.applyIfAny(555L, "WINNER");

        assertThat(applied).isEqualTo(30);
    }

    @Test
    void applyIfAny_sumsMultipleGrants() {
        pendingGrantService.createGrant("winner", 30, 999L);
        pendingGrantService.createGrant("winner", 7, 999L);

        int applied = pendingGrantService.applyIfAny(555L, "winner");

        assertThat(applied).isEqualTo(37);
        // Подписка стекается: 30 + 7 = 37 дней (минус погрешность времени).
        Customer user = userRepository.findByTelegramId(555L).orElseThrow();
        assertThat(user.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(36));
        // Оба гранта помечены used.
        List<PendingGrant> unused = pendingGrantRepository.findUnusedByUsername("winner");
        assertThat(unused).isEmpty();
    }

    @Test
    void applyIfAny_stacksOnExistingUser() {
        // Юзер уже есть в БД с активной подпиской.
        Customer existing = Customer.builder()
                .telegramId(555L)
                .telegramUsername("winner")
                .providerUsername("tg_winner")
                .subscriptionLink("https://example.com/sub/existing")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build();
        userRepository.save(existing);

        pendingGrantService.createGrant("winner", 30, 999L);
        int applied = pendingGrantService.applyIfAny(555L, "winner");

        assertThat(applied).isEqualTo(30);
        Customer updated = userRepository.findByTelegramId(555L).orElseThrow();
        // 10 + 30 = 40 дней — activatePaidSubscription стекает дни поверх остатка.
        assertThat(updated.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(39));
    }

    @Test
    void applyIfAny_ignoresUsedGrants() {
        PendingGrant g = pendingGrantService.createGrant("winner", 30, 999L);
        g.setUsed(true);
        g.setUsedAt(LocalDateTime.now());
        g.setUsedByTelegramId(999L);
        pendingGrantRepository.save(g);

        int applied = pendingGrantService.applyIfAny(555L, "winner");

        assertThat(applied).isZero();
    }

    @Test
    void applyIfAny_returnsZeroForNullUsername() {
        pendingGrantService.createGrant("winner", 30, 999L);
        // У юзера в Telegram может не быть @username; applyIfAny корректно скипает.
        int applied = pendingGrantService.applyIfAny(555L, null);
        assertThat(applied).isZero();
    }

    // ========================
    // AdminService.grantSubscription
    // ========================

    @Test
    void grantSubscription_immediateForExistingUser() {
        Customer existing = Customer.builder()
                .telegramId(555L)
                .telegramUsername("alice")
                .providerUsername("tg_alice")
                .subscriptionLink("https://example.com/sub/alice")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(5))
                .build();
        userRepository.save(existing);

        AdminService.GrantResult result = adminService.grantSubscription("@alice", 30, 999L);

        assertThat(result.applied()).isTrue();
        assertThat(result.telegramId()).isEqualTo(555L);
        assertThat(result.expiresAt()).isAfter(LocalDateTime.now().plusDays(34));
        // Pending grant НЕ создаётся — extend выполнен напрямую.
        assertThat(pendingGrantRepository.findAll()).isEmpty();
    }

    @Test
    void grantSubscription_pendingForMissingUsername() {
        AdminService.GrantResult result = adminService.grantSubscription("@newbie", 30, 999L);

        assertThat(result.applied()).isFalse();
        assertThat(result.pendingUsername()).isEqualTo("newbie");
        // Юзер не создан, грант сохранён.
        assertThat(userRepository.findAll()).isEmpty();
        assertThat(pendingGrantRepository.findAll()).hasSize(1);
        assertThat(pendingGrantRepository.findAll().get(0).getDays()).isEqualTo(30);
    }

    @Test
    void grantSubscription_rejectsNumericIdForMissingUser() {
        // Numeric id без существующей записи — нельзя резервировать (не на что привязывать).
        assertThatThrownBy(() -> adminService.grantSubscription("100200300", 30, 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
        assertThat(pendingGrantRepository.findAll()).isEmpty();
    }
}
