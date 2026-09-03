package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тест роутера inline-кнопок. PR-6 заменил if/switch-цепочку в SubscriptionBot на декларативную
 * таблицу маршрутов — этот тест фиксирует, что каждый callback_data попадает в нужный хендлер,
 * префиксная нагрузка извлекается корректно, owner-гейт админки работает, а неизвестные
 * callback'и молча игнорируются.
 */
class CallbackRouterTest {

    private AppConfig appConfig;
    private AdminCommandHandler admin;
    private PaymentHandler payment;
    private ScreenHandler screen;
    private AutoRenewHandler autoRenew;
    private OnboardingHandler onboarding;
    private EmailBotHandler emailBot;
    private SubscriptionScreenHandler subscriptions;
    private TelegramSender telegramSender;
    private ChannelGateHandler channelGate;
    private CallbackRouter router;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        admin = mock(AdminCommandHandler.class);
        payment = mock(PaymentHandler.class);
        screen = mock(ScreenHandler.class);
        autoRenew = mock(AutoRenewHandler.class);
        onboarding = mock(OnboardingHandler.class);
        emailBot = mock(EmailBotHandler.class);
        subscriptions = mock(SubscriptionScreenHandler.class);
        telegramSender = mock(TelegramSender.class);
        // Гейт канала по умолчанию никого не перехватывает — этот тест про маршрутизацию.
        // Его собственное поведение проверяет ChannelGateHandlerTest.
        channelGate = mock(ChannelGateHandler.class);
        router = new CallbackRouter(appConfig, telegramSender, channelGate, admin, payment,
                screen, autoRenew, onboarding, emailBot, subscriptions);
    }

    @Test
    void exactCallback_routesToScreenHandler() throws Exception {
        router.route(callback("status", 100L, 7L));
        verify(screen).sendStatus(100L, 7L);
    }

    @Test
    void menuStart_routesToOnboarding() throws Exception {
        router.route(callback("menu:start", 100L, 7L));
        verify(onboarding).sendWelcome(100L, 7L);
    }

    @Test
    void prefixCallback_extractsPayloadSuffix() throws Exception {
        router.route(callback("plan:month3", 100L, 7L));
        verify(screen).sendPaymentMethodPicker(100L, 7L, "month3");
    }

    @Test
    void emailAndServersAndPromo_routes() throws Exception {
        router.route(callback("email:open", 100L, 7L));
        verify(emailBot).sendEmailScreen(100L, 7L);

        router.route(callback("email:delete:confirm", 100L, 7L));
        verify(emailBot).deleteConfirmed(100L, 7L);

        router.route(callback("servers", 100L, 7L));
        verify(screen).sendServers(100L);

        router.route(callback("promo:ask:month_3:d2", 100L, 7L));
        verify(screen).askPromo(100L, 7L, "month_3:d2");

        router.route(callback("device:name:42", 100L, 7L));
        verify(screen).askDeviceName(100L, 7L, "42");
    }

    @Test
    void payYookassa_passesSuffixAndSavePaymentMethodFalse() throws Exception {
        router.route(callback("pay:yookassa:m1", 100L, 7L));
        verify(payment).handleYooKassaPayment(eq(100L), any(User.class), eq("m1"), isNull(), eq(false));
    }

    @Test
    void payYookassa_withDeviceTier_splitsPlanAndTier() throws Exception {
        router.route(callback("pay:yookassa:m1:d2", 100L, 7L));
        verify(payment).handleYooKassaPayment(eq(100L), any(User.class), eq("m1"), eq("d2"), eq(false));
    }

    @Test
    void tierCallback_routesToBuyMenuForTier() throws Exception {
        router.route(callback("tier:f5", 100L, 7L));
        verify(screen).sendBuyMenuForTier(100L, 7L, "f5");
    }

    @Test
    void devicesCallback_routesToDevicesScreen() throws Exception {
        router.route(callback("devices", 100L, 7L));
        verify(screen).sendDevices(100L, 7L);
    }

    @Test
    void deviceRevoke_passesAction() throws Exception {
        router.route(callback("device:revoke:42", 100L, 7L));
        verify(screen).handleDeviceAction(100L, 7L, "revoke", "42");
    }

    @Test
    void rotateLink_confirmFlow_routes() throws Exception {
        router.route(callback("devlink:rotate", 100L, 7L));
        verify(screen).sendRotateLinkConfirm(100L);

        router.route(callback("devlink:rotate:confirm", 100L, 7L));
        verify(screen).handleRotateLink(100L, 7L);
    }

    @Test
    void adminCallback_owner_dispatched() throws Exception {
        when(appConfig.getTelegram().getOwnerId()).thenReturn(7L); // отправитель = владелец
        router.route(callback("admin:broadcast:cancel:d1", 100L, 7L));
        verify(admin).handleAdminCallback(100L, "admin:broadcast:cancel:d1");
    }

    @Test
    void adminCallback_nonOwner_silentlyIgnored() throws Exception {
        when(appConfig.getTelegram().getOwnerId()).thenReturn(999L); // отправитель НЕ владелец
        router.route(callback("admin:broadcast:cancel:d1", 100L, 7L));
        verify(admin, never()).handleAdminCallback(any(), any());
    }

    @Test
    void unknownCallback_noHandlerInvoked() throws Exception {
        router.route(callback("totally_unknown_xyz", 100L, 7L));
        verifyNoInteractions(admin, payment, screen, autoRenew, onboarding);
    }

    /** Update с callback_query: data, chatId, отправитель. */
    private Update callback(String data, Long chatId, Long userId) {
        Update u = mock(Update.class, RETURNS_DEEP_STUBS);
        when(u.getCallbackQuery().getData()).thenReturn(data);
        when(u.getCallbackQuery().getId()).thenReturn("qid");
        when(u.getCallbackQuery().getMessage().getChatId()).thenReturn(chatId);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(u.getCallbackQuery().getFrom()).thenReturn(user);
        return u;
    }

    // =====================================================================
    //  Экран «Мои подписки» + регресс-щит на старую грамматику кнопок
    // =====================================================================

    @Test
    void subList_routesToSubscriptionScreen() throws Exception {
        router.route(callback("sub:list", 100L, 7L));
        verify(subscriptions).sendSubscriptionList(100L, 7L);
    }

    @Test
    void subSwitch_routesToConfirmScreen() throws Exception {
        router.route(callback("sub:switch:42", 100L, 7L));
        verify(subscriptions).sendSwitchConfirm(100L, 7L, "42");
    }

    @Test
    void subGo_routesToSwitchExecution() throws Exception {
        router.route(callback("sub:go:42", 100L, 7L));
        verify(subscriptions).handleSwitch(100L, 7L, "42");
    }

    /**
     * Регресс-щит. Кнопки покупки живут в истории чата годами, и их грамматику этап 5 не
     * трогает ни одним символом: разъехавшийся парсер продал бы семейный тариф по базовой цене
     * (findDeviceTierOptional(...).orElse(null) не падает, а молча даёт цену без ступени).
     */
    @Test
    void legacyCheckoutGrammarIsUntouched() throws Exception {
        router.route(callback("pay:yookassa:month_3:f5", 100L, 7L));
        verify(payment).handleYooKassaPayment(eq(100L), any(), eq("month_3"), eq("f5"), eq(false));

        router.route(callback("plan:month_1", 100L, 7L));
        verify(screen).sendPaymentMethodPicker(100L, 7L, "month_1");

        router.route(callback("tier:d2", 100L, 7L));
        verify(screen).sendBuyMenuForTier(100L, 7L, "d2");
    }

    /** Формы «sub:<id>» не существует: показ экрана и переключение не должны путаться. */
    @Test
    void bareSubPayloadIsIgnored() throws Exception {
        router.route(callback("sub:42", 100L, 7L));
        verify(subscriptions, org.mockito.Mockito.never())
                .handleSwitch(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
    }
}
