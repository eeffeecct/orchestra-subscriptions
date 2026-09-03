package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.SubscriptionLedger;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionLedgerRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.payment.PaymentDraft;
import com.orchestra.subscriptions.service.payment.PaymentFactory;
import com.orchestra.subscriptions.service.payment.TargetSpec;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Зачисление денег в НЕ-текущую подписку: создание второй подписки и пополнение замороженной.
 * <p>
 * Проверяется главный инвариант этапа — <b>деньги ложатся ровно туда, что написано на строке
 * платежа</b>, и три следствия, каждое из которых уже стоило бы владельцу денег:
 * <ul>
 *   <li>вторая подписка рождается ЗАМОРОЖЕННОЙ и выключенной в панели — иначе у человека на
 *       минуты два живых аккаунта, то есть удвоенный лимит устройств;</li>
 *   <li>повторная обработка того же платежа не создаёт вторую подписку и второй аккаунт;</li>
 *   <li>покупка в замороженную подписку не переназначает автопродление работающей — иначе
 *       следующее списание взяло бы чужую цену и срезало бы лимит устройств.</li>
 * </ul>
 * Флаг мультиподписки здесь ВКЛЮЧЁН — без него все ветки недостижимы, и тест зеленел бы
 * вхолостую (см. {@link #flagIsActuallyOnInThisContext()}).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.multi-subscription.enabled=true",
        "app.multi-subscription.allowed-telegram-ids=808090100",
        "app.multi-subscription.max-per-user=3",
        // Реконсилер выключен: он досылал бы намерения параллельно с проверками панели.
        "app.subscriptions.sync-enabled=false",
        "app.plans[0].id=month_1",
        "app.plans[0].name=1 месяц",
        "app.plans[0].days=30",
        "app.plans[0].price-kopeks=15000",
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
class MultiSubscriptionActivationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    com.orchestra.subscriptions.config.AppConfig appConfig;
    @Autowired
    PaymentProcessingService paymentProcessingService;
    @Autowired
    PaymentFactory paymentFactory;
    @Autowired
    AccessService accessService;
    @Autowired
    SubscriptionSwitchService switchService;
    @Autowired
    MultiSubscriptionPolicy policy;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    SubscriptionLedgerRepository ledgerRepository;
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
    NotificationService notificationService;

    private static final long TG_ID = 808090100L;
    private static final String YK_ID = "yk-multisub";

    @BeforeEach
    void setUp() {
        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount account = new AccessProvider.AccessAccount();
        account.setUsername("tg_" + TG_ID);
        account.setSubscriptionUrl("https://test.example.com/sub/firsttoken");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(account);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any()))
                .thenAnswer(inv -> secondAccount(inv.getArgument(0)));
        when(accessProvider.getUser(anyString())).thenReturn(Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());

    }

    /**
     * Санити-проверка контекста. Ступени и флаг заданы через {@code @TestPropertySource}: если
     * они не доедут, каждая ветка ниже молча уйдёт в «продлить текущую», все ассерты про
     * «текущая не изменилась» останутся верными, и набор зеленел бы, ничего не проверяя.
     */
    @Test
    @DisplayName("Контекст: флаг мультиподписки включён и ступени видны")
    void flagIsActuallyOnInThisContext() {
        assertThat(policy.canOpenNew(TG_ID)).isTrue();
        assertThat(policy.canOpenNew(TG_ID + 1)).isFalse();
        assertThat(appConfig.findDeviceTierOptional("d1")).isPresent();
        assertThat(appConfig.findDeviceTierOptional("d2")).isPresent();
    }

    // =====================================================================
    //  Создание второй подписки
    // =====================================================================

    @Test
    @DisplayName("Другая ступень: появляется вторая подписка, замороженная и выключенная в панели")
    void differentTierOpensSeparateFrozenSubscription() {
        Subscription current = givenActiveSubscriber("d2", 20);
        LocalDateTime currentExpiryBefore = current.getExpiresAt();

        pay("d1");

        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID);
        assertThat(subs).hasSize(2);

        Subscription minted = subs.get(1);
        assertThat(minted.getSeq()).isEqualTo(2);
        assertThat(minted.isCurrent()).as("новая подписка не отбирает доступ у работающей").isFalse();
        assertThat(minted.getExpiresAt()).as("у замороженной даты нет — её дни не идут").isNull();
        assertThat(minted.getBalanceSeconds()).isEqualTo(SubscriptionTime.daysToSeconds(30));
        assertThat(minted.getDeviceTierId()).isEqualTo("d1");
        assertThat(minted.getDeviceLimit()).isEqualTo(1);
        assertThat(minted.getOrigin()).isEqualTo(Subscription.Origin.PAID);
        assertThat(minted.getProviderUsername()).isNotEqualTo(subs.get(0).getProviderUsername());

        // Аккаунт создан активным (иначе AccessProvider не умеет) и тут же выключен — иначе у
        // человека два живых аккаунта и удвоенный лимит устройств.
        verify(accessProvider).disableUser(minted.getProviderUsername());

        // Работающая подписка не тронута ни днями, ни ступенью.
        Subscription untouched = subscriptionRepository.findById(current.getId()).orElseThrow();
        assertThat(untouched.isCurrent()).isTrue();
        assertThat(untouched.getExpiresAt()).isEqualTo(currentExpiryBefore);
        assertThat(untouched.getDeviceTierId()).isEqualTo("d2");
        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getDeviceTierId())
                .as("ступень человека описывает работающую подписку").isEqualTo("d2");

        // Цель проставлена на строке платежа — на этом стоит идемпотентность.
        Payment processed = paymentRepository.findAll().get(0);
        assertThat(processed.getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(processed.getSubscriptionId()).isEqualTo(minted.getId());
        assertThat(processed.getTargetKind()).as("исторический факт «этим платежом открыли подписку»")
                .isEqualTo("SEPARATE");

        // Человеку сказали правду: дни легли в замороженную подписку, а не «активна до …».
        verify(notificationService).sendPaymentIntoOtherSubscription(
                eq(TG_ID), any(), any(), eq(false));
        verify(notificationService, never()).sendPaymentSuccess(eq(TG_ID), any(), any());

        assertThat(ledgerOf(minted.getId()))
                .extracting(SubscriptionLedger::getKind)
                .containsExactly(SubscriptionLedger.Kind.PURCHASE);
    }

    @Test
    @DisplayName("Та же ступень: подписка одна, дни ложатся поверх остатка")
    void sameTierExtendsCurrentSubscription() {
        Subscription current = givenActiveSubscriber("d2", 20);

        pay("d2");

        assertThat(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID)).hasSize(1);
        Subscription after = subscriptionRepository.findById(current.getId()).orElseThrow();
        assertThat(after.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(49));
        verify(notificationService).sendPaymentSuccess(eq(TG_ID), any(), any());
    }

    @Test
    @DisplayName("Повтор активации: второй подписки и второго аккаунта не появляется")
    void repeatedActivationDoesNotMintTwice() {
        givenActiveSubscriber("d2", 20);
        pay("d1");

        Payment payment = paymentRepository.findAll().get(0);
        Subscription minted = subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID).get(1);
        long balanceAfterFirst = minted.getBalanceSeconds();

        // Имитируем догон реконсилером активации, которая «не закрылась»: цель на строке уже
        // материализована, и повтор обязан пойти веткой «зачислить в существующую».
        payment.setStatus(Payment.Status.PENDING);
        paymentRepository.save(payment);
        paymentProcessingService.handleSucceeded(YK_ID);

        assertThat(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .as("второй подписки не появилось").hasSize(2);
        verify(accessProvider, times(1))
                .createUser(eq(minted.getProviderUsername()), anyInt(), anyLong(), any());
        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getSubscriptionSeq())
                .as("номер подписки не сгорел").isEqualTo(2);
        assertThat(subscriptionRepository.findById(minted.getId()).orElseThrow().getBalanceSeconds())
                .isEqualTo(balanceAfterFirst + SubscriptionTime.daysToSeconds(30));
    }

    @Test
    @DisplayName("Живой текущей подписки нет: новая включается сразу, человек не остаётся без доступа")
    void withoutLiveCurrentTheNewSubscriptionIsTurnedOn() {
        Subscription expired = givenActiveSubscriber("d2", 20);
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        expired.setState(Customer.Status.DISABLED);
        subscriptionRepository.save(expired);

        pay("d1");

        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID);
        Subscription minted = subs.get(1);
        assertThat(minted.isCurrent()).isTrue();
        assertThat(minted.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
        assertThat(minted.getBalanceSeconds()).isNull();
        assertThat(subscriptionRepository.findById(expired.getId()).orElseThrow().isCurrent()).isFalse();
        // И человеку сказали именно это: подписка включена, вот новая ссылка. Текст «она
        // заморожена, Ваш доступ работает» здесь был бы ложью дважды.
        verify(notificationService).sendPaymentIntoOtherSubscription(
                eq(TG_ID), any(), any(), eq(true));
    }

    /**
     * Регресс-щит. Счёт, выставленный ДО появления подписки (первая покупка), человек может
     * оплатить уже ПОСЛЕ того, как подписка появилась: со второй попытки, после триала, догоном
     * реконсилера. Такой платёж обязан продлить работающую подписку — иначе деньги замёрзли бы
     * в новой, и произошло бы это при полностью выключенном флаге мультиподписки.
     */
    @Test
    @DisplayName("Зависший счёт первой покупки продлевает работающую подписку, а не плодит вторую")
    void stalePendingOfFirstPurchaseExtendsCurrent() {
        // Счёт выставлен, когда подписки ещё не было.
        Payment payment = paymentFactory.create(draft("d2"), TargetSpec.current());
        assertThat(payment.getTargetKind()).isEqualTo("NEW");
        assertThat(payment.getSubscriptionId()).isNull();
        payment.setYookassaPaymentId(YK_ID);
        paymentRepository.save(payment);
        when(yooKassaClient.getPayment(YK_ID)).thenReturn(succeeded());

        // А к моменту оплаты подписка уже есть.
        Subscription current = givenActiveSubscriber("d2", 20);

        paymentProcessingService.handleSucceeded(YK_ID);

        assertThat(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .as("второй подписки быть не должно").hasSize(1);
        assertThat(subscriptionRepository.findById(current.getId()).orElseThrow().getExpiresAt())
                .as("дни легли поверх остатка работающей подписки")
                .isAfter(LocalDateTime.now().plusDays(49));
        verify(notificationService).sendPaymentSuccess(eq(TG_ID), any(), any());
    }

    // =====================================================================
    //  Пополнение замороженной подписки
    // =====================================================================

    @Test
    @DisplayName("Оплата замороженной: дни в остаток, панель не трогаем, флаги предупреждений сброшены")
    void depositIntoParkedDoesNotTouchPanel() {
        givenActiveSubscriber("d2", 20);
        Subscription parked = givenParkedSubscription(10);
        parked.setNotified24h(true);
        parked.setNotified1h(true);
        parked.setWinbackSent(true);
        parked.setState(Customer.Status.DISABLED);
        subscriptionRepository.save(parked);

        payInto(parked, "d1");

        Subscription after = subscriptionRepository.findById(parked.getId()).orElseThrow();
        assertThat(after.getBalanceSeconds()).isEqualTo(SubscriptionTime.daysToSeconds(40));
        assertThat(after.getExpiresAt()).isNull();
        assertThat(after.isCurrent()).isFalse();
        assertThat(after.getState()).as("оплата отменяет отключение по истечении")
                .isEqualTo(Customer.Status.ACTIVE);
        assertThat(after.isNotified24h()).isFalse();
        assertThat(after.isNotified1h()).isFalse();
        assertThat(after.isWinbackSent()).isFalse();
        assertThat(after.getDeviceTierId()).isEqualTo("d1");

        // Ни одного включения в панели: у замороженной подписки живого аккаунта быть не должно.
        verify(accessProvider, never()).enableUserUntil(eq(after.getProviderUsername()), any(), any());
        verify(accessProvider, never()).createUser(eq(after.getProviderUsername()), anyInt(), anyLong(), any());

        // Работающая подписка не получила ни дня.
        Subscription current = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        assertThat(current.getExpiresAt()).isBefore(LocalDateTime.now().plusDays(21));
    }

    @Test
    @DisplayName("Оплата замороженной не переназначает автопродление работающей подписки")
    void depositIntoParkedDoesNotRepointAutorenew() {
        givenActiveSubscriber("d2", 20);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setAutorenewMethodId("pm_saved");
        user.setAutorenewEnabled(true);
        user.setAutorenewPlanId("month_1");
        user.setAutorenewDeviceTierId("d2");
        userRepository.save(user);
        Subscription parked = givenParkedSubscription(10);

        payInto(parked, "d1");

        Customer after = userRepository.findByTelegramId(TG_ID).orElseThrow();
        assertThat(after.getAutorenewDeviceTierId())
                .as("иначе следующее списание срежет лимит устройств работающей подписки")
                .isEqualTo("d2");
        assertThat(after.getAutorenewPlanId()).isEqualTo("month_1");
        assertThat(after.isAutorenewEnabled()).isTrue();
    }

    /**
     * Инвариант 18: платёж кредитует подписку, записанную на его строке, даже если между
     * оплатой и вебхуком человек переключился на другую. Иначе деньги приземлялись бы не туда,
     * куда человек платил, и заметить это можно было бы только по жалобе.
     */
    @Test
    @DisplayName("Деньги ложатся туда, что написано на строке, даже после переключения")
    void moneyLandsWhereTheRowSays() {
        Subscription first = givenActiveSubscriber("d2", 20);
        Subscription second = givenParkedSubscription(10);

        // Счёт выставлен на работающую сейчас подписку…
        Payment payment = paymentFactory.create(draft("d2"), TargetSpec.current());
        assertThat(payment.getSubscriptionId()).isEqualTo(first.getId());
        payment.setYookassaPaymentId(YK_ID);
        paymentRepository.save(payment);
        when(yooKassaClient.getPayment(YK_ID)).thenReturn(succeeded());

        // …а до прихода вебхука человек переключился на вторую.
        switchService.switchTo(TG_ID, second.getId());

        paymentProcessingService.handleSucceeded(YK_ID);

        Subscription paidInto = subscriptionRepository.findById(first.getId()).orElseThrow();
        assertThat(paidInto.isCurrent()).as("она уже не текущая").isFalse();
        assertThat(paidInto.getBalanceSeconds())
                .as("дни легли в подписку, за которую платили")
                .isGreaterThan(SubscriptionTime.daysToSeconds(49));
        assertThat(subscriptionRepository.findById(second.getId()).orElseThrow().getExpiresAt())
                .as("подписка, ставшая текущей, чужих дней не получила")
                .isBefore(LocalDateTime.now().plusDays(11));
    }

    // ===== helpers =====

    /** Человек с работающей подпиской — состояние подавляющего большинства плательщиков. */
    private Subscription givenActiveSubscriber(String tierId, int days) {
        accessService.activatePaidSubscription(TG_ID, "multisub", days,
                appConfig.findDeviceTierOptional(tierId).orElseThrow());
        return subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
    }

    private Subscription givenParkedSubscription(int frozenDays) {
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setSubscriptionSeq(2);
        userRepository.save(user);
        return subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID)
                .seq(2)
                .current(false)
                .providerUsername("tg_multisub_" + TG_ID + "_s2")
                .subscriptionLink("https://test.example.com/sub/secondtoken")
                .subscriptionToken("secondtoken")
                .state(Customer.Status.ACTIVE)
                .expiresAt(null)
                .balanceSeconds(SubscriptionTime.daysToSeconds(frozenDays))
                .deviceTierId("d2")
                .deviceLimit(2)
                .origin(Subscription.Origin.PAID)
                .providerAttempts(0)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** Покупка ступени {@code tierId} обычным путём: цель определяет резолвер. */
    private void pay(String tierId) {
        Payment payment = paymentFactory.create(draft(tierId), TargetSpec.current());
        payment.setYookassaPaymentId(YK_ID);
        paymentRepository.save(payment);
        when(yooKassaClient.getPayment(YK_ID)).thenReturn(succeeded());
        paymentProcessingService.handleSucceeded(YK_ID);
    }

    /** Покупка с явно названной подпиской — так платят из экрана «Мои подписки». */
    private void payInto(Subscription target, String tierId) {
        Payment payment = paymentFactory.create(draft(tierId), TargetSpec.of(target.getId()));
        assertThat(payment.getSubscriptionId())
                .as("явно названная подписка обязана доехать до строки платежа")
                .isEqualTo(target.getId());
        payment.setYookassaPaymentId(YK_ID);
        paymentRepository.save(payment);
        when(yooKassaClient.getPayment(YK_ID)).thenReturn(succeeded());
        paymentProcessingService.handleSucceeded(YK_ID);
    }

    private PaymentDraft draft(String tierId) {
        return PaymentDraft.builder()
                .telegramId(TG_ID)
                .telegramUsername("multisub")
                .planId("month_1")
                .deviceTierId(tierId)
                .amountKopeks(15000L)
                .provider(Payment.Provider.YOOKASSA)
                .build();
    }

    private List<SubscriptionLedger> ledgerOf(Long subscriptionId) {
        return ledgerRepository.findAll().stream()
                .filter(l -> subscriptionId.equals(l.getSubscriptionId()))
                .toList();
    }

    private static AccessProvider.AccessAccount secondAccount(String username) {
        AccessProvider.AccessAccount u = new AccessProvider.AccessAccount();
        u.setUsername(username);
        u.setSubscriptionUrl("https://test.example.com/sub/" + username);
        return u;
    }

    private static YooKassaClient.PaymentResponse succeeded() {
        YooKassaClient.PaymentResponse resp = new YooKassaClient.PaymentResponse();
        resp.setId(YK_ID);
        resp.setStatus("succeeded");
        return resp;
    }
}
