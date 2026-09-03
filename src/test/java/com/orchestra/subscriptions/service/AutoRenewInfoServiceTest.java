package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Снимок автопродления для экранов. Проверяются не поля, а обещания, которые по ним даются
 * человеку: спишется ли вообще, за какую подписку и не изменится ли у неё лимит устройств.
 */
class AutoRenewInfoServiceTest {

    private static final long TG_ID = 42L;

    private AppConfig appConfig;
    private SubscriptionRepository subscriptionRepository;
    private AutoRenewInfoService service;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        subscriptionRepository = mock(SubscriptionRepository.class);
        when(appConfig.getYookassa().isRecurrentEnabled()).thenReturn(true);
        when(appConfig.findPlanOptional("month")).thenReturn(Optional.of(plan()));
        when(appConfig.findDeviceTierOptional("d3")).thenReturn(Optional.of(tier("d3", "3 устройства", 3)));
        when(appConfig.findDeviceTierOptional("d1")).thenReturn(Optional.of(tier("d1", "1 устройство", 1)));
        when(appConfig.findDeviceTierOptional(null)).thenReturn(Optional.empty());
        when(appConfig.tierPriceKopeks(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(29900L);
        when(subscriptionRepository.countByTelegramId(anyLong())).thenReturn(2L);
        service = new AutoRenewInfoService(appConfig, subscriptionRepository);
    }

    /**
     * Дату списания считаем от срока РАБОТАЮЩЕЙ подписки. У припаркованной {@code expires_at}
     * пуст по построению, и счёт от срока человека показал бы дату той подписки, которая спит.
     */
    @Test
    @DisplayName("Дата списания — минус сутки от срока действующей подписки")
    void nextChargeCountsFromCurrentSubscription() {
        LocalDateTime until = LocalDateTime.of(2026, 9, 10, 12, 0);
        givenCurrent(sub(2, "d3", until));

        AutoRenewInfoService.Info info = service.forUser(user(true, "d3"));

        assertThat(info.nextChargeAt()).isEqualTo(until.minusHours(24));
        assertThat(info.targetSeq()).isEqualTo(2);
        assertThat(info.willCharge()).isTrue();
    }

    /**
     * ⛔ ГЛАВНОЕ ПРАВИЛО (решение владельца 14.08.2026): списывается ступень ПРОДЛЕВАЕМОЙ
     * ПОДПИСКИ, а не ступень последней покупки, лежащая на человеке.
     * <p>
     * Раньше эти две вещи расходились после переключения: человек, перешедший на подписку
     * «1 устройство», ночью получал счёт за «3 устройства», и активация переписывала лимит
     * подписки под оплаченную ступень. Экран обязан показывать ровно ту цифру, которую спишут,
     * поэтому снимок считает ступень тем же способом, что и {@code chargeAndActivate}.
     */
    @Test
    @DisplayName("Ступень берётся у продлеваемой подписки, а не у последней покупки")
    void tierComesFromRenewedSubscription() {
        givenCurrent(sub(2, "d1", LocalDateTime.now().plusDays(5)));

        AutoRenewInfoService.Info info = service.forUser(user(true, "d3"));

        assertThat(info.tierId())
                .as("на человеке лежит d3, но продлевают подписку со ступенью d1")
                .isEqualTo("d1");
        assertThat(info.tierName()).isEqualTo("1 устройство");
    }

    /** Одна подписка — ступень человека и подписки совпадают, поведение прежнее. */
    @Test
    @DisplayName("Совпадающие ступени дают тот же ответ, что и раньше")
    void singleSubscriptionKeepsOldAnswer() {
        givenCurrent(sub(1, "d3", LocalDateTime.now().plusDays(5)));

        assertThat(service.forUser(user(true, "d3")).tierId()).isEqualTo("d3");
    }

    /**
     * У подписки нет ступени (триал, покупка до появления ступеней) — списывается базовая цена
     * плана, а лимит устройств активация не трогает. Ступень с человека сюда не подставляется:
     * это переписало бы лимит подписки, которая жила без него.
     */
    @Test
    @DisplayName("Подписка без ступени: ступень человека её не навязывает")
    void subscriptionWithoutTierIsNotRetiered() {
        givenCurrent(sub(1, null, LocalDateTime.now().plusDays(5)));

        AutoRenewInfoService.Info info = service.forUser(user(true, "d3"));

        assertThat(info.tierId()).isNull();
        assertThat(info.tierName()).isNull();
    }

    /**
     * ⚠️ Списания не будет и при выключенных рекуррентах у магазина: планировщик в этом случае
     * не делает ни одной попытки. Экран, считающий {@code willCharge} по одному лишь флагу
     * человека, обещал бы продление, которого не произойдёт.
     */
    @Test
    @DisplayName("Выключенные рекурренты магазина отменяют обещание продления")
    void shopDisabledMeansNoCharge() {
        when(appConfig.getYookassa().isRecurrentEnabled()).thenReturn(false);
        givenCurrent(sub(1, "d3", LocalDateTime.now().plusDays(5)));

        AutoRenewInfoService.Info info = service.forUser(user(true, "d3"));

        assertThat(info.enabled()).isTrue();
        assertThat(info.willCharge()).isFalse();
    }

    /** Карта отвязана — списывать нечем, сколько бы флагов ни стояло. */
    @Test
    @DisplayName("Без привязанной карты списания не будет")
    void withoutCardThereIsNoCharge() {
        givenCurrent(sub(1, "d3", LocalDateTime.now().plusDays(5)));
        Customer u = user(true, "d3");
        u.setAutorenewMethodId(null);

        assertThat(service.forUser(u).willCharge()).isFalse();
    }

    /** Тариф сняли с продажи — следующая попытка выключит автопродление, предупреждаем заранее. */
    @Test
    @DisplayName("Снятый с продажи тариф помечается отдельно")
    void removedPlanIsFlagged() {
        when(appConfig.findPlanOptional("month")).thenReturn(Optional.empty());
        givenCurrent(sub(1, "d3", LocalDateTime.now().plusDays(5)));

        AutoRenewInfoService.Info info = service.forUser(user(true, "d3"));

        assertThat(info.planGone()).isTrue();
        assertThat(info.amountRub()).isZero();
    }

    /** У человека с одной подпиской номер только мешает: называть нечего и незачем. */
    @Test
    @DisplayName("Одна подписка — номер не показываем")
    void singleSubscriptionNeedsNoNumber() {
        when(subscriptionRepository.countByTelegramId(anyLong())).thenReturn(1L);
        givenCurrent(sub(1, "d3", LocalDateTime.now().plusDays(5)));

        assertThat(service.forUser(user(true, "d3")).needsSubscriptionName()).isFalse();
    }

    // ===== helpers =====

    private void givenCurrent(Subscription s) {
        when(subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID)).thenReturn(Optional.of(s));
    }

    private static Subscription sub(int seq, String tierId, LocalDateTime expiresAt) {
        return Subscription.builder()
                .id((long) seq).telegramId(TG_ID).seq(seq).current(true)
                .state(Customer.Status.ACTIVE)
                .deviceTierId(tierId)
                .expiresAt(expiresAt)
                .build();
    }

    private static Customer user(boolean autorenew, String tierId) {
        Customer u = new Customer();
        u.setTelegramId(TG_ID);
        u.setAutorenewEnabled(autorenew);
        u.setAutorenewMethodId("pm_1");
        u.setAutorenewPlanId("month");
        u.setAutorenewDeviceTierId(tierId);
        return u;
    }

    private static AppConfig.Plan plan() {
        AppConfig.Plan p = new AppConfig.Plan();
        p.setId("month");
        p.setName("Месяц");
        return p;
    }

    private static AppConfig.DeviceTier tier(String id, String name, int devices) {
        AppConfig.DeviceTier t = new AppConfig.DeviceTier();
        t.setId(id);
        t.setName(name);
        t.setDevices(devices);
        return t;
    }
}
