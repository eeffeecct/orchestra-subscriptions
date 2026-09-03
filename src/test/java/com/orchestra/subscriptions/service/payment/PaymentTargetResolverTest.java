package com.orchestra.subscriptions.service.payment;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.service.MultiSubscriptionPolicy;
import com.orchestra.subscriptions.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Правило выбора цели платежа. Здесь решается, куда лягут деньги, поэтому проверяется каждая
 * ветка — включая те, что в проде сейчас недостижимы: они станут достижимыми ровно в тот день,
 * когда владелец включит флаг, и к этому моменту должны быть уже покрыты.
 */
class PaymentTargetResolverTest {

    private static final long TG_ID = 4242L;
    private static final long OTHER_TG_ID = 999L;

    private SubscriptionRepository subscriptionRepository;
    private MultiSubscriptionPolicy policy;
    private NotificationService notificationService;
    private PaymentTargetResolver resolver;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        policy = mock(MultiSubscriptionPolicy.class);
        notificationService = mock(NotificationService.class);
        resolver = new PaymentTargetResolver(subscriptionRepository, policy, notificationService);
        // Дефолт как в проде и в тестовом конфиге: заводить отдельные подписки нельзя.
        when(policy.canOpenNew(anyLong())).thenReturn(false);
        when(policy.maxPerUser()).thenReturn(3);
    }

    // =====================================================================
    //  Цель не названа — сегодняшнее поведение всех каналов
    // =====================================================================

    @Test
    void noSubscriptionsAtAll_mints() {
        haveSubscriptions();

        PaymentTarget target = resolver.resolve(TG_ID, "d2", TargetSpec.current());

        assertThat(target.kind()).isEqualTo(PaymentTarget.Kind.NEW);
        assertThat(target.subscriptionId()).isNull();
    }

    @Test
    void unnamedTarget_goesToCurrent() {
        Subscription current = sub(10L, 1, true, "d2");
        haveSubscriptions(current, sub(11L, 2, false, "f5"));

        PaymentTarget target = resolver.resolve(TG_ID, "d2", TargetSpec.current());

        assertThat(target.kind()).isEqualTo(PaymentTarget.Kind.EXTEND);
        assertThat(target.subscriptionId()).isEqualTo(10L);
        assertThat(target.seq()).isEqualTo(1);
        assertThat(target.parked()).isFalse();
    }

    /**
     * Ключевая гарантия этого шага: даже когда куплена ДРУГАЯ ступень, при выключенном флаге
     * деньги идут в текущую подписку. Иначе поведение прода изменилось бы вместе с сантехникой.
     */
    @Test
    void unnamedTarget_otherTier_flagOff_stillGoesToCurrent() {
        haveSubscriptions(sub(10L, 1, true, "d2"));

        PaymentTarget target = resolver.resolve(TG_ID, "f5", TargetSpec.current());

        assertThat(target.kind()).isEqualTo(PaymentTarget.Kind.EXTEND);
        assertThat(target.subscriptionId()).isEqualTo(10L);
    }

    /** Та же покупка при включённом флаге заводит отдельную подписку — правило заложено. */
    @Test
    void unnamedTarget_otherTier_flagOn_opensSeparate() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        haveSubscriptions(sub(10L, 1, true, "d2"));

        PaymentTarget target = resolver.resolve(TG_ID, "f5", TargetSpec.current());

        // Именно SEPARATE, а не NEW: первая подписка человека и отдельная подписка поверх
        // существующих активируются РАЗНЫМИ путями, и одно значение на оба случая уже давало
        // баг — зависший счёт первой покупки заводил вторую подписку вместо продления.
        assertThat(target.kind()).isEqualTo(PaymentTarget.Kind.SEPARATE);
    }

    /** Та же ступень продлевает всегда — вторая неотличимая подписка не заводится. */
    @Test
    void unnamedTarget_sameTier_flagOn_extendsCurrent() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        haveSubscriptions(sub(10L, 1, true, "d2"));

        PaymentTarget target = resolver.resolve(TG_ID, "d2", TargetSpec.current());

        assertThat(target.subscriptionId()).isEqualTo(10L);
    }

    /**
     * Триальные и все, кто покупал до появления ступеней, живут без {@code device_tier_id}.
     * Их «поглощение триала» и legacy-апгрейд обязаны остаться прежними даже при флаге ON.
     */
    @Test
    void unnamedTarget_legacyUserWithoutTier_flagOn_extendsCurrent() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        haveSubscriptions(sub(10L, 1, true, null));

        PaymentTarget target = resolver.resolve(TG_ID, "d2", TargetSpec.current());

        assertThat(target.subscriptionId()).isEqualTo(10L);
    }

    /**
     * ⛔ ГЛАВНАЯ ГАРАНТИЯ ПРАВИЛА: покупка ступени, которая уже есть у ЗАМОРОЖЕННОЙ подписки,
     * пополняет ЕЁ, а не переписывает ступень работающей.
     * <p>
     * До 14.08.2026 здесь была дыра, ради закрытия которой затевалась вся мультиподписка:
     * «отдельную заводить нельзя, дубликат» роняло цель в текущую подписку, и покупка дешёвой
     * ступени понижала тариф работающей подписки на весь оплаченный срок с отключением лишних
     * устройств. То есть ровно тот баг, от которого фича и защищает, возвращался через дверь
     * «у меня уже есть подписка этой ступени».
     */
    @Test
    void unnamedTarget_tierOfParkedSubscription_topsUpThatOne() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        haveSubscriptions(sub(10L, 1, true, "d3"), sub(11L, 2, false, "d1"));

        PaymentTarget target = resolver.resolve(TG_ID, "d1", TargetSpec.current());

        assertThat(target.kind()).isEqualTo(PaymentTarget.Kind.EXTEND);
        assertThat(target.subscriptionId())
                .as("деньги обязаны идти в подписку своей ступени, а не понижать работающую")
                .isEqualTo(11L);
        assertThat(target.parked()).isTrue();
    }

    /**
     * Тот же запрет действует и при ВЫКЛЮЧЕННОМ флаге: он гейтит создание новых подписок, а не
     * защиту оплаченного срока. У человека с двумя подписками, заведёнными до выключения флага,
     * покупка не должна сжигать ступень работающей.
     */
    @Test
    void unnamedTarget_tierOfParkedSubscription_flagOff_stillTopsUpThatOne() {
        haveSubscriptions(sub(10L, 1, true, "d3"), sub(11L, 2, false, "d1"));

        PaymentTarget target = resolver.resolve(TG_ID, "d1", TargetSpec.current());

        assertThat(target.subscriptionId()).isEqualTo(11L);
    }

    /**
     * Потолок достигнут, купленной ступени нет ни у одной подписки: завести третью нельзя,
     * а понижать работающую — тем более. Остаётся замороженная: она не работает, и смена её
     * ступени никому не обрывает доступ. Экраны на обоих каналах называют эту цель прямо.
     */
    @Test
    void unnamedTarget_atLimitWithUnknownTier_prefersParkedOverDowngradingCurrent() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        when(policy.maxPerUser()).thenReturn(2);
        haveSubscriptions(sub(10L, 1, true, "d3"), sub(11L, 2, false, "d1"));

        PaymentTarget target = resolver.resolve(TG_ID, "f5", TargetSpec.current());

        assertThat(target.subscriptionId()).isEqualTo(11L);
        assertThat(target.parked()).isTrue();
    }

    /** Совпадение со ступенью ТЕКУЩЕЙ по-прежнему продлевает именно её, а не будит спящую. */
    @Test
    void unnamedTarget_tierOfCurrent_extendsCurrentNotParked() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        haveSubscriptions(sub(10L, 1, true, "d3"), sub(11L, 2, false, "d1"));

        PaymentTarget target = resolver.resolve(TG_ID, "d3", TargetSpec.current());

        assertThat(target.subscriptionId()).isEqualTo(10L);
        assertThat(target.parked()).isFalse();
    }

    /** Битое зеркало (ни одна строка не текущая) — не повод отказать в оплате. */
    @Test
    void brokenMirrorWithoutCurrent_fallsBackToFirstBySeq() {
        haveSubscriptions(sub(10L, 1, false, "d2"), sub(11L, 2, false, "f5"));

        PaymentTarget target = resolver.resolve(TG_ID, "d2", TargetSpec.current());

        assertThat(target.subscriptionId()).isEqualTo(10L);
        assertThat(target.parked()).isTrue();
    }

    // =====================================================================
    //  Явно названная подписка
    // =====================================================================

    @Test
    void namedOwnSubscription_isUsed() {
        Subscription parked = sub(11L, 2, false, "f5");
        haveSubscriptions(sub(10L, 1, true, "d2"), parked);
        when(subscriptionRepository.findByIdAndTelegramId(11L, TG_ID)).thenReturn(Optional.of(parked));

        PaymentTarget target = resolver.resolve(TG_ID, "f5", TargetSpec.of(11L));

        assertThat(target.subscriptionId()).isEqualTo(11L);
        assertThat(target.parked()).isTrue();
        verifyNoInteractions(notificationService);
    }

    /** IDOR: чужой id никогда не становится целью — деньги ложатся туда, где юзер их увидит. */
    @Test
    void namedForeignSubscription_fallsBackToCurrentAndAlertsOwner() {
        haveSubscriptions(sub(10L, 1, true, "d2"));
        when(subscriptionRepository.findByIdAndTelegramId(777L, TG_ID)).thenReturn(Optional.empty());

        PaymentTarget target = resolver.resolve(TG_ID, "d2", TargetSpec.of(777L));

        assertThat(target.subscriptionId()).isEqualTo(10L);
        verify(notificationService).notifyOwnerPaymentTargetAnomaly(eq(TG_ID), isNull(), any());
    }

    // =====================================================================
    //  Явная просьба «завести отдельную»
    // =====================================================================

    @Test
    void explicitNew_flagOff_rejectsCheckout() {
        haveSubscriptions(sub(10L, 1, true, "d2"));

        assertThatThrownBy(() -> resolver.resolve(TG_ID, "f5", TargetSpec.openNew()))
                .isInstanceOf(CheckoutRejectedException.class)
                .extracting(e -> ((CheckoutRejectedException) e).reason())
                .isEqualTo(CheckoutRejectedException.MULTI_DISABLED);
    }

    @Test
    void explicitNew_overLimit_rejectsCheckout() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        when(policy.maxPerUser()).thenReturn(2);
        haveSubscriptions(sub(10L, 1, true, "d2"), sub(11L, 2, false, "f5"));

        assertThatThrownBy(() -> resolver.resolve(TG_ID, "d10", TargetSpec.openNew()))
                .isInstanceOf(CheckoutRejectedException.class)
                .extracting(e -> ((CheckoutRejectedException) e).reason())
                .isEqualTo(CheckoutRejectedException.LIMIT);
    }

    @Test
    void explicitNew_sameTierAlreadyExists_rejectsCheckout() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        haveSubscriptions(sub(10L, 1, true, "d2"), sub(11L, 2, false, "f5"));

        assertThatThrownBy(() -> resolver.resolve(TG_ID, "f5", TargetSpec.openNew()))
                .isInstanceOf(CheckoutRejectedException.class)
                .extracting(e -> ((CheckoutRejectedException) e).reason())
                .isEqualTo(CheckoutRejectedException.SAME_TIER_EXISTS);
    }

    @Test
    void explicitNew_allowed_mints() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        haveSubscriptions(sub(10L, 1, true, "d2"));

        PaymentTarget target = resolver.resolve(TG_ID, "f5", TargetSpec.openNew());

        assertThat(target.kind()).isEqualTo(PaymentTarget.Kind.SEPARATE);
        assertThat(target.subscriptionId()).isNull();
    }

    /** Allow-list персональный: включение флага одному человеку не открывает фичу другому. */
    @Test
    void allowListIsPerPerson() {
        when(policy.canOpenNew(TG_ID)).thenReturn(true);
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(OTHER_TG_ID))
                .thenReturn(List.of(sub(20L, 1, true, "d2")));

        assertThatThrownBy(() -> resolver.resolve(OTHER_TG_ID, "f5", TargetSpec.openNew()))
                .isInstanceOf(CheckoutRejectedException.class);
        verify(policy, never()).maxPerUser();
    }

    // ===== helpers =====

    private void haveSubscriptions(Subscription... subs) {
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID)).thenReturn(List.of(subs));
    }

    private static Subscription sub(Long id, int seq, boolean current, String tierId) {
        return Subscription.builder()
                .id(id)
                .telegramId(TG_ID)
                .seq(seq)
                .current(current)
                .deviceTierId(tierId)
                .build();
    }
}
