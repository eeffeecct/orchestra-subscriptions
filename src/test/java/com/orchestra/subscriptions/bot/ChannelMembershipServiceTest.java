package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тест проверки членства в обязательном канале: разбор статусов, кэш положительных
 * вердиктов и fail-open при недоступной проверке.
 */
class ChannelMembershipServiceTest {

    private AppConfig appConfig;
    private TelegramSender telegramSender;
    private ChannelMembershipService service;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        telegramSender = mock(TelegramSender.class);
        when(appConfig.getTelegram().getRequiredChannel()).thenReturn("@your_channel");
        service = new ChannelMembershipService(appConfig, telegramSender);
    }

    @Test
    void channelNotConfigured_gateOff_noApiCall() {
        when(appConfig.getTelegram().getRequiredChannel()).thenReturn("");

        assertThat(service.required()).isFalse();
        assertThat(service.isMember(7L)).isTrue();
        verifyNoInteractions(telegramSender);
    }

    @Test
    void memberStatuses_allowed() throws Exception {
        for (String status : new String[]{"member", "creator", "administrator"}) {
            reply(status);
            assertThat(service.isMember(7L)).as(status).isTrue();
            service.forget(7L);
        }
    }

    @Test
    void leftStatus_denied() throws Exception {
        reply("left");
        assertThat(service.isMember(7L)).isFalse();
    }

    @Test
    void kickedStatus_denied() throws Exception {
        reply("kicked");
        assertThat(service.isMember(7L)).isFalse();
    }

    /** Список статусов задаёт Telegram — незнакомый трактуем как «не подписан». */
    @Test
    void unknownStatus_denied() throws Exception {
        reply("something_new");
        assertThat(service.isMember(7L)).isFalse();
    }

    @Test
    void restrictedButStillMember_allowed() throws Exception {
        ChatMemberRestricted restricted = mock(ChatMemberRestricted.class);
        when(restricted.getStatus()).thenReturn("restricted");
        when(restricted.getIsMember()).thenReturn(true);
        when(telegramSender.execute(any(GetChatMember.class))).thenReturn(restricted);

        assertThat(service.isMember(7L)).isTrue();
    }

    @Test
    void restrictedAndNotMember_denied() throws Exception {
        ChatMemberRestricted restricted = mock(ChatMemberRestricted.class);
        when(restricted.getStatus()).thenReturn("restricted");
        when(restricted.getIsMember()).thenReturn(false);
        when(telegramSender.execute(any(GetChatMember.class))).thenReturn(restricted);

        assertThat(service.isMember(7L)).isFalse();
    }

    /**
     * Бота сняли с админов канала / сеть отвалилась — пускаем. Обратный выбор запер бы вход
     * в бот целиком, включая оплаченные подписки.
     */
    @Test
    void apiFailure_failsOpen() throws Exception {
        when(telegramSender.execute(any(GetChatMember.class)))
                .thenThrow(new TelegramApiException("chat admin required"));

        assertThat(service.isMember(7L)).isTrue();
    }

    @Test
    void confirmedMembership_servedFromCache() throws Exception {
        reply("member");

        assertThat(service.isMember(7L)).isTrue();
        assertThat(service.isMember(7L)).isTrue();

        verify(telegramSender, times(1)).execute(any(GetChatMember.class));
    }

    /** Отказ не кэшируется: человек подписывается и жмёт «Проверить» в ту же секунду. */
    @Test
    void denial_notCached() throws Exception {
        reply("left");
        assertThat(service.isMember(7L)).isFalse();

        reply("member");
        assertThat(service.isMember(7L)).isTrue();

        verify(telegramSender, times(2)).execute(any(GetChatMember.class));
    }

    /** Кнопка «Проверить» обязана сходить в Telegram, а не отвечать из кэша. */
    @Test
    void isMemberFresh_bypassesCache() throws Exception {
        reply("member");
        assertThat(service.isMember(7L)).isTrue();

        reply("left");
        assertThat(service.isMemberFresh(7L)).isFalse();

        verify(telegramSender, times(2)).execute(any(GetChatMember.class));
    }

    /** Спрашиваем именно тот канал, что задан в конфиге, и про того человека. */
    @Test
    void asksTelegramAboutConfiguredChannel() throws Exception {
        reply("member");
        service.isMember(42L);

        var captor = org.mockito.ArgumentCaptor.forClass(GetChatMember.class);
        verify(telegramSender).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("@your_channel");
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
    }

    private void reply(String status) throws Exception {
        ChatMember member = mock(ChatMember.class);
        when(member.getStatus()).thenReturn(status);
        when(telegramSender.execute(any(GetChatMember.class))).thenReturn(member);
    }
}
