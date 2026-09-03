package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Зеркало подписок: наполнение, перенос существующих юзеров и сверка.
 * <p>
 * Ключевое, что здесь проверяется, — зеркало НЕ меняет поведение сервиса и сходится с
 * основной таблицей. Пока источник правды — {@code customers}, расхождение означает
 * пропущенную запись, а на следующем этапе от этих же данных начнёт зависеть выдача доступа.
 */
@SpringBootTest
@Testcontainers
class SubscriptionRegistryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AccessService accessService;
    @Autowired
    SubscriptionRegistryService registry;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;
    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;

    private static final long TG_ID = 888000222L;

    @Autowired
    com.orchestra.subscriptions.repository.SubscriptionLedgerRepository ledgerRepository;

    @BeforeEach
    void setUp() {
        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mUser = new AccessProvider.AccessAccount();
        mUser.setUsername("tg_reg_" + TG_ID);
        mUser.setSubscriptionUrl("https://example.com/sub/regtoken");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mUser);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any())).thenReturn(mUser);
        when(accessProvider.getUser(anyString())).thenReturn(java.util.Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
    }

    // =====================================================================
    //  Адресация старых записей журнала (разовый бэкфилл)
    // =====================================================================

    /**
     * У человека с одной подпиской безадресная запись принадлежит ей — других не было.
     * Без этого история, записанная до появления колонки, навсегда осталась бы «ничьей», а
     * разбор жалобы «куда делись дни» по ней не собрать.
     */
    @Test
    @DisplayName("Одна подписка — старая запись журнала получает её адрес")
    void orphanLedgerRowIsLinkedWhenPersonHasOneSubscription() {
        Subscription only = givenSubscribedUserWithCleanLedger();
        Long orphanId = saveOrphanLedgerRow(TG_ID, LocalDateTime.now());

        SubscriptionRegistryService.LedgerLinkResult result = registry.linkOrphanLedgerRows();

        assertThat(result.linked()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(ledgerRepository.findById(orphanId).orElseThrow().getSubscriptionId())
                .isEqualTo(only.getId());
    }

    /**
     * Запись о ПЕРВОЙ покупке рождается без адреса и сегодня: зеркальная строка создаётся
     * ПОСЛЕ коммита платёжной транзакции, а журнал пишется внутри неё — на момент записи
     * подписки в таблице ещё нет. Бэкфилл на старте это и чинит; тест держит связку, чтобы
     * при её обрыве история первых покупок не осталась «ничьей» навсегда.
     */
    @Test
    @DisplayName("Запись о первой покупке рождается без адреса и чинится бэкфиллом")
    void firstPurchaseRowIsAddressedByBackfill() {
        accessService.activatePaidSubscription(TG_ID, "regtest", 30);
        Subscription only = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        assertThat(ledgerRepository.findBySubscriptionIdIsNullOrderByOccurredAtAsc())
                .as("первая покупка пишется в журнал раньше, чем появляется зеркальная строка")
                .isNotEmpty();

        registry.linkOrphanLedgerRows();

        assertThat(ledgerRepository.findBySubscriptionIdIsNullOrderByOccurredAtAsc()).isEmpty();
        assertThat(ledgerRepository.findAll())
                .allSatisfy(row -> assertThat(row.getSubscriptionId()).isEqualTo(only.getId()));
    }

    /**
     * Две подписки — адрес НЕ угадываем. В истории есть события подписок, которые с тех пор
     * удалены; приписать их уцелевшей значило бы записать в денежный журнал неправду.
     */
    @Test
    @DisplayName("Две подписки — запись остаётся без адреса, а не приписывается наугад")
    void orphanLedgerRowIsLeftAloneWhenPersonHasSeveralSubscriptions() {
        givenSubscribedUserWithCleanLedger();
        subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID).seq(2).current(false)
                .providerUsername("tg_reg_" + TG_ID + "_s2").subscriptionToken("secondtoken")
                .state(Customer.Status.ACTIVE).balanceSeconds(1000L)
                .createdAt(LocalDateTime.now().minusDays(5))
                .build());
        Long orphanId = saveOrphanLedgerRow(TG_ID, LocalDateTime.now().minusDays(1));

        SubscriptionRegistryService.LedgerLinkResult result = registry.linkOrphanLedgerRows();

        assertThat(result.linked()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(ledgerRepository.findById(orphanId).orElseThrow().getSubscriptionId()).isNull();
    }

    /** Подписка появилась ПОСЛЕ события — значит событие было не про неё. */
    @Test
    @DisplayName("Подписка новее события — адрес не проставляется")
    void orphanLedgerRowOlderThanSubscriptionIsNotLinked() {
        givenSubscribedUserWithCleanLedger();
        Long orphanId = saveOrphanLedgerRow(TG_ID, LocalDateTime.now().minusYears(1));

        assertThat(registry.linkOrphanLedgerRows().linked()).isZero();
        assertThat(ledgerRepository.findById(orphanId).orElseThrow().getSubscriptionId()).isNull();
    }

    /** Повторный прогон не находит работы — бэкфилл можно звать на каждом старте. */
    @Test
    @DisplayName("Повторный прогон идемпотентен")
    void linkingOrphansIsIdempotent() {
        givenSubscribedUserWithCleanLedger();
        saveOrphanLedgerRow(TG_ID, LocalDateTime.now());

        assertThat(registry.linkOrphanLedgerRows().linked()).isEqualTo(1);
        assertThat(registry.linkOrphanLedgerRows().total()).isZero();
    }

    /**
     * Человек с оплаченной подпиской и пустым журналом: сама покупка тоже пишет безадресную
     * запись (см. {@link #firstPurchaseRowIsAddressedByBackfill}), а тестам ниже нужен ровно
     * один контролируемый «сирота».
     */
    private Subscription givenSubscribedUserWithCleanLedger() {
        accessService.activatePaidSubscription(TG_ID, "regtest", 30);
        Subscription only = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        ledgerRepository.deleteAll();
        return only;
    }

    /** Запись журнала без адреса — так выглядит история, сделанная до PR #199. */
    private Long saveOrphanLedgerRow(long telegramId, LocalDateTime occurredAt) {
        return ledgerRepository.save(com.orchestra.subscriptions.model.SubscriptionLedger.builder()
                .telegramId(telegramId)
                .subscriptionId(null)
                .occurredAt(occurredAt)
                .kind(com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PURCHASE)
                .deltaSeconds(SubscriptionTime.daysToSeconds(30))
                .balanceAfterSeconds(SubscriptionTime.daysToSeconds(30))
                .note("история без адреса")
                .build()).getId();
    }

    @Test
    @DisplayName("Покупка заводит зеркальную строку, совпадающую с основной таблицей")
    void purchaseCreatesMatchingMirror() {
        Customer user = accessService.activatePaidSubscription(TG_ID, "regtest", 30);

        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        assertThat(sub.getSeq()).isEqualTo(1);
        assertThat(sub.isCurrent()).isTrue();
        assertThat(sub.getState()).isEqualTo(Customer.Status.ACTIVE);
        // С допуском: Postgres хранит микросекунды, in-memory LocalDateTime — наносекунды.
        assertThat(sub.getExpiresAt()).isCloseTo(user.getExpiresAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(sub.getProviderUsername()).isEqualTo(user.getProviderUsername());
        assertThat(sub.getSubscriptionToken()).isEqualTo(user.getSubscriptionToken());
        // Текущая живёт по дате: замороженный остаток — свойство припаркованной, и держать
        // оба поля разом запрещено CHECK-ограничением в БД.
        assertThat(sub.getBalanceSeconds()).isNull();
        assertThat(sub.getOrigin()).isEqualTo(Subscription.Origin.PAID);
        assertThat(registry.findDivergent()).isEmpty();
    }

    @Test
    @DisplayName("Триал помечается TRIAL, а после оплаты остаётся один ряд — не плодим подписки")
    void trialThenPurchaseKeepsSingleMirror() {
        accessService.createTrialAccess(TG_ID, "regtest");
        Subscription afterTrial = subscriptionRepository
                .findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        assertThat(afterTrial.getOrigin()).isEqualTo(Subscription.Origin.TRIAL);

        accessService.activatePaidSubscription(TG_ID, "regtest", 30);

        assertThat(subscriptionRepository.countByTelegramId(TG_ID)).isEqualTo(1);
        assertThat(registry.findDivergent()).isEmpty();
    }

    @Test
    @DisplayName("Отключение и урезание срока доезжают до зеркала")
    void disableAndSetExpiryAreMirrored() {
        accessService.activatePaidSubscription(TG_ID, "regtest", 30);

        accessService.setExpiry(TG_ID, LocalDateTime.now().plusDays(5));
        assertThat(registry.findDivergent()).isEmpty();

        accessService.disableAccess(TG_ID);
        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        assertThat(sub.getState()).isEqualTo(Customer.Status.DISABLED);
        assertThat(registry.findDivergent()).isEmpty();
    }

    /**
     * Докупка слота и смена ссылки правят состояние в обход основных путей активации.
     * Ревью показало, что без явного зеркалирования они расходились бы молча — а сверка
     * будила бы владельца каждую ночь месяцами, пока у юзера не кончится подписка.
     */
    @Test
    @DisplayName("Докупка устройства и смена ссылки доезжают до зеркала")
    void addonAndLinkRotationAreMirrored() {
        accessService.activatePaidSubscription(TG_ID, "regtest", 30);

        accessService.addDeviceSlot(TG_ID);
        assertThat(registry.findDivergent()).isEmpty();

        AccessProvider.AccessAccount rotated = new AccessProvider.AccessAccount();
        rotated.setUsername("tg_reg_" + TG_ID);
        rotated.setSubscriptionUrl("https://example.com/sub/rotatedtoken");
        when(accessProvider.revokeSubscription(anyString())).thenReturn(rotated);

        accessService.rotateSubscriptionLink(TG_ID);

        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        // Зеркало не должно хранить отозванный токен: AccessProvider на него уже отвечает 404.
        assertThat(sub.getSubscriptionToken()).isEqualTo("rotatedtoken");
        assertThat(registry.findDivergent()).isEmpty();
    }

    /** Сверка обязана чинить расхождение сама — иначе ночной алерт быстро перестают читать. */
    @Test
    @DisplayName("Сверка перезаписывает разошедшееся зеркало, а не только жалуется")
    void reconcileRepairsDivergence() {
        accessService.activatePaidSubscription(TG_ID, "regtest", 30);
        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        // Имитируем пропущенный вызов зеркалирования.
        sub.setDeviceLimit(99);
        subscriptionRepository.save(sub);
        assertThat(registry.findDivergent()).containsExactly(TG_ID);

        assertThat(registry.reconcileDivergent()).isEmpty();
        assertThat(registry.findDivergent()).isEmpty();
    }

    @Test
    @DisplayName("Перенос заводит строки тем, кто появился до зеркала, и идемпотентен")
    void backfillIsIdempotent() {
        // Юзер «из прошлого»: строка есть, зеркала нет.
        userRepository.save(Customer.builder()
                .telegramId(TG_ID).telegramUsername("legacy")
                .providerUsername("tg_legacy_" + TG_ID)
                .subscriptionLink("https://example.com/sub/legacytoken")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(45))
                .build());

        assertThat(registry.backfillMissing()).isEqualTo(1);
        // Повторный прогон ничего не делает — перенос безопасно гонять на каждом деплое.
        assertThat(registry.backfillMissing()).isZero();

        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        assertThat(sub.getProviderUsername()).isEqualTo("tg_legacy_" + TG_ID);
        // Ворота триала проставлены: иначе, припарковав все подписки, человек заново
        // получил бы бесплатный триал.
        Customer refreshed = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(refreshed.getTrialUsedAt()).isNotNull();
        assertThat(refreshed.getSubscriptionSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("Платный юзер с trial_-именем НЕ считается триалом")
    void paidUserWithTrialPrefixIsNotTrial() {
        userRepository.save(Customer.builder()
                .telegramId(TG_ID).telegramUsername("upgraded")
                .providerUsername("trial_upgraded_" + TG_ID)   // префикс остаётся навсегда
                .subscriptionLink("https://example.com/sub/upgradedtoken")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(20))
                .build());
        paymentRepository.save(Payment.builder()
                .telegramId(TG_ID).planId("month_1").amountKopeks(17900L)
                .provider(Payment.Provider.YOOKASSA)
                .status(Payment.Status.SUCCEEDED).paidAt(LocalDateTime.now())
                .build());

        registry.backfillMissing();

        // Пометить его TRIAL значило бы, что следующая покупка «поглотит» его остаток —
        // ровно тот баг, ради которого детект и стал двухчастным.
        assertThat(subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow()
                .getOrigin()).isEqualTo(Subscription.Origin.PAID);
    }

    @Test
    @DisplayName("Перенос проставляет ссылку на подписку и цель в платежах")
    void backfillLinksRelatedRows() {
        userRepository.save(Customer.builder()
                .telegramId(TG_ID).telegramUsername("linked")
                .providerUsername("tg_linked_" + TG_ID)
                .subscriptionLink("https://example.com/sub/linkedtoken")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build());
        paymentRepository.save(Payment.builder()
                .telegramId(TG_ID).planId("month_1").amountKopeks(17900L)
                .provider(Payment.Provider.YOOKASSA)
                .status(Payment.Status.SUCCEEDED).paidAt(LocalDateTime.now())
                .build());

        registry.backfillMissing();

        Long subId = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow().getId();
        assertThat(paymentRepository.findAll()).allSatisfy(p -> {
            assertThat(p.getSubscriptionId()).isEqualTo(subId);
            assertThat(p.getTargetKind()).isEqualTo("EXTEND");
        });
    }
}
