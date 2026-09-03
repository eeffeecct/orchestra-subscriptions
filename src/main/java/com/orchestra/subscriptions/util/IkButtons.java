package com.orchestra.subscriptions.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Хелперы построения inline-кнопок. Вынесены из {@code SubscriptionBot}, чтобы экраны бота
 * (в {@code SubscriptionBot} и {@code ScreenHandler}) строили кнопки с анимированными иконками
 * одинаково, без дублирования логики фолбэка.
 */
public final class IkButtons {

    private IkButtons() {
    }

    /**
     * Inline-кнопка с анимированной иконкой (icon_custom_emoji_id, Bot API 9.4) по semantic-ключу.
     * Если id не задан/фича выключена — иконки нет, обычный эмодзи идёт в начале текста (fallback),
     * так на кнопке не бывает двойного значка. Callback/url/webApp/style довешивает вызывающий.
     */
    public static InlineKeyboardButton icon(Emoji emoji, String emojiKey, String fallbackEmoji, String label) {
        InlineKeyboardButton b = new IkButton();
        Long id = emoji.id(emojiKey);
        if (id != null) {
            b.setText(label);
            b.setIconCustomEmojiId(String.valueOf(id));
        } else {
            b.setText(fallbackEmoji + " " + label);
        }
        return b;
    }
}
