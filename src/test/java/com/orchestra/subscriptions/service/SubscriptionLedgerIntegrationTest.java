package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Журнал сроков пишется на всех путях, которые двигают оплаченное время, и с правильной
 * причиной. Это фундамент: любые будущие правки сроков сверяются с ним, поэтому важно,
 * чтобы «почему» в журнале было честным — покупка не выглядела подарком и наоборот.
 * <p>
 * Отдельно проверяется, что журнал НЕ вмешивается в бизнес-логику: сроки и статусы после
 * всех операций такие же, как были до его появления.
 */
@SpringBootTest
@Testcontainers
class SubscriptionLedgerIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AccessService accessService;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    SubscriptionLedgerRepository ledgerRepository;

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;
    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;

    private static final long TG_ID = 777000111L;

    @BeforeEach
    void setUp() {
        ledgerRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mUser = new AccessProvider.AccessAccount();
        mUser.setUsername("tg_ledger_" + TG_ID);
        mUser.setSubscriptionUrl("https://example.com/sub/ledgertoken");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mUser);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any())).thenReturn(mUser);
        when(accessProvider.getUser(anyString())).thenReturn(java.util.Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
    }

    private List<SubscriptionLedger> ledger() {
        return ledgerRepository.findAll();
    }

    @Test
    @DisplayName("Триал пишется как TRIAL с полными 7 днями")
    void trialIsRecorded() {
        accessService.createTrialAccess(TG_ID, "ledgertest");

        assertThat(ledger()).singleElement().satisfies(row -> {
            assertThat(row.getKind()).isEqualTo(SubscriptionLedger.Kind.TRIAL);
            assertThat(row.getTelegramId()).isEqualTo(TG_ID);
            assertThat(row.getDeltaSeconds()).isEqualTo(SubscriptionTime.daysToSeconds(7));
            assertThat(row.getBalanceAfterSeconds()).isPositive();
            // Запись обязана быть АДРЕСНОй: без subscription_id журнал человека с
            // несколькими подписками превращается в перемешанный список, по которому нельзя
            // ответить на главный вопрос разбора — «куда делись дни ЭТОЙ подписки».
            assertThat(row.getSubscriptionId())
                    .isEqualTo(subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID)
                            .map(com.orchestra.subscriptions.model.Subscription::getId).orElse(null));
        });
    }

    @Test
    @DisplayName("Покупка и бонус различаются в журнале, дни складываются")
    void purchaseAndBonusAreDistinguished() {
        accessService.activatePaidSubscription(TG_ID, "ledgertest", 30);
        accessService.addDays(TG_ID, "ledgertest", 14, SubscriptionLedger.Kind.BONUS);

        assertThat(ledger()).extracting(SubscriptionLedger::getKind)
                .containsExactlyInAnyOrder(
                        SubscriptionLedger.Kind.PURCHASE, SubscriptionLedger.Kind.BONUS);
        // Бонус стекается поверх покупки: остаток после него больше, чем после неё.
        List<SubscriptionLedger> rows = ledgerRepository
                .findByTelegramIdOrderByOccurredAtDesc(TG_ID, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(rows.get(0).getBalanceAfterSeconds())
                .isGreaterThan(rows.get(1).getBalanceAfterSeconds());
        assertThat(rows.get(0).getDeltaSeconds()).isEqualTo(SubscriptionTime.daysToSeconds(14));
    }

    @Test
    @DisplayName("Урезание срока при возврате пишется отрицательной дельтой")
    void refundIsRecordedAsNegativeDelta() {
        accessService.activatePaidSubscription(TG_ID, "ledgertest", 60);
        ledgerRepository.deleteAll();

        accessService.setExpiry(TG_ID, LocalDateTime.now().plusDays(10));

        assertThat(ledger()).singleElement().satisfies(row -> {
            assertThat(row.getKind()).isEqualTo(SubscriptionLedger.Kind.REFUND);
            assertThat(row.getDeltaSeconds()).isNegative();
            assertThat(row.getBalanceAfterSeconds()).isPositive();
        });
    }

    @Test
    @DisplayName("Досрочное отключение фиксирует, сколько времени сгорело")
    void earlyDisableRecordsLostTime() {
        accessService.activatePaidSubscription(TG_ID, "ledgertest", 30);
        ledgerRepository.deleteAll();

        accessService.disableAccess(TG_ID);

        assertThat(ledger()).singleElement().satisfies(row -> {
            assertThat(row.getKind()).isEqualTo(SubscriptionLedger.Kind.EXPIRE);
            // Отключили за ~30 дней до конца — в журнале видно потерю, а не ноль.
            assertThat(row.getDeltaSeconds()).isNegative();
            assertThat(row.getBalanceAfterSeconds()).isZero();
        });
    }

    @Test
    @DisplayName("Журнал не вмешивается в бизнес-логику: сроки прежние")
    void ledgerDoesNotAlterBehaviour() {
        Customer afterPurchase = accessService.activatePaidSubscription(TG_ID, "ledgertest", 30);
        LocalDateTime expected = LocalDateTime.now().plusDays(30);

        assertThat(afterPurchase.getExpiresAt()).isCloseTo(expected,
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(afterPurchase.getStatus()).isEqualTo(Customer.Status.ACTIVE);
    }
}
