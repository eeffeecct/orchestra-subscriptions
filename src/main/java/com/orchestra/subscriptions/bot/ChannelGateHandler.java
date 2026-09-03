package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.util.Emoji;
import com.orchestra.subscriptions.util.IkButtons;
import com.orchestra.subscriptions.util.IkMarkup;
import com.orchestra.subscriptions.util.IkSendMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * Обязательная подписка на новостной канал: пока человек не в канале, вместо любого экрана
 * бота он видит гейт с кнопками «Подписаться» и «Проверить».
 *
 * <p>Гейт стоит выше всех пользовательских экранов — в {@code SubscriptionBot.handleMessage} и в
 * {@link CallbackRouter#route(Update)}, а не внутри отдельных хендлеров. Причина та же, что у
 * согласия с офертой: проверка, разложенная по экранам, обходится любым забытым вызовом, и
 * новый экран автоматически оказывается дырой.
 *
 * <p>Чего гейт НЕ касается — намеренно:
 * <ul>
 *   <li><b>Деньги.</b> {@code pre_checkout_query} и {@code successful_payment} разбираются в
 *       {@code SubscriptionBot.consume} до маршрутизации сообщений: платёж, за который уже списали
 *       деньги, обязан зачислиться независимо от подписки на канал.</li>
 *   <li><b>Сам доступ.</b> Ссылка подписки и параметры доступа живут вне бота — отписка от канала
 *       закрывает интерфейс бота, но не отключает оплаченный доступ.</li>
 *   <li><b>Владелец.</b> Иначе снятие бота с админов канала могло бы отрезать админку.</li>
 * </ul>
 */
@Slf4j
@Component
public class ChannelGateHandler {

    /** callback_data кнопки «Проверить». */
    public static final String CHECK_CALLBACK = "channel:check";

    private final AppConfig appConfig;
    private final ChannelMembershipService membership;
    private final TelegramSender telegramSender;
    private final OnboardingHandler onboardingHandler;
    private final Emoji emoji;

    public ChannelGateHandler(AppConfig appConfig,
                              ChannelMembershipService membership,
                              TelegramSender telegramSender,
                              OnboardingHandler onboardingHandler) {
        this.appConfig = appConfig;
        this.membership = membership;
        this.telegramSender = telegramSender;
        this.onboardingHandler = onboardingHandler;
        this.emoji = new Emoji(appConfig.getEmoji());
        // Диагностика на старте — по образцу лога эмодзи в SubscriptionBot: из логов должно быть
        // видно, включён гейт или нет, без похода в конфиг прода.
        log.info("Channel gate: channel={}, joinUrl={}",
                membership.required() ? membership.channel() : "<off>", joinUrl());
    }

    /**
     * Гейт для текстового сообщения. {@code true} — сообщение перехвачено (показан экран
     * подписки), дальше его роутить не нужно.
     */
    public boolean interceptMessage(Long chatId, Long telegramId) throws TelegramApiException {
        if (allowed(telegramId)) {
            return false;
        }
        sendGate(chatId);
        return true;
    }

    /**
     * Гейт для inline-кнопки. {@code true} — callback перехвачен (нажали «Проверить» либо
     * человек не подписан), дальше его роутить не нужно.
     *
     * <p>Вызывается в {@link CallbackRouter} ДО общего ack: гейту нужно ответить на query
     * своим текстом (всплывашкой), а общий ack гасит loader молча и второй ответ на тот же
     * query Telegram уже не примет.
     */
    public boolean interceptCallback(Update update) throws TelegramApiException {
        CallbackQuery query = update.getCallbackQuery();
        MaybeInaccessibleMessage source = query.getMessage();
        if (source == null) {
            // Кнопка без сообщения (inline-режим) — показывать экран некуда. Отдаём как
            // было: маршрутизация ниже такие callback'и и раньше не умела.
            return false;
        }
        Long telegramId = query.getFrom().getId();
        Long chatId = source.getChatId();

        if (CHECK_CALLBACK.equals(query.getData())) {
            handleCheck(query, source, chatId, telegramId);
            return true;
        }
        if (allowed(telegramId)) {
            return false;
        }
        // Ответ на query и гасит loader на кнопке, и объясняет, почему ничего не произошло.
        answer(query.getId(), "Доступ к боту открыт подписчикам канала.", true);
        sendGate(chatId);
        return true;
    }

    /**
     * Нажали «Проверить». Проверяем без кэша — человек подписался секунду назад.
     * Экран гейта убираем за собой, как это делает экран оферты.
     */
    private void handleCheck(CallbackQuery query, MaybeInaccessibleMessage source,
                             Long chatId, Long telegramId) throws TelegramApiException {
        if (!isOwner(telegramId) && !membership.isMemberFresh(telegramId)) {
            answer(query.getId(),
                    "Подписка не найдена. Подпишитесь на канал и нажмите «Проверить» ещё раз.",
                    true);
            return;
        }
        answer(query.getId(), "Подписка подтверждена.", false);
        deleteQuietly(chatId, source.getMessageId());
        onboardingHandler.sendWelcome(chatId, telegramId);
    }

    /**
     * Пускаем ли этого человека. Владелец — всегда; гейт без ссылки «Подписаться» считается
     * выключенным: экран без действия был бы для человека тупиком (см. {@link #joinUrl()}).
     */
    private boolean allowed(Long telegramId) {
        if (isOwner(telegramId) || !membership.required()) {
            return true;
        }
        String joinUrl = joinUrl();
        if (joinUrl == null || joinUrl.isBlank()) {
            return true;
        }
        return membership.isMember(telegramId);
    }

    /** Экран подписки. Канал в тексте не называем — его несёт кнопка со ссылкой. */
    private void sendGate(Long chatId) throws TelegramApiException {
        String text = emoji.e("news") + " <b>Подписка на канал</b>\n\n"
                + "Доступ к боту открыт подписчикам новостного канала сервиса: там выходят "
                + "сообщения о работах, сменах адресов и новых серверах.\n\n"
                + "Подпишитесь на канал и нажмите «Проверить».";

        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardButton joinButton = IkButtons.icon(emoji, "news", "🔈", "Подписаться на канал");
        joinButton.setUrl(joinUrl());
        rows.add(new InlineKeyboardRow(joinButton));

        InlineKeyboardButton checkButton = IkButtons.icon(emoji, "check", "✅", "Проверить");
        checkButton.setCallbackData(CHECK_CALLBACK);
        checkButton.setStyle("success");
        rows.add(new InlineKeyboardRow(checkButton));

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

    /** Куда ведёт кнопка «Подписаться». */
    private String joinUrl() {
        return appConfig.getTelegram().getRequiredChannelJoinUrl();
    }

    /**
     * Ответ на callback: текст всплывашкой ({@code showAlert}) или короткой плашкой сверху.
     * Никогда не бросает наружу — ответ на query косметический и не должен валить флоу.
     */
    private void answer(String callbackQueryId, String text, boolean alert) {
        try {
            telegramSender.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .showAlert(alert)
                    .build());
        } catch (Exception e) {
            log.debug("Failed to answer callback {}: {}", callbackQueryId, e.getMessage());
        }
    }

    /** Убрать экран гейта. Не удалилось (сообщение старше 48 часов) — не беда, идём дальше. */
    private void deleteQuietly(Long chatId, Integer messageId) {
        if (messageId == null) {
            return;
        }
        try {
            telegramSender.execute(DeleteMessage.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .build());
        } catch (Exception e) {
            log.debug("Не удалось удалить экран гейта в чате {}: {}", chatId, e.getMessage());
        }
    }

    private boolean isOwner(Long telegramId) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        return ownerId != null && ownerId.equals(telegramId);
    }
}
