package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.OfferAcceptance;
import com.orchestra.subscriptions.repository.OfferAcceptanceRepository;
import com.orchestra.subscriptions.service.ReferralService;
import com.orchestra.subscriptions.service.AccessService;
import com.orchestra.subscriptions.util.Emoji;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkButtons;
import com.orchestra.subscriptions.util.IkMarkup;
import com.orchestra.subscriptions.util.IkSendMessage;
import com.orchestra.subscriptions.util.Keyboards;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Онбординг/вход: welcome-экран (/start), постоянное нижнее меню, deep-link реферала
 * (/start ref_), проверка доступности триала. Вынесено из god-class SubscriptionBot (PR-5).
 * Свой {@link Emoji} (намеренно не Spring-бин), кнопки через {@link IkButtons}, отправка
 * через {@link TelegramSender}. Eager: зависимости (AppConfig/AccessService/ReferralService/
 * TelegramSender) не образуют цикла с SubscriptionBot.
 */
@Slf4j
@Component
public class OnboardingHandler {

    private final AppConfig appConfig;
    private final AccessService accessService;
    private final ReferralService referralService;
    private final TelegramSender telegramSender;
    private final OfferAcceptanceRepository offerAcceptanceRepository;
    private final Emoji emoji;

    public OnboardingHandler(AppConfig appConfig, AccessService accessService,
                             ReferralService referralService, TelegramSender telegramSender,
                             OfferAcceptanceRepository offerAcceptanceRepository) {
        this.appConfig = appConfig;
        this.accessService = accessService;
        this.referralService = referralService;
        this.telegramSender = telegramSender;
        this.offerAcceptanceRepository = offerAcceptanceRepository;
        this.emoji = new Emoji(appConfig.getEmoji());
    }

    /**
     * Устанавливает/обновляет постоянное нижнее меню. Reply keyboard прикрепляется только к
     * сообщению, поэтому шлём короткое сообщение-носитель. Вызывается из {@link #sendWelcome}
     * (на /start), дальше клавиатура остаётся видимой сама ({@code isPersistent}).
     */
    private void sendMainMenu(Long chatId) throws TelegramApiException {
        SendMessage msg = new SendMessage(chatId.toString(),
                emoji.e("sparkles") + " Меню всегда снизу — выбирайте раздел.");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(Keyboards.mainMenu(emoji));
        telegramSender.execute(msg);
    }

    /**
     * Обрабатывает параметр /start. Сейчас поддерживается только формат "ref_{telegramId}".
     * Невалидные / неизвестные параметры молча игнорируются — welcome показывается всё равно.
     */
    public void handleStartParam(Long referredTelegramId, String startParam) {
        if (startParam == null || !startParam.startsWith("ref_")) {
            return;
        }
        String refIdRaw = startParam.substring("ref_".length());
        Long referrerId;
        try {
            referrerId = Long.parseLong(refIdRaw);
        } catch (NumberFormatException e) {
            log.debug("Malformed referral param: {}", startParam);
            return;
        }
        try {
            referralService.recordReferral(referrerId, referredTelegramId);
        } catch (Exception e) {
            // Не даём упасть welcome-flow из-за ошибки реферала.
            log.error("recordReferral failed for referrer={} referred={}",
                    referrerId, referredTelegramId, e);
        }
    }

    /**
     * Нужно ли сперва взять согласие с офертой. Только для тех, кто ещё ни разу не пользовался
     * сервисом: критерий тот же, что у {@link #canUseTrial} — отсутствие строки в {@code customers}.
     * <p>
     * Существующих людей гейт не касается специально: они пришли до его появления, и показывать
     * им «примите оферту, чтобы продолжить» на ровном месте значило бы перегородить вход тем,
     * кто уже платит.
     */
    private boolean needsOfferAcceptance(Long telegramId) {
        return accessService.getUser(telegramId).isEmpty()
                && !offerAcceptanceRepository.existsById(telegramId);
    }

    /**
     * Экран согласия перед первым входом: оферта + одна кнопка «Принимаю». Показывается вместо
     * welcome и заменяется им же после нажатия (см. {@link #acceptOffer}).
     * <p>
     * Ссылка на оферту — отдельной кнопкой, а не ссылкой в тексте: текст сообщения удаляется
     * сразу после согласия, и человеку должно быть куда нажать, пока оно на экране. Если
     * {@code OFFER_URL} не настроен, кнопки нет — врать про существующий документ нельзя,
     * согласие всё равно спрашиваем.
     */
    public void sendOfferGate(Long chatId) throws TelegramApiException {
        String text = emoji.e("shield") + " <b>Orchestra</b>\n\n"
                + "Пользование сервисом означает согласие с условиями публичной оферты.\n\n"
                + "Ознакомьтесь с ней и нажмите «Принимаю», чтобы продолжить.";

        List<InlineKeyboardRow> rows = new ArrayList<>();

        String offerUrl = appConfig.getTelegram().getOfferUrl();
        if (offerUrl != null && !offerUrl.isBlank()) {
            InlineKeyboardButton offerButton = new IkButton();
            offerButton.setText("📄 Публичная оферта");
            offerButton.setUrl(offerUrl);
            rows.add(new InlineKeyboardRow(offerButton));
        }

        InlineKeyboardButton acceptButton = IkButtons.icon(emoji, "check", "✅", "Принимаю");
        acceptButton.setCallbackData("offer:accept");
        acceptButton.setStyle("success");
        rows.add(new InlineKeyboardRow(acceptButton));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /**
     * Нажали «Принимаю»: фиксируем согласие, убираем экран и пускаем в бот.
     * <p>
     * Отметка пишется ДО удаления сообщения: если удаление не пройдёт (сообщение старше
     * 48 часов — Telegram такие боту удалять не даёт), согласие всё равно зафиксировано,
     * и человек не упрётся в тот же экран снова.
     */
    public void acceptOffer(Long chatId, Long telegramId, Integer messageId) throws TelegramApiException {
        offerAcceptanceRepository.save(OfferAcceptance.builder()
                .telegramId(telegramId)
                .acceptedAt(LocalDateTime.now())
                .offerUrl(appConfig.getTelegram().getOfferUrl())
                // Редакция — то, чего адрес не даёт: текст на Telegraph правится на месте.
                .offerRevision(appConfig.getTelegram().getOfferRevision())
                .build());

        if (messageId != null) {
            try {
                telegramSender.execute(DeleteMessage.builder()
                        .chatId(chatId.toString())
                        .messageId(messageId)
                        .build());
            } catch (Exception e) {
                // Не роняем вход из-за неудалённого экрана — welcome ниже уйдёт в любом случае.
                log.warn("Не удалось удалить экран оферты для telegramId={}", telegramId, e);
            }
        }

        sendWelcomeScreen(chatId, telegramId);
    }

    /**
     * Вход в бот. На первом запуске вместо welcome показывается согласие с офертой: проверка
     * живёт здесь, а не в роутере команд, чтобы её нельзя было обойти забытым вызовом.
     */
    public void sendWelcome(Long chatId, Long telegramId) throws TelegramApiException {
        if (needsOfferAcceptance(telegramId)) {
            sendOfferGate(chatId);
            return;
        }
        sendWelcomeScreen(chatId, telegramId);
    }

    private void sendWelcomeScreen(Long chatId, Long telegramId) throws TelegramApiException {
        // Брендовый хук. Менять можно прямо здесь, layout от текста не зависит.
        // Mini App — основной экран для всего платного (тарифы, оплата, автопродление).
        // Бот оставлен для быстрых команд (/status, /access, /referral) и уведомлений.
        // Пробный период создаётся автоматически при первом открытии Mini App.
        String text = emoji.e("shield") + " <b>Orchestra</b>\n\n"
                + "Каждый сервер — как инструмент в оркестре: слаженно, быстро и без единого сбоя.\n\n"
                + "Доступ работает на всех Ваших устройствах: подключите их по одной ссылке "
                + "и переключайтесь свободно.\n\n"
                + emoji.e("gift") + " <b>7 дней бесплатно</b>";

        List<InlineKeyboardRow> rows = new ArrayList<>();

        // Главная кнопка — открывает Mini App. setWebApp() делает кнопку особого типа,
        // при тапе Telegram открывает встроенный WebView с нашим интерфейсом.
        // Никаких redirect-ов / openLink — нативное поведение, мгновенно.
        InlineKeyboardButton openAppButton = IkButtons.icon(emoji, "shield", "🪐", "Открыть Orchestra");
        openAppButton.setWebApp(new org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo(
                appConfig.getTelegram().getMiniAppBaseUrl() + "/app/"
        ));
        openAppButton.setStyle("success"); // единственная зелёная кнопка — главный CTA
        rows.add(new InlineKeyboardRow(openAppButton));

        // Запасной путь прямо в чате: Mini App грузится с нашего домена, и если WebView
        // до него не дошёл, человек всё равно должен уметь купить и получить пробный период.
        // Поэтому покупка дублируется нативными средствами Telegram (Stars и криптоплатёж
        // внутри Telegram, ссылка подтверждения эквайринга — на домене платёжного сервиса).

        // Триал — показываем только если юзер ещё ни разу не заходил (трайл = первая запись в БД).
        if (canUseTrial(telegramId)) {
            InlineKeyboardButton trialButton = new IkButton();
            Long giftIcon = emoji.id("gift");
            trialButton.setText((giftIcon != null ? "" : "🎁 ") + "Попробовать 7 дней бесплатно");
            if (giftIcon != null) {
                trialButton.setIconCustomEmojiId(String.valueOf(giftIcon));
            }
            trialButton.setCallbackData("trial");
            rows.add(new InlineKeyboardRow(trialButton));
        }

        InlineKeyboardButton buyButton = new IkButton();
        Long buyIcon = emoji.id("rocket");
        buyButton.setText(buyIcon != null ? "Купить подписку" : "🚀 Купить подписку");
        if (buyIcon != null) {
            buyButton.setIconCustomEmojiId(String.valueOf(buyIcon));
        }
        buyButton.setCallbackData("menu:buy");
        rows.add(new InlineKeyboardRow(buyButton));

        // Моя подписка / Кабинет / Поддержка вынесены в постоянное нижнее меню
        // (см. sendMainMenu) — поэтому их inline-дубликаты в welcome убраны, экран чище.

        // Канал с новостями — кнопка с прямой URL, открывается в Telegram нативно.
        // Условно: если в env задан URL, иначе скрываем (для dev / случая если канала временно нет).
        String newsUrl = appConfig.getTelegram().getNewsChannelUrl();
        if (newsUrl != null && !newsUrl.isBlank()) {
            InlineKeyboardButton newsButton = IkButtons.icon(emoji, "news", "🔈", "Канал с новостями");
            newsButton.setUrl(newsUrl);
            rows.add(new InlineKeyboardRow(newsButton));
        }

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);

        // Ставим/обновляем постоянное нижнее меню отдельным сообщением: одна reply keyboard
        // на сообщение, а welcome занят inline-кнопками.
        sendMainMenu(chatId);
    }

    /**
     * Триал доступен ровно один раз в жизни: строки человека ещё нет — значит это первый визит.
     * <p>
     * ⚠️ Не переписывать на {@code trial_used_at IS NULL}. Выглядит как более честный критерий,
     * но эта отметка есть НЕ у всех: её ставит выдача триала и разовый бэкфилл, а человек,
     * который сразу купил тариф (минуя триал), живёт с {@code NULL} навсегда. Гейт по отметке
     * вернул бы ему кнопку «7 дней бесплатно», а нажатие кончилось бы ошибкой —
     * {@code AccessService.createTrialAccess} отказывает по наличию строки.
     * <p>
     * Для нескольких подписок признак остаётся верным: {@code customers} 1:1 с ЧЕЛОВЕКОМ и
     * не исчезает, сколько бы подписок он ни припарковал.
     */
    private boolean canUseTrial(Long telegramId) {
        return accessService.getUser(telegramId).isEmpty();
    }
}
