package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.service.AutoRenewService;
import com.orchestra.subscriptions.service.AccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест управления автопродлением. После выноса из god-class'а проверяется с моком
 * сендера, без Spring.
 */
class AutoRenewHandlerTest {

    private AppConfig appConfig;
    private AccessService accessService;
    private AutoRenewService autoRenewService;
    private TelegramSender telegramSender;
    private AutoRenewHandler handler;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        accessService = mock(AccessService.class);
        autoRenewService = mock(AutoRenewService.class);
        telegramSender = mock(TelegramSender.class);
        handler = new AutoRenewHandler(appConfig, accessService, autoRenewService,
                mock(com.orchestra.subscriptions.service.AutoRenewInfoService.class), telegramSender);
    }

    @Test
    void sendAutoRenewInfo_recurrentDisabled_sendsUnavailableAndSkipsLookup() throws Exception {
        when(appConfig.getYookassa().isRecurrentEnabled()).thenReturn(false);

        handler.sendAutoRenewInfo(100L, 7L);

        assertThat(captureSent().getText()).contains("недоступно");
        verify(accessService, never()).getUser(anyLong()); // фича выключена → в БД не лезем
    }

    @Test
    void handleAutoRenewUnbindConfirmed_unbindsCardAndConfirms() throws Exception {
        when(autoRenewService.unbindCard(7L)).thenReturn(true);

        handler.handleAutoRenewUnbindConfirmed(100L, 7L);

        verify(autoRenewService).unbindCard(7L);
        assertThat(captureSent().getText()).contains("отвязана");
    }

    private SendMessage captureSent() throws Exception {
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).execute(cap.capture());
        return cap.getValue();
    }
}
