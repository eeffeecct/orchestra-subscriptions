package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.service.PendingGrantService;
import com.orchestra.subscriptions.util.Keyboards;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Set;

@Slf4j
@Component
public class SubscriptionBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final AppConfig appConfig;
    private final PendingGrantService pendingGrantService;
    /** Отправка в Telegram. Вынесена в отдельный бин — см. {@link TelegramSender}. */
    private final TelegramSender telegramSender;
    /**
     * Owner-only админ-команды и admin-callback'и. {@code @Lazy}, потому что
     * AdminCommandHandler тянет NotificationService, а тот инжектит SubscriptionBot — цикл,
     * который Lazy разрывает на старте.
     */
    private final AdminCommandHandler adminHandler;
    /**
     * Money-path оплаты (in-chat создание счетов + pre_checkout/successful_payment).
     * {@code @Lazy} — цикл SubscriptionBot → PaymentHandler → PaymentProcessingService →
     * NotificationService → SubscriptionBot разрывается на старте.
     */
    private final PaymentHandler paymentHandler;
    /**
     * Пользовательские экраны (статус/кабинет/покупка/история/чеки/поддержка/помощь/оферта/
     * рефералка). Eager: зависит только от листьев (нет NotificationService/PaymentProcessingService),
     * цикла с SubscriptionBot нет.
     */
    private final ScreenHandler screenHandler;
    /** Автопродление (recurring). {@code @Lazy} — autoRenewService в цикле через
     *  NotificationService → SubscriptionBot. */
    private final AutoRenewHandler autoRenewHandler;
    /** Онбординг/вход: welcome, нижнее меню, deep-link реферала, gate триала. Eager
     *  (зависит только от листьев, цикла с SubscriptionBot нет). */
    private final OnboardingHandler onboardingHandler;
    /** Экран «Мои подписки»: список и переключение. */
    private final SubscriptionScreenHandler subscriptionScreenHandler;
    /**
     * Обязательная подписка на новостной канал. Eager: зависит от листьев
     * (AppConfig/ChannelMembershipService/TelegramSender/OnboardingHandler).
     */
    private final ChannelGateHandler channelGateHandler;
    /**
     * Роутер inline-кнопок (callback_data → хендлер). {@code @Lazy} — тянет хендлеры,
     * часть которых замыкается на SubscriptionBot через NotificationService/PaymentProcessingService.
     */
    private final CallbackRouter callbackRouter;

    /** Reply-флоу (промокод/имя устройства/email): что бот ждёт следующим сообщением. */
    private final PendingInputService pendingInputService;
    /**
     * Диспетчер reply-ввода. {@code @Lazy}: тянет EmailBotHandler → EmailSettingsService →
     * NotificationService → SubscriptionBot — цикл рвётся здесь (как у остальных @Lazy-хендлеров).
     */
    private final UserInputHandler userInputHandler;

    public SubscriptionBot(AppConfig appConfig,
                  PendingGrantService pendingGrantService,
                  TelegramSender telegramSender,
                  @Lazy AdminCommandHandler adminHandler,
                  @Lazy PaymentHandler paymentHandler,
                  ScreenHandler screenHandler,
                  @Lazy AutoRenewHandler autoRenewHandler,
                  OnboardingHandler onboardingHandler,
                  SubscriptionScreenHandler subscriptionScreenHandler,
                  ChannelGateHandler channelGateHandler,
                  @Lazy CallbackRouter callbackRouter,
                  PendingInputService pendingInputService,
                  @Lazy UserInputHandler userInputHandler) {
        this.appConfig = appConfig;
        this.telegramSender = telegramSender;
        this.pendingGrantService = pendingGrantService;
        this.adminHandler = adminHandler;
        this.paymentHandler = paymentHandler;
        this.screenHandler = screenHandler;
        this.autoRenewHandler = autoRenewHandler;
        this.onboardingHandler = onboardingHandler;
        this.subscriptionScreenHandler = subscriptionScreenHandler;
        this.channelGateHandler = channelGateHandler;
        this.callbackRouter = callbackRouter;
        this.pendingInputService = pendingInputService;
        this.userInputHandler = userInputHandler;
        // Диагностика на старте: видно в логах, включены ли анимированные эмодзи и сколько id
        // загружено. Проверка для прода: docker compose logs app | grep "Animated emoji".
        log.info("Animated emoji: enabled={}, ids={}",
                appConfig.getEmoji() != null && appConfig.getEmoji().isEnabled(),
                (appConfig.getEmoji() != null && appConfig.getEmoji().getIds() != null)
                        ? appConfig.getEmoji().getIds().size() : 0);
    }

    @Override
    public String getBotToken() {
        return appConfig.getTelegram().getBotToken();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    /**
     * Отправка в Telegram. В 9.x приём апдейтов (этот класс) и отправка ({@link TelegramSender})
     * разделены, но сохраняем привычный {@code execute(...)} как делегат — чтобы внешний код
     * (NotificationService, BroadcastService, BotConfig, WebAppController) и внутренние вызовы
     * не переписывать.
     * <p>pre_checkout_query (для Stars) приходит по умолчанию — в 9.x long polling не ограничивает
     * allowed_updates, если явно не задано, поэтому отдельная настройка как в 6.x больше не нужна.
     */
    public <T extends java.io.Serializable, M extends BotApiMethod<T>> T execute(M method) throws TelegramApiException {
        return telegramSender.execute(method);
    }

    @Override
    public void consume(Update update) {
        try {
            // pre_checkout_query приходит от Telegram при Stars-оплате. Отвечать за ≤10 секунд.
            if (update.hasPreCheckoutQuery()) {
                paymentHandler.handlePreCheckout(update.getPreCheckoutQuery());
                return;
            }

            if (update.hasMessage()) {
                // successful_payment прилетает как поле message — это уведомление об успешной оплате Stars.
                if (update.getMessage().getSuccessfulPayment() != null) {
                    paymentHandler.handleSuccessfulPayment(update);
                    return;
                }
                // Картинка от владельца — заготовка для рассылки: команда читается из подписи
                // к ней (/broadcast, /broadcast_to), подпись без команды получает подсказку и
                // file_id. Проверка идёт до hasText(): у фото текста нет, подпись лежит в
                // caption, и обычный текстовый маршрут такое сообщение просто не увидел бы.
                if (update.getMessage().hasPhoto()) {
                    if (isOwner(update.getMessage().getFrom().getId())) {
                        adminHandler.handleOwnerPhoto(update, update.getMessage().getChatId());
                    } else if (update.getMessage().getCaption() != null) {
                        // Подпись к картинке — такой же способ дёрнуть owner-команду, как и
                        // обычный текст, и перебор через неё должен быть виден в логах ровно
                        // так же. Отвечать по-прежнему не отвечаем.
                        logAdminCommandAttempt(update.getMessage().getCaption(),
                                update.getMessage().getFrom().getId());
                    }
                    return;
                }
                // Ролик от владельца — отвечаем его file_id (нужен для HOW_TO_VIDEO_ID).
                // Три поля, а не одно: mp4 без звуковой дорожки Telegram считает гифкой
                // (message.animation), присланный «как файл» — документом. Проверка только
                // getVideo() молчала на двух случаях из трёх, и это выглядело как поломка.
                // От всех остальных ролики игнорируем молча, как и админ-команды.
                if (update.getMessage().getVideo() != null
                        || update.getMessage().getAnimation() != null
                        || update.getMessage().getDocument() != null) {
                    if (isOwner(update.getMessage().getFrom().getId())) {
                        adminHandler.handleVideoFileId(update, update.getMessage().getChatId());
                    }
                    return;
                }
                if (update.getMessage().hasText()) {
                    handleMessage(update);
                }
            }

            if (update.hasCallbackQuery()) {
                callbackRouter.route(update);
            }
        } catch (Exception e) {
            log.error("Error processing update", e);
        }
    }

    // ========================
    // Обработка команд
    // ========================

    private void handleMessage(Update update) throws TelegramApiException {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        User user = update.getMessage().getFrom();

        // Наблюдаемость: до этого попытка чужого дёрнуть owner-команду не оставляла ни
        // ответа, ни следа в логах — перебор всей админки был невидим. Отвечать по-прежнему
        // не отвечаем (см. silent ignore ниже), но факт фиксируем.
        if (text.startsWith("/") && !isOwner(user.getId())) {
            logAdminCommandAttempt(text, user.getId());
        }

        // Админ-команды (распознаются по первому токену, содержат аргументы).
        // Silent ignore для не-владельца: не палим, что команда существует.
        // Сообщение передаётся целиком, а не одним текстом: /broadcast достаёт из него
        // разметку (в ней живут premium-эмодзи) и картинку из ответа.
        if (text.startsWith("/") && isOwner(user.getId())
                && adminHandler.tryHandleAdminCommand(text, chatId, update.getMessage())) {
            return;
        }

        // /emojiid: вернуть custom_emoji_id из присланного/отвеченного сообщения, чтобы владелец
        // сам заполнил app.emoji.ids. Нужен доступ к entities → обрабатываем здесь, с полным
        // Update (а не в tryHandleAdminCommand, который видит только текст).
        // Доступ: владелец, ИЛИ если owner-id не задан вовсе (dev-профиль) — команда безвредна,
        // отдаёт лишь id эмодзи из СОБСТВЕННОГО сообщения отправителя. В проде owner-id задан →
        // доступен только владельцу.
        if (text.startsWith("/emojiid")
                && (isOwner(user.getId()) || appConfig.getTelegram().getOwnerId() == null)) {
            adminHandler.handleEmojiIdCommand(update, chatId);
            return;
        }

        // Deep-link реферала фиксируем ДО гейта канала: параметр /start ref_… приезжает ровно
        // один раз, и если человек упрётся в экран подписки, повторного /start с ним уже не
        // будет — приглашение потерялось бы вместе с бонусом пригласившему.
        if (text.startsWith("/start ")) {
            onboardingHandler.handleStartParam(user.getId(), text.substring("/start ".length()).trim());
        }

        // Обязательная подписка на новостной канал. Стоит выше всех пользовательских экранов
        // (и выше reply-флоу, и выше нижнего меню) — проверка, разложенная по экранам,
        // обходится любым забытым вызовом. Владелец и деньги гейт не задевают: см.
        // ChannelGateHandler. Pending grant специально НИЖЕ: он идемпотентен и отработает на
        // следующем /start, а тратить резерв на человека, который до бота не дошёл, незачем.
        if (channelGateHandler.interceptMessage(chatId, user.getId())) {
            return;
        }

        // Reply-флоу: бот ждёт ввод (промокод / имя устройства / email). /cancel отменяет;
        // любая команда или кнопка нижнего меню сбрасывает ожидание (юзер передумал);
        // обычный текст при активном ожидании — это ввод, а не команда.
        if ("/cancel".equals(text)) {
            boolean had = pendingInputService.hasPending(user.getId());
            pendingInputService.clear(user.getId());
            telegramSender.execute(new org.telegram.telegrambots.meta.api.methods.send.SendMessage(
                    chatId.toString(), had ? "Ок, отменил." : "Нечего отменять."));
            return;
        }
        if (text.startsWith("/") || Keyboards.isMenuButton(text)) {
            pendingInputService.clear(user.getId());
        } else {
            var pendingInput = pendingInputService.consume(user.getId());
            if (pendingInput.isPresent()) {
                userInputHandler.handle(pendingInput.get(), text, chatId, user);
                return;
            }
        }

        // Постоянное нижнее меню (reply keyboard) присылает текст кнопки как обычное
        // сообщение — роутим его раньше обычных команд.
        if (Keyboards.isMenuButton(text)) {
            handleMenuText(text, chatId, user);
            return;
        }

        // Pending grant: победитель конкурса, которому админ заранее зарезервировал дни через
        // /grant @username 30, при первом /start (с параметром или без) получает свою подписку
        // автоматически + DM с поздравлением. applyIfAny идемпотентен — повторные /start
        // ничего не делают, найдут пустой список pending'ов. Делаем ДО sendWelcome, чтобы при
        // показе welcome подписка уже была активна (sendStatus в welcome покажет правильный
        // expires_at, а не «нет подписки»).
        if (text.startsWith("/start")) {
            try {
                pendingGrantService.applyIfAny(user.getId(), user.getUserName());
            } catch (Exception e) {
                // Не роняем welcome из-за проблем с грантом.
                log.error("applyIfAny failed for telegramId={} @{}", user.getId(), user.getUserName(), e);
            }
        }

        // Deep-link /start ref_XXX — Telegram передаёт параметр как второе слово в тексте.
        // Сам параметр разобран выше (до гейта канала), здесь остаётся только показать
        // welcome: /start с параметром обязан вести на то же меню, что и голый /start.
        if (text.startsWith("/start ")) {
            onboardingHandler.sendWelcome(chatId, user.getId());
            return;
        }

        switch (text) {
            case "/start" -> onboardingHandler.sendWelcome(chatId, user.getId());
            case "/status", "/key", "/mykey" -> screenHandler.sendStatus(chatId, user.getId());
            case "/help" -> screenHandler.sendSupport(chatId);
            case "/referral", "/invite" -> screenHandler.sendReferralInfo(chatId, user.getId());
            case "/autorenew" -> autoRenewHandler.sendAutoRenewInfo(chatId, user.getId());
            // Именно /mysubs, а не /subs: /subs — owner-команда админки, и перехват её
            // юзерским экраном лишил бы владельца инструмента разбора.
            case "/mysubs" -> subscriptionScreenHandler.sendSubscriptionList(chatId, user.getId());
            case "/terms", "/offer" -> screenHandler.sendTerms(chatId);
            default -> onboardingHandler.sendWelcome(chatId, user.getId());
        }
    }

    /** Роутер нажатий нижнего меню (reply keyboard, см. {@link Keyboards}). */
    private void handleMenuText(String text, Long chatId, User user) throws TelegramApiException {
        switch (text) {
            case Keyboards.MENU_BUY -> screenHandler.sendBuyMenu(chatId, user.getId());
            case Keyboards.MENU_ACCESS -> screenHandler.sendStatus(chatId, user.getId());
            case Keyboards.MENU_CABINET -> screenHandler.sendCabinet(chatId, user.getId());
            case Keyboards.MENU_SUPPORT -> screenHandler.sendSupport(chatId);
            default -> onboardingHandler.sendWelcome(chatId, user.getId());
        }
    }

    /**
     * Имена owner-команд — дубль списка из
     * {@link AdminCommandHandler#tryHandleAdminCommand(String, Long)}. Нужен ТОЛЬКО для
     * логирования: авторизация как была, так и осталась одна — {@link #isOwner(Long)}.
     * ⚠️ Заводите новую owner-команду — допишите её и сюда, иначе перебор именно этой
     * команды в логах не будет виден.
     */
    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "/admin", "/stats", "/metrics", "/user", "/devices", "/extend", "/extend_all", "/grant",
            "/disable", "/nodes", "/online", "/ledger", "/subs", "/subcheck", "/switchsub",
            "/receipt", "/reloadconfig", "/broadcast", "/broadcast_to", "/promo", "/testmail");

    /**
     * Пишет в лог попытку не-владельца выполнить owner-команду. В сообщение попадает только
     * ПЕРВЫЙ ТОКЕН ({@code /grant}) — аргументы логировать нельзя, там чужие telegramId,
     * юзернеймы и промокоды. Юзеру, как и раньше, ничего не отвечаем: silent ignore,
     * чтобы не подтверждать существование команды.
     */
    private void logAdminCommandAttempt(String text, Long telegramId) {
        String command = text.split("\\s+", 2)[0];
        if (ADMIN_COMMANDS.contains(command)) {
            log.warn("Non-owner telegramId={} sent owner command {} — ignored", telegramId, command);
        }
    }

    /**
     * Проверяет, что Telegram ID отправителя совпадает с настроенным OWNER_TELEGRAM_ID.
     * Null-safe: если env-var не задан, любой вызов вернёт false.
     */
    private boolean isOwner(Long userId) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        return ownerId != null && ownerId.equals(userId);
    }

}
