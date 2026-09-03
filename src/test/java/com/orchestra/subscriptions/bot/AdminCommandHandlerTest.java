package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.service.AdminService;
import com.orchestra.subscriptions.service.BroadcastService;
import com.orchestra.subscriptions.service.DynamicConfigService;
import com.orchestra.subscriptions.service.EmailService;
import com.orchestra.subscriptions.service.NotificationService;
import com.orchestra.subscriptions.service.PromoCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тест роутинга админ-команд. До выноса из god-class'а это было непроверяемо: теперь
 * отправка — {@code mock(TelegramSender.class)}, данные — моки сервисов, и можно проверить
 * диспетч (распознал/не распознал команду) и парсинг аргументов без подъёма Spring.
 */
class AdminCommandHandlerTest {

    private AppConfig appConfig;
    private AdminService adminService;
    private BroadcastService broadcastService;
    private DynamicConfigService dynamicConfigService;
    private com.orchestra.subscriptions.service.DeviceTrackingService deviceTrackingService;
    private TelegramSender telegramSender;
    private AdminCommandHandler handler;
    /** Настоящий, а не мок: тест проверяет форму ссылки, а токен для этого нужен живой. */
    private final com.orchestra.subscriptions.service.AdminLoginTokenService adminLoginTokenService =
            new com.orchestra.subscriptions.service.AdminLoginTokenService();

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class);
        adminService = mock(AdminService.class);
        PromoCodeService promoCodeService = mock(PromoCodeService.class);
        broadcastService = mock(BroadcastService.class);
        NotificationService notificationService = mock(NotificationService.class);
        EmailService emailService = mock(EmailService.class);
        dynamicConfigService = mock(DynamicConfigService.class);
        deviceTrackingService = mock(com.orchestra.subscriptions.service.DeviceTrackingService.class);
        telegramSender = mock(TelegramSender.class);
        handler = new AdminCommandHandler(appConfig, adminService, promoCodeService,
                broadcastService, notificationService, emailService, dynamicConfigService,
                deviceTrackingService, mock(com.orchestra.subscriptions.service.OnlineTracker.class),
                mock(com.orchestra.subscriptions.service.SubscriptionLedgerService.class),
                mock(com.orchestra.subscriptions.service.SubscriptionRegistryService.class),
                mock(com.orchestra.subscriptions.service.SubscriptionSwitchService.class),
                mock(com.orchestra.subscriptions.repository.SubscriptionRepository.class),
                adminLoginTokenService,
                telegramSender);
    }

    @Test
    void tryHandleAdminCommand_admin_sendsPanelLinkToOwnerWithoutPreview() throws Exception {
        com.orchestra.subscriptions.config.AppConfig.TelegramConfig telegram = new com.orchestra.subscriptions.config.AppConfig.TelegramConfig();
        telegram.setOwnerId(999L);
        telegram.setPublicBaseUrl("https://sub.example.com:8443/");
        when(appConfig.getTelegram()).thenReturn(telegram);

        assertThat(handler.tryHandleAdminCommand("/admin", 999L)).isTrue();

        org.mockito.ArgumentCaptor<org.telegram.telegrambots.meta.api.methods.send.SendMessage> captor =
                org.mockito.ArgumentCaptor.forClass(
                        org.telegram.telegrambots.meta.api.methods.send.SendMessage.class);
        verify(telegramSender).execute(captor.capture());
        var sent = captor.getValue();

        assertThat(sent.getChatId()).isEqualTo("999");
        // Двойного слеша быть не должно: base-url в конфиге может приехать со слешем на конце.
        assertThat(sent.getText()).contains("https://sub.example.com:8443/account/admin/enter?token=");
        assertThat(sent.getDisableWebPagePreview()).isTrue();
    }

    @Test
    void handleVideoFileId_animation_marksTypeInEnvLine() throws Exception {
        // mp4 без звуковой дорожки Telegram считает гифкой и кладёт в message.animation.
        // Молчать на это нельзя: снаружи «ничего не происходит» неотличимо от поломки.
        org.telegram.telegrambots.meta.api.objects.games.Animation animation =
                mock(org.telegram.telegrambots.meta.api.objects.games.Animation.class);
        when(animation.getFileId()).thenReturn("CgACAgIAAxk-silent");
        when(animation.getWidth()).thenReturn(768);
        when(animation.getHeight()).thenReturn(1280);
        when(animation.getDuration()).thenReturn(18);
        when(animation.getFileSize()).thenReturn(1058005L);
        org.telegram.telegrambots.meta.api.objects.message.Message message =
                mock(org.telegram.telegrambots.meta.api.objects.message.Message.class);
        when(message.getAnimation()).thenReturn(animation);
        org.telegram.telegrambots.meta.api.objects.Update update =
                mock(org.telegram.telegrambots.meta.api.objects.Update.class);
        when(update.getMessage()).thenReturn(message);

        handler.handleVideoFileId(update, 999L);

        String text = captureSent().getText();
        // Пометка обязательна: file_id привязан к типу, и без неё экран помощи попытается
        // отправить анимацию как видео — и промолчит.
        assertThat(text).contains("HOW_TO_VIDEO_ID=animation:CgACAgIAAxk-silent");
        assertThat(text).contains("нет звуковой дорожки");
    }

    @Test
    void handleVideoFileId_document_asksToResendAsVideoInsteadOfOfferingUselessId() throws Exception {
        // Идентификатор документа отправляется только документом — на экране это карточка
        // файла, а не проигрыватель. Предлагать его было бы приглашением в тупик.
        org.telegram.telegrambots.meta.api.objects.Document document =
                mock(org.telegram.telegrambots.meta.api.objects.Document.class);
        when(document.getFileId()).thenReturn("BQACAgIAAxk-as-file");
        org.telegram.telegrambots.meta.api.objects.message.Message message =
                mock(org.telegram.telegrambots.meta.api.objects.message.Message.class);
        when(message.getDocument()).thenReturn(document);
        org.telegram.telegrambots.meta.api.objects.Update update =
                mock(org.telegram.telegrambots.meta.api.objects.Update.class);
        when(update.getMessage()).thenReturn(message);

        handler.handleVideoFileId(update, 999L);

        String text = captureSent().getText();
        assertThat(text).contains("как видео");
        assertThat(text).doesNotContain("BQACAgIAAxk-as-file");
        assertThat(text).doesNotContain("HOW_TO_VIDEO_ID");
    }

    @Test
    void handleVideoFileId_answersWithIdentifierAndWhereToPutIt() throws Exception {
        // file_id существует только внутри Telegram: получить его можно ровно так — прислать
        // боту видео. Ответ должен содержать сам идентификатор и место, куда его класть.
        org.telegram.telegrambots.meta.api.objects.Video video =
                mock(org.telegram.telegrambots.meta.api.objects.Video.class);
        when(video.getFileId()).thenReturn("BAACAgIAAxkBAAIC-how-to");
        when(video.getWidth()).thenReturn(768);
        when(video.getHeight()).thenReturn(1280);
        when(video.getDuration()).thenReturn(18);
        when(video.getFileSize()).thenReturn(1058005L);
        org.telegram.telegrambots.meta.api.objects.message.Message message =
                mock(org.telegram.telegrambots.meta.api.objects.message.Message.class);
        when(message.getVideo()).thenReturn(video);
        org.telegram.telegrambots.meta.api.objects.Update update =
                mock(org.telegram.telegrambots.meta.api.objects.Update.class);
        when(update.getMessage()).thenReturn(message);

        handler.handleVideoFileId(update, 999L);

        SendMessage sent = captureSent();
        assertThat(sent.getText())
                // Строка для .env отдаётся целиком, чтобы её копировали, а не собирали руками.
                .contains("HOW_TO_VIDEO_ID=BAACAgIAAxkBAAIC-how-to")
                .doesNotContain("animation:")
                .contains("768×1280");
        assertThat(sent.getParseMode()).isEqualTo("HTML");
    }

    @Test
    void tryHandleAdminCommand_devices_rendersDeviceList() throws Exception {
        com.orchestra.subscriptions.model.Customer user = com.orchestra.subscriptions.model.Customer.builder()
                .telegramId(555L).telegramUsername("bob").deviceLimit(3).deviceTierId("d3").build();
        when(adminService.findUserBySubject("@bob")).thenReturn(java.util.Optional.of(user));
        when(deviceTrackingService.usedSlots(555L)).thenReturn(1L);
        when(deviceTrackingService.listDevices(555L)).thenReturn(List.of(
                com.orchestra.subscriptions.model.UserDevice.builder()
                        .id(1L).telegramId(555L)
                        .hwid("aaaaaaaa-1111-2222-3333-444444444444")
                        .deviceModel("iPhone15,2").deviceOs("iOS").osVersion("18.3")
                        .customName("телефон мамы")
                        .status(com.orchestra.subscriptions.model.UserDevice.Status.ACTIVE)
                        .firstSeenAt(java.time.LocalDateTime.now().minusDays(3))
                        .lastSeenAt(java.time.LocalDateTime.now())
                        .build()));

        boolean handled = handler.tryHandleAdminCommand("/devices @bob", 100L);

        assertThat(handled).isTrue();
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).execute(cap.capture());
        String text = cap.getValue().getText();
        assertThat(text).contains("@bob")
                .contains("1 из 3")
                .contains("телефон мамы")
                .contains("iPhone 14 Pro (iOS 18.3)")
                // HWID замаскирован: первые 8 + последние 4
                .contains("aaaaaaaa…4444")
                .doesNotContain("aaaaaaaa-1111-2222-3333-444444444444");
    }

    @Test
    void tryHandleAdminCommand_devices_userNotFound_warns() throws Exception {
        when(adminService.findUserBySubject("777")).thenReturn(java.util.Optional.empty());

        boolean handled = handler.tryHandleAdminCommand("/devices 777", 100L);

        assertThat(handled).isTrue();
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("не найден");
    }

    @Test
    void tryHandleAdminCommand_unknownCommand_returnsFalseAndSendsNothing() throws Exception {
        // /start — не админ-команда: хендлер не должен её перехватывать (вернёт false,
        // SubscriptionBot обработает обычным switch'ем).
        boolean handled = handler.tryHandleAdminCommand("/start", 100L);

        assertThat(handled).isFalse();
        verifyNoInteractions(telegramSender);
        verifyNoInteractions(adminService);
    }

    @Test
    void tryHandleAdminCommand_disableWithValidId_callsServiceAndConfirms() throws Exception {
        when(adminService.disableSubscription(123L)).thenReturn(true);

        boolean handled = handler.tryHandleAdminCommand("/disable 123", 100L);

        assertThat(handled).isTrue();
        verify(adminService).disableSubscription(123L);
        assertThat(captureSent().getText()).contains("отключена").contains("123");
    }

    @Test
    void tryHandleAdminCommand_disableWithBadId_showsUsageAndSkipsService() throws Exception {
        boolean handled = handler.tryHandleAdminCommand("/disable notanumber", 100L);

        assertThat(handled).isTrue(); // команда распознана, но аргумент невалиден
        verify(adminService, never()).disableSubscription(anyLong());
        assertThat(captureSent().getText()).contains("Использование");
    }

    @Test
    void tryHandleAdminCommand_extendWithoutArgs_showsUsage() throws Exception {
        boolean handled = handler.tryHandleAdminCommand("/extend", 100L);

        assertThat(handled).isTrue(); // распознана, но без аргументов → usage, без вызова сервиса
        assertThat(captureSent().getText()).contains("Использование");
    }

    @Test
    void handleAdminCallback_cancel_consumesDraftAndReplies() throws Exception {
        handler.handleAdminCallback(100L, "admin:broadcast:cancel:draft_1");

        verify(broadcastService).consumeDraft("draft_1");
        assertThat(captureSent().getText()).contains("отменена");
    }

    @Test
    void handleAdminCallback_disabled_runsWinbackAudience() throws Exception {
        BroadcastService.BroadcastContent content = BroadcastService.BroadcastContent.html("вернитесь");
        when(broadcastService.consumeDraft("draft_2")).thenReturn(content);
        when(broadcastService.execute(any(BroadcastService.BroadcastContent.class),
                any(BroadcastService.Target.class)))
                .thenReturn(new java.util.concurrent.CompletableFuture<>());

        handler.handleAdminCallback(100L, "admin:broadcast:disabled:draft_2");

        verify(broadcastService).execute(content, BroadcastService.Target.DISABLED);
        assertThat(captureSent().getText()).contains("отключившимся");
    }

    /** Картинка живёт в черновике до подтверждения — иначе кнопка разослала бы голый текст. */
    @Test
    void handleAdminCallback_shouldPassPhotoDraftThrough() throws Exception {
        BroadcastService.BroadcastContent content = new BroadcastService.BroadcastContent(
                "Смотрите", java.util.List.of(), "AgACfile", true);
        when(broadcastService.consumeDraft("draft_photo")).thenReturn(content);
        when(broadcastService.execute(any(BroadcastService.BroadcastContent.class),
                any(BroadcastService.Target.class)))
                .thenReturn(new java.util.concurrent.CompletableFuture<>());

        handler.handleAdminCallback(100L, "admin:broadcast:active:draft_photo");

        verify(broadcastService).execute(content, BroadcastService.Target.ACTIVE);
        assertThat(captureSent().getText()).contains("с фото");
    }

    /**
     * Самая дорогая ошибка в этом коде — не «кнопка не сработала», а «сработала не та».
     * Раньше цель выбиралась тернарником, где ВСЁ, что не «active», означало ALL: любая
     * опечатка в callback-данных превращалась в рассылку по всей базе, а её не отменить.
     * Теперь неизвестное действие не запускает ничего.
     */
    @Test
    void handleAdminCallback_unknownAction_doesNotFallBackToEveryone() throws Exception {
        when(broadcastService.consumeDraft("draft_3"))
                .thenReturn(BroadcastService.BroadcastContent.html("текст"));
        // Заглушка нужна, чтобы при регрессии тест падал на понятном «execute не должен был
        // вызываться», а не на NullPointer от неотстабленного мока: разбирать чужое падение
        // будут по тексту ошибки.
        when(broadcastService.execute(any(BroadcastService.BroadcastContent.class),
                any(BroadcastService.Target.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        new BroadcastService.BroadcastStats(0, 0, java.time.Duration.ZERO)));

        handler.handleAdminCallback(100L, "admin:broadcast:actve:draft_3");

        verify(broadcastService, never()).execute(any(BroadcastService.BroadcastContent.class),
                any(BroadcastService.Target.class));
        assertThat(captureSent().getText()).contains("Непонятная кнопка");
    }

    @Test
    void tryHandleAdminCommand_nodes_callsServiceAndSendsHtml() throws Exception {
        when(adminService.getNodeStatuses()).thenReturn(List.of(
                new AdminService.NodeStatus("🇪🇪 Estonia", "connected", "203.0.113.10", true, null, "25.3.6")));

        boolean handled = handler.tryHandleAdminCommand("/nodes", 100L);

        assertThat(handled).isTrue();
        verify(adminService).getNodeStatuses();
        SendMessage sent = captureSent();
        assertThat(sent.getParseMode()).isEqualTo("HTML");
        assertThat(sent.getText()).contains("Estonia").contains("🟢 :443").contains("ядро 25.3.6");
    }

    /**
     * История срока должна быть адресной: у человека с двумя подписками записи о разных
     * подписках выглядели одинаково, и вопрос «куда делись дни ЭТОЙ подписки» по /ledger
     * было не разобрать. Номер берём тот же, который человек видит на своём экране, — иначе
     * владелец сопоставляет id вручную.
     */
    @Test
    void formatLedgerMarksSubscriptionWhenThereAreSeveral() {
        com.orchestra.subscriptions.model.Customer user = com.orchestra.subscriptions.model.Customer.builder().telegramId(77L).telegramUsername("tester").build();
        var rows = List.of(
                com.orchestra.subscriptions.model.SubscriptionLedger.builder()
                        .occurredAt(java.time.LocalDateTime.now())
                        .kind(com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PURCHASE)
                        .subscriptionId(57L).deltaSeconds(30L * 86400).balanceAfterSeconds(30L * 86400)
                        .build(),
                com.orchestra.subscriptions.model.SubscriptionLedger.builder()
                        .occurredAt(java.time.LocalDateTime.now())
                        .kind(com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PARK)
                        .subscriptionId(58L).deltaSeconds(0L).balanceAfterSeconds(12L * 86400)
                        .build());

        String out = AdminCommandHandler.formatLedger(user, rows, Map.of(57L, 1, 58L, 2));

        assertThat(out).contains("подписка #1").contains("подписка #2");
    }

    /** У человека с одной подпиской пометка только шумела бы — её быть не должно. */
    @Test
    void formatLedgerStaysQuietForSingleSubscription() {
        com.orchestra.subscriptions.model.Customer user = com.orchestra.subscriptions.model.Customer.builder().telegramId(77L).build();
        var rows = List.of(com.orchestra.subscriptions.model.SubscriptionLedger.builder()
                .occurredAt(java.time.LocalDateTime.now())
                .kind(com.orchestra.subscriptions.model.SubscriptionLedger.Kind.PURCHASE)
                .subscriptionId(57L).deltaSeconds(30L * 86400).balanceAfterSeconds(30L * 86400)
                .build());

        assertThat(AdminCommandHandler.formatLedger(user, rows, Map.of(57L, 1)))
                .doesNotContain("подписка #");
    }

    @Test
    void formatNodes_emptyList_showsPanelUnreachableHint() {
        String out = AdminCommandHandler.formatNodes(List.of());
        assertThat(out).contains("Панель не вернула ноды").contains("provider-provider-1");
    }

    @Test
    void formatNodes_connectedButPortClosed_flagsDpiSuspicion() {
        String out = AdminCommandHandler.formatNodes(List.of(
                new AdminService.NodeStatus("🇱🇻 Latvia 1", "connected", "203.0.113.11", false, null, null)));
        assertThat(out).contains("🔴 :443")
                .contains("возможен per-IP DPI")
                .contains("diag-node.sh");
    }

    @Test
    void formatNodes_escapesHtmlInPanelMessage() {
        String out = AdminCommandHandler.formatNodes(List.of(
                new AdminService.NodeStatus("Node", "error", "203.0.113.12", false, "boom <b>x</b>", null)));
        assertThat(out).contains("boom &lt;b&gt;x&lt;/b&gt;");
    }

    @Test
    void tryHandleAdminCommand_reloadconfig_previewsWithoutApplying() throws Exception {
        when(dynamicConfigService.preview()).thenReturn(new DynamicConfigService.ReloadResult(
                true, null, "/opt/subscription-service/config/app-dynamic.yml",
                List.of("ноды (servers): 5 → 6 ⟵ изменится"), false));

        boolean handled = handler.tryHandleAdminCommand("/reloadconfig", 100L);

        assertThat(handled).isTrue();
        verify(dynamicConfigService).preview();
        verify(dynamicConfigService, never()).apply();
        assertThat(captureSent().getText()).contains("Превью").contains("/reloadconfig apply");
    }

    @Test
    void tryHandleAdminCommand_reloadconfigApply_applies() throws Exception {
        when(dynamicConfigService.apply()).thenReturn(new DynamicConfigService.ReloadResult(
                true, null, "/opt/subscription-service/config/app-dynamic.yml",
                List.of("ноды (servers): 5 → 6 ⟵ изменится"), true));

        boolean handled = handler.tryHandleAdminCommand("/reloadconfig apply", 100L);

        assertThat(handled).isTrue();
        verify(dynamicConfigService).apply();
        assertThat(captureSent().getText()).contains("применён");
    }

    @Test
    void formatReload_errorResult_showsHint() {
        String out = AdminCommandHandler.formatReload(
                new DynamicConfigService.ReloadResult(false, "Файл не читается: /x", null, List.of(), false));
        assertThat(out).contains("❌").contains("Файл не читается");
    }

    // ========================
    // /broadcast с картинкой
    // ========================

    /**
     * Картинку в рассылку можно занести только присланным сообщением: {@code file_id}
     * существует лишь внутри Telegram. Проверяем весь путь — подпись читается как команда,
     * из лесенки размеров берётся самый крупный, premium-эмодзи доезжает до черновика.
     */
    @Test
    void handleOwnerPhoto_broadcastCaption_buildsDraftWithPhotoAndPremiumEmoji() throws Exception {
        String caption = "/broadcast Скидка 🎉 на всё";
        Update update = photoUpdate(caption, List.of(
                entity("bot_command", 0, 10),
                org.telegram.telegrambots.meta.api.objects.MessageEntity.builder()
                        .type("custom_emoji").offset(caption.indexOf("🎉")).length(2)
                        .customEmojiId("5000000000000000001").build()));
        when(broadcastService.prepare(any(BroadcastService.BroadcastContent.class)))
                .thenReturn(new BroadcastService.BroadcastDraft(
                        "draft_x", BroadcastService.BroadcastContent.html("x"), 3, 1, 4));

        handler.handleOwnerPhoto(update, 100L);

        ArgumentCaptor<BroadcastService.BroadcastContent> cap =
                ArgumentCaptor.forClass(BroadcastService.BroadcastContent.class);
        verify(broadcastService).prepare(cap.capture());
        BroadcastService.BroadcastContent content = cap.getValue();
        assertThat(content.text()).isEqualTo("Скидка 🎉 на всё");
        assertThat(content.photoFileId()).as("берём самый крупный размер").isEqualTo("file-big");
        assertThat(content.html()).isFalse();
        assertThat(content.customEmojiCount()).isEqualTo(1);
        var premium = content.entities().stream()
                .filter(e -> "custom_emoji".equals(e.getType())).findFirst().orElseThrow();
        assertThat(content.text().substring(premium.getOffset(),
                premium.getOffset() + premium.getLength())).isEqualTo("🎉");
    }

    /** Превью — это сам черновик, отправленный владельцу: иначе эмодзи «показать» нечем. */
    @Test
    void handleOwnerPhoto_broadcastCaption_sendsPreviewBeforeButtons() throws Exception {
        when(broadcastService.prepare(any(BroadcastService.BroadcastContent.class)))
                .thenReturn(new BroadcastService.BroadcastDraft(
                        "draft_x", BroadcastService.BroadcastContent.html("x"), 3, 1, 4));

        handler.handleOwnerPhoto(photoUpdate("/broadcast Смотрите", List.of()), 100L);

        // Порядок здесь и есть проверяемое: карточка говорит «выше — ровно то, что получат
        // люди», и если превью уедет после неё, эта фраза будет указывать в пустоту. Двух
        // независимых verify для такого мало — они порядок не проверяют вовсе.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(broadcastService, telegramSender);
        inOrder.verify(broadcastService).send(eq("100"), any(BroadcastService.BroadcastContent.class));
        inOrder.verify(telegramSender).execute(any(SendMessage.class));
        assertThat(captureSent().getText())
                .contains("Черновик рассылки")
                .contains("фото с подписью");
    }

    /**
     * Вторая половина того же порядка: превью — ещё и репетиция запроса. Не ушло владельцу —
     * значит, не уйдёт и людям, и кнопок подтверждения быть не должно, а черновик надо
     * погасить: показать его уже нечем.
     */
    @Test
    void handleOwnerPhoto_whenPreviewFails_suppressesButtonsAndDropsDraft() throws Exception {
        when(broadcastService.prepare(any(BroadcastService.BroadcastContent.class)))
                .thenReturn(new BroadcastService.BroadcastDraft(
                        "draft_x", BroadcastService.BroadcastContent.html("x"), 3, 1, 4));
        org.mockito.Mockito.doThrow(new org.telegram.telegrambots.meta.exceptions.TelegramApiException(
                        "can't parse entities"))
                .when(broadcastService).send(anyString(), any(BroadcastService.BroadcastContent.class));

        handler.handleOwnerPhoto(photoUpdate("/broadcast <b>сломано", List.of()), 100L);

        verify(broadcastService).consumeDraft("draft_x");
        SendMessage sent = captureSent();
        assertThat(sent.getText()).contains("не отправился даже вам").contains("Рассылка не создана");
        assertThat(sent.getReplyMarkup()).as("кнопок подтверждения быть не должно").isNull();
    }

    /** Подпись без команды — картинка не рассылка, а заготовка: отвечаем подсказкой. */
    @Test
    void handleOwnerPhoto_withoutCommand_repliesWithHintAndFileId() throws Exception {
        handler.handleOwnerPhoto(photoUpdate(null, null), 100L);

        verifyNoInteractions(broadcastService);
        assertThat(captureSent().getText()).contains("/broadcast").contains("file-big");
    }

    /** Слишком длинная подпись — отказ до создания черновика, а не 400 у каждого получателя. */
    @Test
    void handleOwnerPhoto_captionOverCaptionLimit_refusesBeforeDraft() throws Exception {
        handler.handleOwnerPhoto(photoUpdate("/broadcast " + "я".repeat(1100), List.of()), 100L);

        verify(broadcastService, never()).prepare(any(BroadcastService.BroadcastContent.class));
        assertThat(captureSent().getText())
                .contains("лимита Telegram").contains("1024")
                // Совет «разошлите без фото» без объяснения, как именно, отправлял бы искать
                // несуществующий ключ: картинка цепляется и вложением, и ответом на сообщение.
                .contains("отдельным сообщением");
    }

    /**
     * Тест-отправка одному обязана рендериться 1-в-1 с массовой — иначе проверять ею нечего.
     * Поэтому она идёт тем же {@code BroadcastService.send}, включая картинку.
     */
    @Test
    void handleOwnerPhoto_broadcastToCaption_sendsSamePhotoToOneRecipient() throws Exception {
        handler.handleOwnerPhoto(photoUpdate("/broadcast_to 555 Проверка", List.of()), 100L);

        ArgumentCaptor<BroadcastService.BroadcastContent> cap =
                ArgumentCaptor.forClass(BroadcastService.BroadcastContent.class);
        verify(broadcastService).send(eq("555"), cap.capture());
        assertThat(cap.getValue().photoFileId()).isEqualTo("file-big");
        assertThat(cap.getValue().text()).isEqualTo("Проверка");
        verify(broadcastService, never()).prepare(any(BroadcastService.BroadcastContent.class));
    }

    /** Перенос строки между id и текстом Telegram отдаёт как есть — это не «кривой id». */
    @Test
    void tryHandleAdminCommand_broadcastTo_shouldAcceptNewlineBetweenIdAndText() throws Exception {
        boolean handled = handler.tryHandleAdminCommand("/broadcast_to 555\nПривет", 100L);

        assertThat(handled).isTrue();
        ArgumentCaptor<BroadcastService.BroadcastContent> cap =
                ArgumentCaptor.forClass(BroadcastService.BroadcastContent.class);
        verify(broadcastService).send(eq("555"), cap.capture());
        assertThat(cap.getValue().text()).isEqualTo("Привет");
    }

    private static Update photoUpdate(String caption,
                                      List<org.telegram.telegrambots.meta.api.objects.MessageEntity> entities) {
        org.telegram.telegrambots.meta.api.objects.message.Message message =
                new org.telegram.telegrambots.meta.api.objects.message.Message();
        message.setPhoto(List.of(photoSize("file-small", 90), photoSize("file-big", 1280)));
        message.setCaption(caption);
        message.setCaptionEntities(entities);
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static org.telegram.telegrambots.meta.api.objects.photo.PhotoSize photoSize(String id, int side) {
        return org.telegram.telegrambots.meta.api.objects.photo.PhotoSize.builder()
                .fileId(id).fileUniqueId(id).width(side).height(side).build();
    }

    private static org.telegram.telegrambots.meta.api.objects.MessageEntity entity(
            String type, int offset, int length) {
        return org.telegram.telegrambots.meta.api.objects.MessageEntity.builder()
                .type(type).offset(offset).length(length).build();
    }

    private SendMessage captureSent() throws Exception {
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender).execute(cap.capture());
        return cap.getValue();
    }
}
