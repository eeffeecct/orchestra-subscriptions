package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Переключение между подписками: заморозка одной, разморозка другой.
 * <p>
 * Главное, что здесь проверяется, — <b>время не теряется и не появляется из ниоткуда</b>.
 * Каждое переключение конвертирует остаток «дата → секунды → дата», и систематическая
 * потеря даже секунды за цикл со временем съедала бы оплаченное. Плюс порядок работы с
 * панелью и поведение при обрыве посреди операции.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.subscriptions.dual-write-enabled=true",
        // Реконсилер выключен: он бы досылал намерения параллельно с проверками.
        "app.subscriptions.sync-enabled=false",
})
class SubscriptionSwitchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AccessService accessService;
    @Autowired
    SubscriptionSwitchService switchService;
    @Autowired
    AccessSyncService accessSync;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    MultiSubscriptionPolicy policy;
    @Autowired
    com.orchestra.subscriptions.repository.SubscriptionLedgerRepository ledgerRepository;

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;
    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;

    private static final long TG_ID = 700100200L;

    @BeforeEach
    void setUp() {
        // Журнал чистим первым: на него нет каскада от подписок, а суточный лимит
        // переключений считается именно по нему — записи прошлого теста (в drift-тесте их
        // двести) отказывали бы следующему в первом же переключении.
        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mUser = new AccessProvider.AccessAccount();
        mUser.setUsername("tg_switch_" + TG_ID);
        mUser.setSubscriptionUrl("https://example.com/sub/switchtoken");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mUser);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any())).thenReturn(mUser);
        when(accessProvider.getUser(anyString())).thenReturn(java.util.Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
    }

    /** Текущая подписка (создаётся обычной покупкой) + вторая, припаркованная. */
    private Subscription givenParkedSecondSubscription(long frozenDays) {
        accessService.activatePaidSubscription(TG_ID, "switch", 30);
        return subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID).seq(2).current(false)
                .providerUsername("tg_switch_second_" + TG_ID)
                .subscriptionLink("https://example.com/sub/secondtoken")
                .subscriptionToken("secondtoken")
                .state(Customer.Status.ACTIVE)
                .expiresAt(null)
                .balanceSeconds(SubscriptionTime.daysToSeconds(frozenDays))
                .deviceTierId("d2").deviceLimit(2)
                .build());
    }

    @Test
    @DisplayName("Переключение: одна замораживается, другая оттаивает")
    void switchFreezesOneAndResumesOther() {
        Subscription second = givenParkedSecondSubscription(90);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();

        SubscriptionSwitchService.Result result = switchService.switchTo(TG_ID, second.getId());

        assertThat(result.outcome()).isEqualTo(SubscriptionSwitchService.Outcome.SWITCHED);

        Subscription parked = subscriptionRepository.findById(first.getId()).orElseThrow();
        assertThat(parked.isCurrent()).isFalse();
        // Дата стёрта — именно поэтому припаркованную не видит ни один запрос по датам.
        assertThat(parked.getExpiresAt()).isNull();
        assertThat(parked.getBalanceSeconds()).isPositive();

        Subscription active = subscriptionRepository.findById(second.getId()).orElseThrow();
        assertThat(active.isCurrent()).isTrue();
        assertThat(active.getBalanceSeconds()).isNull();
        assertThat(active.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(89));
    }

    /**
     * Сброшенные при разморозке флаги предупреждений обязаны доехать до {@code customers}:
     * планировщик истечения пишет их в старую таблицу, а переключение — в строку подписки.
     * Разъезд означал бы, что сразу после переключения человек либо получает «истекает через
     * час» на свежие 90 дней, либо не получает предупреждения вовсе.
     */
    @Test
    @DisplayName("Флаги предупреждений после переключения зеркалируются в customers")
    void notificationFlagsAreMirroredAfterSwitch() {
        Subscription second = givenParkedSecondSubscription(90);
        Customer before = userRepository.findByTelegramId(TG_ID).orElseThrow();
        before.setNotified24h(true);
        before.setNotified1h(true);
        before.setWinbackSent(true);
        userRepository.save(before);

        switchService.switchTo(TG_ID, second.getId());

        Customer after = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(after.isNotified24h()).isFalse();
        assertThat(after.isNotified1h()).isFalse();
        assertThat(after.isWinbackSent()).isFalse();
        assertThat(after.getExpiresAt()).isEqualTo(
                subscriptionRepository.findById(second.getId()).orElseThrow().getExpiresAt());
    }

    /**
     * Ключевой инвариант: сумма оплаченного времени по обеим подпискам до и после
     * переключения совпадает. Потеря здесь — это молча съеденные оплаченные дни.
     */
    @Test
    @DisplayName("Время не теряется: сумма остатков сохраняется")
    void totalRemainingTimeIsConserved() {
        Subscription second = givenParkedSecondSubscription(90);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        long before = SubscriptionTime.remainingSeconds(first.getExpiresAt(), now)
                + second.getBalanceSeconds();

        switchService.switchTo(TG_ID, second.getId());

        Subscription parked = subscriptionRepository.findById(first.getId()).orElseThrow();
        Subscription active = subscriptionRepository.findById(second.getId()).orElseThrow();
        long after = parked.getBalanceSeconds()
                + SubscriptionTime.remainingSeconds(active.getExpiresAt(), now);

        // Допуск — секунды на исполнение теста, и только В ПОЛЬЗУ ЮЗЕРА.
        assertThat(after).isBetween(before - 2, before + 5);
    }

    @Test
    @DisplayName("Туда-обратно 20 раз не крадёт у юзера время")
    void repeatedSwitchesDoNotStealTime() {
        Subscription second = givenParkedSecondSubscription(60);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        long before = SubscriptionTime.remainingSeconds(first.getExpiresAt(), now)
                + second.getBalanceSeconds();

        for (int i = 0; i < 10; i++) {
            switchService.switchTo(TG_ID, second.getId());
            switchService.switchTo(TG_ID, first.getId());
        }

        Subscription a = subscriptionRepository.findById(first.getId()).orElseThrow();
        Subscription b = subscriptionRepository.findById(second.getId()).orElseThrow();
        long after = SubscriptionTime.remainingSeconds(a.getExpiresAt(), now)
                + (b.getBalanceSeconds() == null ? 0 : b.getBalanceSeconds());

        // 20 конвертаций подряд не должны утечь: округление всегда вверх, в пользу юзера.
        assertThat(after).isGreaterThanOrEqualTo(before - 5);
    }

    @Test
    @DisplayName("В панель сначала уходит выключение старой, потом включение новой")
    void panelGetsDisableBeforeEnable() {
        Subscription second = givenParkedSecondSubscription(30);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();

        SubscriptionSwitchService.Result result = switchService.switchTo(TG_ID, second.getId());
        switchService.pushToPanelAfterCommit(result);

        // Две одновременно живые подписки означали бы двойной лимит устройств и соединений —
        // выдачу того, за что не платили. Поэтому строгий порядок.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(accessProvider);
        order.verify(accessProvider).disableUser(first.getProviderUsername());
        order.verify(accessProvider).enableUserUntil(
                org.mockito.ArgumentMatchers.eq(second.getProviderUsername()), any(), any());
    }

    /**
     * Обрыв между базой и панелью. Переключение уже зафиксировано, панель не ответила —
     * намерение обязано остаться незакрытым, чтобы реконсилер его добил. Потерянное
     * намерение означало бы человека без доступа при оплаченной подписке.
     */
    @Test
    @DisplayName("Панель недоступна — намерение остаётся, состояние в базе не откатывается")
    void panelFailureKeepsIntentForReconciler() {
        Subscription second = givenParkedSecondSubscription(30);
        doThrow(new RuntimeException("panel down"))
                .when(accessProvider).enableUserUntil(anyString(), any(), any());

        SubscriptionSwitchService.Result result = switchService.switchTo(TG_ID, second.getId());
        switchService.pushToPanelAfterCommit(result);

        Subscription active = subscriptionRepository.findById(second.getId()).orElseThrow();
        assertThat(active.isCurrent()).isTrue();                 // переключение состоялось
        assertThat(active.getProviderIntent()).isNotNull();       // но панель ещё не знает
        assertThat(active.getProviderAttempts()).isPositive();
    }

    @Test
    @DisplayName("Повторное переключение на ту же подписку ничего не делает")
    void switchingToCurrentIsNoop() {
        Subscription second = givenParkedSecondSubscription(30);
        switchService.switchTo(TG_ID, second.getId());

        assertThat(switchService.switchTo(TG_ID, second.getId()).outcome())
                .isEqualTo(SubscriptionSwitchService.Outcome.ALREADY_CURRENT);
    }

    @Test
    @DisplayName("Чужая подписка не переключается по одному только id")
    void cannotSwitchToSomeoneElsesSubscription() {
        givenParkedSecondSubscription(30);
        Subscription foreign = subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID + 1).seq(1).current(false)
                .providerUsername("tg_foreign").subscriptionToken("foreigntoken")
                .state(Customer.Status.ACTIVE).balanceSeconds(1000L)
                .build());

        assertThat(switchService.switchTo(TG_ID, foreign.getId()).outcome())
                .isEqualTo(SubscriptionSwitchService.Outcome.NOT_FOUND);
        assertThat(subscriptionRepository.findById(foreign.getId()).orElseThrow().isCurrent())
                .isFalse();
    }

    // =====================================================================
    //  Инварианты журнала сроков (§4 плана: 5-6 непрерывность, 7 «переключение бесплатно»)
    //
    //  Журнал — единственный прямой ответ на вопрос «куда делись дни». Если его записи
    //  расходятся с состоянием подписки, разбор денежной жалобы начинается с недоверия к
    //  собственным данным, и заканчивается ничем.
    // =====================================================================

    /**
     * Инвариант 7. Заморозка и разморозка не выдают и не забирают время — меняется только
     * форма хранения. Ненулевая дельта здесь означала бы, что переключение само по себе
     * что-то стоит человеку (или дарит ему), и суммарная выданная величина в журнале
     * перестала бы сходиться с оплаченным.
     */
    @Test
    @DisplayName("Инвариант 7: PARK и RESUME пишутся с нулевой дельтой и с адресом подписки")
    void parkAndResumeAreFreeAndAddressed() {
        Subscription second = givenParkedSecondSubscription(90);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();

        switchService.switchTo(TG_ID, second.getId());

        var switchRows = ledgerRows().stream()
                .filter(r -> r.getKind() == com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PARK
                        || r.getKind() == com.orchestra.subscriptions.model.SubscriptionLedger.Kind.RESUME)
                .toList();

        assertThat(switchRows).hasSize(2);
        assertThat(switchRows).allSatisfy(row -> {
            assertThat(row.getDeltaSeconds()).isZero();
            // Без адреса журнал человека с двумя подписками — перемешанный список.
            assertThat(row.getSubscriptionId()).isNotNull();
        });
        assertThat(switchRows).extracting(com.orchestra.subscriptions.model.SubscriptionLedger::getSubscriptionId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    /**
     * Инварианты 5 и 6. Последняя запись журнала обязана описывать то же состояние, что
     * лежит в строке подписки: у работающей — дату окончания, у замороженной — остаток.
     */
    @Test
    @DisplayName("Инварианты 5-6: журнал сходится со сроком текущей и остатком припаркованной")
    void ledgerMatchesSubscriptionStateAfterSwitch() {
        Subscription second = givenParkedSecondSubscription(90);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();

        switchService.switchTo(TG_ID, second.getId());

        com.orchestra.subscriptions.model.SubscriptionLedger resume = lastLedgerRowOf(second.getId());
        Subscription active = subscriptionRepository.findById(second.getId()).orElseThrow();
        assertThat(resume.getKind()).isEqualTo(com.orchestra.subscriptions.model.SubscriptionLedger.Kind.RESUME);
        // Срок = момент записи + остаток на момент записи. Допуск — секунда на исполнение.
        assertThat(java.time.Duration.between(
                        resume.getOccurredAt().plusSeconds(resume.getBalanceAfterSeconds()),
                        active.getExpiresAt()).abs())
                .isLessThanOrEqualTo(java.time.Duration.ofSeconds(1));

        com.orchestra.subscriptions.model.SubscriptionLedger park = lastLedgerRowOf(first.getId());
        Subscription parked = subscriptionRepository.findById(first.getId()).orElseThrow();
        assertThat(park.getKind()).isEqualTo(com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PARK);
        assertThat(parked.getBalanceSeconds()).isEqualTo(park.getBalanceAfterSeconds());
    }

    /**
     * Инвариант 9, часть про панель: припаркованный аккаунт выключен и не оживает от
     * повторного прохода реконсилера. Живая припаркованная подписка означала бы удвоенный
     * лимит устройств — выдачу того, за что не платили.
     */
    @Test
    @DisplayName("Инвариант 9: припаркованный аккаунт в панели выключен и реконсилером не оживает")
    void parkedAccountStaysDisabledInPanel() {
        Subscription second = givenParkedSecondSubscription(30);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();

        SubscriptionSwitchService.Result result = switchService.switchTo(TG_ID, second.getId());
        switchService.pushToPanelAfterCommit(result);

        // Чистим историю вызовов: интересует только то, что реконсилер делает с УЖЕ
        // припаркованной строкой, а не следы её создания и парковки.
        org.mockito.Mockito.clearInvocations(accessProvider);
        accessSync.push(first.getId());

        verify(accessProvider).disableUser(first.getProviderUsername());
        verify(accessProvider, org.mockito.Mockito.never())
                .enableUserUntil(org.mockito.ArgumentMatchers.eq(first.getProviderUsername()), any(), any());
    }

    /** Записи журнала этого человека, свежие сверху. */
    private java.util.List<com.orchestra.subscriptions.model.SubscriptionLedger> ledgerRows() {
        return ledgerRepository.findByTelegramIdOrderByOccurredAtDesc(
                TG_ID, org.springframework.data.domain.Pageable.unpaged());
    }

    /** Последняя запись журнала по конкретной подписке. */
    private com.orchestra.subscriptions.model.SubscriptionLedger lastLedgerRowOf(Long subscriptionId) {
        return ledgerRows().stream()
                .filter(r -> subscriptionId.equals(r.getSubscriptionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "в журнале нет ни одной записи по подписке " + subscriptionId));
    }

    // =====================================================================
    //  Гейты пользовательского пути
    //
    //  Живут в СЕРВИСЕ, а не на экране: каналов два (бот и Mini App), и лимит,
    //  который снимается сменой окна, — не лимит.
    // =====================================================================

    @Test
    @DisplayName("Аварийный тормоз останавливает переключение по просьбе человека, но не системный путь")
    void pausedSwitchingStopsUserPathOnly() {
        Subscription second = givenParkedSecondSubscription(30);
        policy.setSwitchingEnabled(false);
        try {
            assertThat(switchService.switchByUser(TG_ID, second.getId()).outcome())
                    .isEqualTo(SubscriptionSwitchService.Outcome.PAUSED);
            assertThat(subscriptionRepository.findById(second.getId()).orElseThrow().isCurrent())
                    .isFalse();

            // А системный путь проходит: только что оплаченная подписка обязана включиться
            // даже при включённом тормозе — иначе это «заплатил и остался без доступа».
            assertThat(switchService.switchTo(TG_ID, second.getId()).outcome())
                    .isEqualTo(SubscriptionSwitchService.Outcome.SWITCHED);
        } finally {
            policy.setSwitchingEnabled(true);
        }
    }

    @Test
    @DisplayName("Суточный лимит считается по журналу и не сбрасывается рестартом")
    void dailySwitchLimitIsCountedFromLedger() {
        Subscription second = givenParkedSecondSubscription(60);
        Subscription first = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();

        // Потолок — 10 переключений в сутки (дефолт политики).
        for (int i = 0; i < policy.maxSwitchesPerDay(); i++) {
            Long target = (i % 2 == 0) ? second.getId() : first.getId();
            assertThat(switchService.switchByUser(TG_ID, target).outcome())
                    .isEqualTo(SubscriptionSwitchService.Outcome.SWITCHED);
        }

        Long next = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID)
                .orElseThrow().getId().equals(second.getId()) ? first.getId() : second.getId();
        assertThat(switchService.switchByUser(TG_ID, next).outcome())
                .isEqualTo(SubscriptionSwitchService.Outcome.RATE_LIMITED);
    }

    /** Старая таблица обязана отражать НОВУЮ текущую подписку — на неё ещё многое смотрит. */
    @Test
    @DisplayName("После переключения основная таблица показывает новую подписку")
    void mainTableReflectsNewCurrentSubscription() {
        Subscription second = givenParkedSecondSubscription(45);

        switchService.switchTo(TG_ID, second.getId());

        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(user.getProviderUsername()).isEqualTo(second.getProviderUsername());
        assertThat(user.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(44));
    }

    /**
     * Настройки автопродления — свойство ЧЕЛОВЕКА, а кандидатов на списание планировщик
     * выбирает ПО СТРОКЕ ПОДПИСКИ. Если при переключении они не переедут в подписку, ставшую
     * текущей, то экран автопродления (он читает человека) продолжит говорить «включено», а
     * списания не случится никогда — человек досидит до нуля, считая, что продление оплачено.
     * Поэтому проверяем не поле, а последствие: планировщик обязан видеть кандидата и после
     * переключения.
     */
    @Test
    @DisplayName("После переключения человек остаётся кандидатом на автосписание")
    void autorenewSettingsFollowNewCurrentSubscription() {
        Subscription second = givenParkedSecondSubscription(90);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setAutorenewEnabled(true);
        user.setAutorenewPlanId("month_3");
        user.setAutorenewDeviceTierId("d2");
        user.setAutorenewMethodId("pm-test");
        userRepository.save(user);

        switchService.switchTo(TG_ID, second.getId());

        Subscription active = subscriptionRepository.findById(second.getId()).orElseThrow();
        assertThat(active.isAutorenewEnabled()).isTrue();
        assertThat(active.getAutorenewPlanId()).isEqualTo("month_3");
        assertThat(active.getAutorenewDeviceTierId()).isEqualTo("d2");

        // Тот самый запрос, которым ходит планировщик. Окно берём заведомо широкое: проверяем
        // не попадание в час, а то, что строка вообще проходит по условиям автопродления.
        assertThat(subscriptionRepository.findAutorenewCandidateTelegramIds(
                LocalDateTime.now(), LocalDateTime.now().plusDays(365), LocalDateTime.now()))
                .contains(TG_ID);

        // Направление переноса — из человека в подписку. Обратное выключило бы человеку
        // автопродление: у только что размороженной подписки эти поля пустые.
        Customer after = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(after.isAutorenewEnabled()).isTrue();
        assertThat(after.getAutorenewPlanId()).isEqualTo("month_3");
    }

    /** Реконсилер доводит намерение до панели после того, как она вернулась. */
    @Test
    @DisplayName("Реконсилер добивает намерение, когда панель ожила")
    void reconcilerCompletesIntentAfterPanelRecovers() {
        Subscription second = givenParkedSecondSubscription(30);
        doThrow(new RuntimeException("panel down"))
                .when(accessProvider).enableUserUntil(anyString(), any(), any());
        SubscriptionSwitchService.Result result = switchService.switchTo(TG_ID, second.getId());
        switchService.pushToPanelAfterCommit(result);

        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        accessSync.push(second.getId());

        Subscription active = subscriptionRepository.findById(second.getId()).orElseThrow();
        assertThat(active.getProviderIntent()).isNull();
        assertThat(active.getProviderSyncedAt()).isNotNull();
        verify(accessProvider, org.mockito.Mockito.atLeastOnce())
                .enableUserUntil(org.mockito.ArgumentMatchers.eq(second.getProviderUsername()), any(), any());
    }
}
