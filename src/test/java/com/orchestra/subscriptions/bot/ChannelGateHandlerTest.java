package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тест гейта обязательной подписки на канал: кого перехватывает, кого пропускает,
 * что делает кнопка «Проверить».
 */
class ChannelGateHandlerTest {

    private static final Long OWNER = 1L;
    private static final Long USER = 7L;
    private static final Long CHAT = 100L;

    private AppConfig appConfig;
    private ChannelMembershipService membership;
    private TelegramSender telegramSender;
    private OnboardingHandler onboarding;
    private ChannelGateHandler handler;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        membership = mock(ChannelMembershipService.class);
        telegramSender = mock(TelegramSender.class);
        onboarding = mock(OnboardingHandler.class);

        when(appConfig.getTelegram().getOwnerId()).thenReturn(OWNER);
        when(appConfig.getTelegram().getRequiredChannelJoinUrl()).thenReturn("https://t.me/your_channel");
        when(membership.required()).thenReturn(true);

        handler = new ChannelGateHandler(appConfig, membership, telegramSender, onboarding);
    }

    // ---------- сообщения ----------

    @Test
    void notMember_messageIntercepted_gateShown() throws Exception {
        when(membership.isMember(USER)).thenReturn(false);

        assertThat(handler.interceptMessage(CHAT, USER)).isTrue();

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).execute(sent.capture());
        assertThat(sent.getValue().getText()).contains("Подписка на канал");
        assertThat(sent.getValue().getReplyMarkup()).isNotNull();
    }

    @Test
    void member_messagePassesThrough() throws Exception {
        when(membership.isMember(USER)).thenReturn(true);

        assertThat(handler.interceptMessage(CHAT, USER)).isFalse();
        verifyNoInteractions(telegramSender);
    }

    /** Владельца гейт не трогает: снятие бота с админов канала не должно резать админку. */
    @Test
    void owner_neverGated() throws Exception {
        assertThat(handler.interceptMessage(CHAT, OWNER)).isFalse();
        verify(membership, never()).isMember(anyLong());
        verifyNoInteractions(telegramSender);
    }

    @Test
    void gateOff_messagePassesThrough() throws Exception {
        when(membership.required()).thenReturn(false);

        assertThat(handler.interceptMessage(CHAT, USER)).isFalse();
        verify(membership, never()).isMember(anyLong());
    }

    /** Без ссылки «Подписаться» экран был бы тупиком — такой гейт считается выключенным. */
    @Test
    void noJoinUrl_messagePassesThrough() throws Exception {
        when(appConfig.getTelegram().getRequiredChannelJoinUrl()).thenReturn("");

        assertThat(handler.interceptMessage(CHAT, USER)).isFalse();
        verify(membership, never()).isMember(anyLong());
    }

    // ---------- callback'и ----------

    @Test
    void notMember_foreignCallbackIntercepted() throws Exception {
        when(membership.isMember(USER)).thenReturn(false);

        assertThat(handler.interceptCallback(callback("menu:buy", USER))).isTrue();

        // Гасим loader своим ответом и тут же показываем экран подписки.
        verify(telegramSender).execute(any(AnswerCallbackQuery.class));
        verify(telegramSender).execute(any(SendMessage.class));
    }

    @Test
    void member_foreignCallbackPassesThrough() throws Exception {
        when(membership.isMember(USER)).thenReturn(true);

        assertThat(handler.interceptCallback(callback("menu:buy", USER))).isFalse();
        verifyNoInteractions(telegramSender);
    }

    @Test
    void checkCallback_stillNotMember_staysOnGate() throws Exception {
        when(membership.isMemberFresh(USER)).thenReturn(false);

        assertThat(handler.interceptCallback(callback(ChannelGateHandler.CHECK_CALLBACK, USER))).isTrue();

        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramSender).execute(answer.capture());
        assertThat(answer.getValue().getText()).contains("Подписка не найдена");
        assertThat(answer.getValue().getShowAlert()).isTrue();
        verify(telegramSender, never()).execute(any(DeleteMessage.class));
        verifyNoInteractions(onboarding);
    }

    @Test
    void checkCallback_subscribed_removesGateAndOpensBot() throws Exception {
        when(membership.isMemberFresh(USER)).thenReturn(true);

        assertThat(handler.interceptCallback(callback(ChannelGateHandler.CHECK_CALLBACK, USER))).isTrue();

        verify(telegramSender).execute(any(AnswerCallbackQuery.class));
        verify(telegramSender).execute(any(DeleteMessage.class));
        verify(onboarding).sendWelcome(CHAT, USER);
    }

    /** Кнопка «Проверить» обязана спрашивать Telegram заново, а не смотреть в кэш. */
    @Test
    void checkCallback_asksFreshNotCached() throws Exception {
        when(membership.isMemberFresh(USER)).thenReturn(true);

        handler.interceptCallback(callback(ChannelGateHandler.CHECK_CALLBACK, USER));

        verify(membership).isMemberFresh(USER);
        verify(membership, never()).isMember(anyLong());
    }

    private Update callback(String data, Long telegramId) {
        User user = new User(telegramId, "Tester", false);

        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(CHAT);
        when(message.getMessageId()).thenReturn(555);

        CallbackQuery query = mock(CallbackQuery.class);
        when(query.getId()).thenReturn("cbq-1");
        when(query.getData()).thenReturn(data);
        when(query.getFrom()).thenReturn(user);
        when(query.getMessage()).thenReturn(message);

        Update update = mock(Update.class);
        when(update.getCallbackQuery()).thenReturn(query);
        return update;
    }
}
