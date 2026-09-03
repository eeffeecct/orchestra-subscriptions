package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.OfferAcceptance;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.OfferAcceptanceRepository;
import com.orchestra.subscriptions.service.ReferralService;
import com.orchestra.subscriptions.service.AccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест онбординга: welcome-экран и deep-link реферала. Отправка — мок сендера,
 * {@link AppConfig} — deep-stub (welcome лезет в getTelegram()/getEmoji()).
 */
class OnboardingHandlerTest {

    private AppConfig appConfig;
    private AccessService accessService;
    private ReferralService referralService;
    private TelegramSender telegramSender;
    private OfferAcceptanceRepository offerAcceptanceRepository;
    private OnboardingHandler handler;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        accessService = mock(AccessService.class);
        referralService = mock(ReferralService.class);
        telegramSender = mock(TelegramSender.class);
        offerAcceptanceRepository = mock(OfferAcceptanceRepository.class);
        handler = new OnboardingHandler(appConfig, accessService, referralService, telegramSender,
                offerAcceptanceRepository);
    }

    @Test
    void handleStartParam_validRef_recordsReferral() {
        handler.handleStartParam(100L, "ref_55");
        verify(referralService).recordReferral(55L, 100L);
    }

    @Test
    void handleStartParam_malformedRef_doesNotRecord() {
        handler.handleStartParam(100L, "ref_notanumber");
        verify(referralService, never()).recordReferral(anyLong(), anyLong());
    }

    @Test
    void handleStartParam_notAReferral_ignored() {
        handler.handleStartParam(100L, "somethingelse");
        verify(referralService, never()).recordReferral(anyLong(), anyLong());
    }

    @Test
    void sendWelcome_sendsWelcomeAndBottomMenu() throws Exception {
        when(accessService.getUser(anyLong())).thenReturn(Optional.empty()); // canUseTrial → true
        when(offerAcceptanceRepository.existsById(7L)).thenReturn(true);  // оферта уже принята

        handler.sendWelcome(100L, 7L);

        // welcome-сообщение + отдельное сообщение-носитель нижнего меню = 2 отправки
        verify(telegramSender, times(2)).execute(any(SendMessage.class));
    }

    @Test
    void sendWelcome_firstLaunch_showsOfferGateInsteadOfWelcome() throws Exception {
        when(accessService.getUser(anyLong())).thenReturn(Optional.empty());
        when(offerAcceptanceRepository.existsById(7L)).thenReturn(false);

        handler.sendWelcome(100L, 7L);

        // Ровно одно сообщение — экран согласия. Нижнее меню до согласия не ставим.
        verify(telegramSender, times(1)).execute(any(SendMessage.class));
        verify(offerAcceptanceRepository, never()).save(any());
    }

    @Test
    void sendWelcome_existingUserWithoutAcceptance_isNotGated() throws Exception {
        // Человек пришёл до появления гейта: строка в customers есть, отметки согласия нет.
        // Перегораживать ему вход нельзя.
        when(accessService.getUser(anyLong())).thenReturn(Optional.of(new Customer()));
        when(offerAcceptanceRepository.existsById(7L)).thenReturn(false);

        handler.sendWelcome(100L, 7L);

        verify(telegramSender, times(2)).execute(any(SendMessage.class));
    }

    @Test
    void acceptOffer_recordsConsentDeletesGateAndOpensBot() throws Exception {
        when(accessService.getUser(anyLong())).thenReturn(Optional.empty());
        when(appConfig.getTelegram().getOfferUrl()).thenReturn("https://telegra.ph/offer");
        when(appConfig.getTelegram().getOfferRevision()).thenReturn("2026-08-21");

        handler.acceptOffer(100L, 7L, 42);

        ArgumentCaptor<OfferAcceptance> saved = ArgumentCaptor.forClass(OfferAcceptance.class);
        verify(offerAcceptanceRepository).save(saved.capture());
        assertThat(saved.getValue().getTelegramId()).isEqualTo(7L);
        assertThat(saved.getValue().getAcceptedAt()).isNotNull();
        assertThat(saved.getValue().getOfferUrl()).isEqualTo("https://telegra.ph/offer");
        // Одного адреса мало: текст на Telegraph правится на месте, и «согласился по этому
        // адресу» после первой правки перестаёт что-либо доказывать. Метку редакции пишем рядом.
        assertThat(saved.getValue().getOfferRevision()).isEqualTo("2026-08-21");

        verify(telegramSender).execute(any(DeleteMessage.class));
        // Дальше сразу бот: welcome + нижнее меню, без повторного экрана согласия.
        verify(telegramSender, times(2)).execute(any(SendMessage.class));
    }

    /**
     * Редакция не настроена — пишем пусто. Подставлять сюда что-нибудь «по умолчанию» нельзя:
     * пустое поле честно значит «неизвестно», а выдуманная метка врёт увереннее, чем её
     * отсутствие, и именно на неё сошлются в споре.
     */
    @Test
    void acceptOffer_withoutConfiguredRevision_storesNothingInsteadOfGuessing() throws Exception {
        when(accessService.getUser(anyLong())).thenReturn(Optional.empty());
        when(appConfig.getTelegram().getOfferUrl()).thenReturn("https://telegra.ph/offer");
        when(appConfig.getTelegram().getOfferRevision()).thenReturn(null);

        handler.acceptOffer(100L, 7L, 42);

        ArgumentCaptor<OfferAcceptance> saved = ArgumentCaptor.forClass(OfferAcceptance.class);
        verify(offerAcceptanceRepository).save(saved.capture());
        assertThat(saved.getValue().getOfferRevision()).isNull();
    }
}
