package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.PromoCode;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.PromoCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест диспетчера reply-ввода: промокод (валидный применяется и перерисовывает пикер,
 * невалидный — человекочитаемый отказ), имя устройства.
 */
class UserInputHandlerTest {

    private PendingInputService pendingInputService;
    private PromoCodeService promoCodeService;
    private DeviceTrackingService deviceTrackingService;
    private EmailBotHandler emailBotHandler;
    private ScreenHandler screenHandler;
    private AppConfig appConfig;
    private TelegramSender telegramSender;
    private UserInputHandler handler;

    private final User user = mock(User.class);

    @BeforeEach
    void setUp() {
        pendingInputService = new PendingInputService();
        promoCodeService = mock(PromoCodeService.class);
        deviceTrackingService = mock(DeviceTrackingService.class);
        emailBotHandler = mock(EmailBotHandler.class);
        screenHandler = mock(ScreenHandler.class);
        appConfig = new AppConfig();
        telegramSender = mock(TelegramSender.class);
        handler = new UserInputHandler(pendingInputService, promoCodeService,
                deviceTrackingService, emailBotHandler, screenHandler, appConfig, telegramSender);

        when(user.getId()).thenReturn(7L);

        AppConfig.Plan plan = new AppConfig.Plan();
        plan.setId("month_1");
        plan.setPriceKopeks(17900);
        appConfig.setPlans(java.util.List.of(plan));
    }

    private PendingInputService.PendingInput input(PendingInputService.Kind kind, String payload) {
        return new PendingInputService.PendingInput(kind, payload,
                java.time.Instant.now().plusSeconds(60));
    }

    @Test
    void promoInput_valid_appliedAndPickerRedrawn() throws Exception {
        when(promoCodeService.preview(eq("WELCOME20"), eq(17900L), eq(7L)))
                .thenReturn(new PromoCodeService.PromoResult(
                        true, null, 1L, "WELCOME20", PromoCode.DiscountType.PERCENT,
                        20, 3580, 14320));

        handler.handle(input(PendingInputService.Kind.PROMO, "month_1"), "WELCOME20", 100L, user);

        assertThat(pendingInputService.getAppliedPromo(7L)).isEqualTo("WELCOME20");
        verify(screenHandler).sendPaymentMethodPicker(100L, 7L, "month_1");
    }

    @Test
    void promoInput_invalid_humanReadableRefusal() throws Exception {
        when(promoCodeService.preview(anyString(), anyLong(), eq(7L)))
                .thenReturn(PromoCodeService.PromoResult.invalid("expired"));

        handler.handle(input(PendingInputService.Kind.PROMO, "month_1"), "OLDCODE", 100L, user);

        assertThat(pendingInputService.getAppliedPromo(7L)).isNull();
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender, atLeastOnce()).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("Срок действия промокода истёк");
    }

    @Test
    void deviceNameInput_renamesAndRedrawsDevices() throws Exception {
        when(deviceTrackingService.rename(7L, 5L, "телефон мамы")).thenReturn(true);

        handler.handle(input(PendingInputService.Kind.DEVICE_NAME, "5"), "телефон мамы", 100L, user);

        verify(deviceTrackingService).rename(7L, 5L, "телефон мамы");
        verify(screenHandler).sendDevices(100L, 7L);
    }

    @Test
    void emailInput_delegatedToEmailHandler() throws Exception {
        handler.handle(input(PendingInputService.Kind.EMAIL, null), " user@example.com ", 100L, user);
        verify(emailBotHandler).handleEmailInput(100L, 7L, "user@example.com");
    }
}
