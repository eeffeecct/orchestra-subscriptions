package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.ReferralService;
import com.orchestra.subscriptions.service.AccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест экранов бота. После выноса из god-class'а рендер можно проверять с моком
 * сендера, не поднимая Spring. {@link AppConfig} — deep-stub (экраны лезут в
 * getTelegram()/getProvider()/getEmoji()), остальное — обычные моки.
 */
class ScreenHandlerTest {

    private AppConfig appConfig;
    private AccessService accessService;
    private TelegramSender telegramSender;
    private com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository;
    private com.orchestra.subscriptions.service.payment.PaymentTargetResolver paymentTargetResolver;
    private com.orchestra.subscriptions.service.SubscriptionLookupService subscriptionLookup;
    private com.orchestra.subscriptions.service.AutoRenewInfoService autoRenewInfo;
    private PaymentRepository paymentRepository;
    private com.orchestra.subscriptions.service.DeviceTrackingService deviceTrackingService;
    private ScreenHandler handler;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        accessService = mock(AccessService.class);
        telegramSender = mock(TelegramSender.class);
        subscriptionRepository = mock(com.orchestra.subscriptions.repository.SubscriptionRepository.class);
        paymentTargetResolver = mock(com.orchestra.subscriptions.service.payment.PaymentTargetResolver.class);
        subscriptionLookup = mock(com.orchestra.subscriptions.service.SubscriptionLookupService.class);
        autoRenewInfo = mock(com.orchestra.subscriptions.service.AutoRenewInfoService.class);
        // По умолчанию — человек без автопродления: строка «спишется» не должна появляться
        // сама собой на экранах, которые её не проверяют.
        when(autoRenewInfo.forUser(any())).thenReturn(noAutoRenew());
        paymentRepository = mock(PaymentRepository.class);
        deviceTrackingService = mock(DeviceTrackingService.class);
        when(paymentRepository.findTop20ByTelegramIdOrderByCreatedAtDesc(anyLong()))
                .thenReturn(java.util.List.of());
        handler = new ScreenHandler(appConfig, accessService, paymentRepository,
                subscriptionRepository,
                subscriptionLookup,
                paymentTargetResolver,
                mock(ReferralService.class),
                deviceTrackingService, autoRenewInfo, new PendingInputService(),
                mock(com.orchestra.subscriptions.service.PromoCodeService.class),
                mock(com.orchestra.subscriptions.service.ServerStatusService.class), telegramSender);
    }

    /** Снимок «автопродления нет»: ни включено, ни карты, ни магазина. */
    private static com.orchestra.subscriptions.service.AutoRenewInfoService.Info noAutoRenew() {
        return new com.orchestra.subscriptions.service.AutoRenewInfoService.Info(
                false, false, false, null, null, null, null, 0, null, null, null, 1);
    }

    @Test
    void sendStatus_noSubscription_promptsToSubscribe() throws Exception {
        when(accessService.getUser(anyLong())).thenReturn(Optional.empty());

        handler.sendStatus(100L, 7L);

        assertThat(captureSent().getText()).contains("Подписка пока не оформлена");
    }

    /**
     * Совет заплатить и подсказка «оплата не требуется» на одном экране противоречат друг
     * другу, и человек выберет тот, что увидит первым, — то есть заплатит второй раз за уже
     * оплаченное. Когда включать есть что, про продление экран молчит.
     */
    @Test
    void sendStatus_expiredWithParkedDays_offersSwitchInsteadOfPaying() throws Exception {
        givenExpiredCurrentAndParked(40);

        handler.sendStatus(100L, 7L);

        SendMessage sent = captureSent();
        assertThat(sent.getText()).contains("сохранено 40 дн.");
        assertThat(sent.getText()).contains("оплата не требуется");
        assertThat(sent.getText())
                .as("«продлите её» рядом с «оплата не требуется» — прямое противоречие")
                .doesNotContain("продлите её");
        assertThat(markupTexts(sent)).anyMatch(t -> t.contains("Включить #2"));
    }

    /** Спасать нечем — совет продлить возвращается, иначе экран не говорит, что делать. */
    @Test
    void sendStatus_expiredWithoutParkedDays_asksToRenew() throws Exception {
        givenExpiredCurrentAndParked(0);

        handler.sendStatus(100L, 7L);

        SendMessage sent = captureSent();
        assertThat(sent.getText()).contains("продлите её");
        assertThat(sent.getText()).doesNotContain("оплата не требуется");
    }

    /**
     * «Действует до» поверх неактивной подписки читается как «действует»: дата в прошлом этому
     * не мешает, её сопоставляют с сегодняшней не все и не сразу.
     */
    @Test
    void sendStatus_inactive_doesNotClaimItIsValidUntilSomeDate() throws Exception {
        givenExpiredCurrentAndParked(0);

        handler.sendStatus(100L, 7L);

        assertThat(captureSent().getText()).doesNotContain("Действует до");
    }

    private void givenExpiredCurrentAndParked(int parkedDays) {
        Customer u = new Customer();
        u.setTelegramId(7L);
        u.setStatus(Customer.Status.DISABLED);
        u.setExpiresAt(java.time.LocalDateTime.now().minusDays(2));
        when(accessService.getUser(anyLong())).thenReturn(Optional.of(u));
        when(subscriptionLookup.currentFor(anyLong())).thenReturn(Optional.empty());
        when(deviceTrackingService.usedSlots(anyLong())).thenReturn(0L);
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(7L)).thenReturn(java.util.List.of(
                com.orchestra.subscriptions.model.Subscription.builder()
                        .id(1L).telegramId(7L).seq(1).current(true)
                        .state(Customer.Status.DISABLED)
                        .expiresAt(java.time.LocalDateTime.now().minusDays(2)).build(),
                com.orchestra.subscriptions.model.Subscription.builder()
                        .id(2L).telegramId(7L).seq(2).current(false)
                        .state(Customer.Status.ACTIVE)
                        .balanceSeconds(com.orchestra.subscriptions.service.SubscriptionTime.daysToSeconds(parkedDays))
                        .build()));
    }

    private static java.util.List<String> markupTexts(SendMessage msg) {
        return ((org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup)
                msg.getReplyMarkup()).getKeyboard().stream()
                .flatMap(java.util.Collection::stream)
                .map(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton::getText)
                .toList();
    }

    // ===== Экран «Как подключиться»: ролик — дополнение, инструкция — суть =====

    @Test
    void sendHelp_withoutVideoConfigured_sendsInstructionOnly() throws Exception {
        when(appConfig.getTelegram().getHowToVideoId()).thenReturn(null);

        handler.sendHelp(100L);

        verify(telegramSender, never()).execute(any(
                org.telegram.telegrambots.meta.api.methods.send.SendVideo.class));
        assertThat(captureSent().getText()).contains("Как подключить доступ");
    }

    @Test
    void sendHelp_withVideoConfigured_sendsVideoByFileIdBeforeInstruction() throws Exception {
        when(appConfig.getTelegram().getHowToVideoId()).thenReturn("BAACAgIAAxk-test-file-id");

        handler.sendHelp(100L);

        ArgumentCaptor<org.telegram.telegrambots.meta.api.methods.send.SendVideo> cap =
                ArgumentCaptor.forClass(org.telegram.telegrambots.meta.api.methods.send.SendVideo.class);
        verify(telegramSender).execute(cap.capture());
        // Отправляем именно идентификатор уже залитого ролика: файла у нас на диске нет и
        // выгружать мегабайты на каждый показ экрана мы не собираемся.
        assertThat(cap.getValue().getVideo().getAttachName()).contains("BAACAgIAAxk-test-file-id");
        assertThat(cap.getValue().getChatId()).isEqualTo("100");
        assertThat(captureSent().getText()).contains("Как подключить доступ");
    }

    @Test
    void sendHelp_withAnimationPrefix_sendsAnimationNotVideo() throws Exception {
        // file_id привязан к типу: идентификатор гифки через sendVideo не отправится, и экран
        // молча остался бы без ролика.
        when(appConfig.getTelegram().getHowToVideoId()).thenReturn("animation:CgACAgIA-silent");

        handler.sendHelp(100L);

        ArgumentCaptor<org.telegram.telegrambots.meta.api.methods.send.SendAnimation> cap =
                ArgumentCaptor.forClass(org.telegram.telegrambots.meta.api.methods.send.SendAnimation.class);
        verify(telegramSender).execute(cap.capture());
        assertThat(cap.getValue().getAnimation().getAttachName()).contains("CgACAgIA-silent");
        // Пометка типа — служебная, в Telegram она уехать не должна.
        assertThat(cap.getValue().getAnimation().getAttachName()).doesNotContain("animation:");
        verify(telegramSender, never()).execute(any(
                org.telegram.telegrambots.meta.api.methods.send.SendVideo.class));
    }

    @Test
    void sendHelp_whenVideoFails_stillSendsInstruction() throws Exception {
        // Протухший file_id (ролик удалили, сменили бота) не должен оставлять человека,
        // нажавшего «Как подключиться», вообще без ответа.
        when(appConfig.getTelegram().getHowToVideoId()).thenReturn("dead-file-id");
        when(telegramSender.execute(any(
                org.telegram.telegrambots.meta.api.methods.send.SendVideo.class)))
                .thenThrow(new org.telegram.telegrambots.meta.exceptions.TelegramApiException(
                        "wrong file identifier"));

        handler.sendHelp(100L);

        assertThat(captureSent().getText()).contains("Как подключить доступ");
    }

    @Test
    void sendTerms_noOfferUrl_sendsPreparingNotice() throws Exception {
        when(appConfig.getTelegram().getOfferUrl()).thenReturn(null);

        handler.sendTerms(100L);

        SendMessage sent = captureSent();
        assertThat(sent.getText()).contains("в подготовке");
        assertThat(sent.getReplyMarkup()).isNull(); // нет кнопки, пока оферта не настроена
    }

    @Test
    void sendTerms_withOfferUrl_sendsButton() throws Exception {
        when(appConfig.getTelegram().getOfferUrl()).thenReturn("https://orchestra.example/offer");

        handler.sendTerms(100L);

        SendMessage sent = captureSent();
        assertThat(sent.getText()).contains("Публичная оферта");
        assertThat(sent.getReplyMarkup()).isNotNull(); // кнопка «Открыть оферту»
    }

    @Test
    void handleTrial_alreadyUsed_sendsError() throws Exception {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getUserName()).thenReturn("alice");
        when(accessService.createTrialAccess(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("trial already used"));

        handler.handleTrial(100L, user);

        assertThat(captureSent().getText()).contains("Пробный период уже был использован");
    }

    /**
     * Решение владельца: покупка другой ступени заводит ВТОРУЮ подписку, и человек обязан
     * узнать об этом до оплаты, а не после. Выбора при этом не предлагаем — ни кнопки
     * «заменить», ни развилки: при понижении замена ничего не возвращает, а просто сжигает
     * оплаченное.
     */
    @Test
    void paymentPicker_warnsWhenPurchaseOpensSeparateSubscription() throws Exception {
        when(appConfig.findPlan("month_1")).thenReturn(plan());
        when(appConfig.findDeviceTierOptional("d1")).thenReturn(java.util.Optional.of(tier()));
        when(paymentTargetResolver.resolve(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.orchestra.subscriptions.service.payment.PaymentTarget.separate());
        when(subscriptionLookup.currentFor(anyLong())).thenReturn(java.util.Optional.empty());

        handler.sendPaymentMethodPicker(100L, 7L, "month_1:d1");

        assertThat(captureSent().getText()).contains("вторая подписка");
    }

    @Test
    void paymentPicker_silentWhenPurchaseExtendsCurrent() throws Exception {
        when(appConfig.findPlan("month_1")).thenReturn(plan());
        when(appConfig.findDeviceTierOptional("d1")).thenReturn(java.util.Optional.of(tier()));
        when(paymentTargetResolver.resolve(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.orchestra.subscriptions.service.payment.PaymentTarget.first());

        handler.sendPaymentMethodPicker(100L, 7L, "month_1:d1");

        assertThat(captureSent().getText())
                .as("человеку, который просто продлевает, лишнее предупреждение только мешает")
                .doesNotContain("вторая подписка");
    }

    private static AppConfig.Plan plan() {
        AppConfig.Plan p = new AppConfig.Plan();
        p.setId("month_1");
        p.setName("1 месяц");
        p.setDays(30);
        p.setPriceKopeks(15000L);
        return p;
    }

    private static AppConfig.DeviceTier tier() {
        AppConfig.DeviceTier t = new AppConfig.DeviceTier();
        t.setId("d1");
        t.setName("1 устройство");
        t.setDevices(1);
        return t;
    }

    private SendMessage captureSent() throws Exception {
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).execute(cap.capture());
        return cap.getValue();
    }
}
