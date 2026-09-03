package com.orchestra.subscriptions.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

/**
 * Постоянное нижнее меню-клавиатура (reply keyboard) бота — главный источник
 * «app-like» ощущения, как у конкурентов.
 *
 * <p><b>Как это работает:</b> reply-кнопка при тапе отправляет в чат своё <i>текстовое</i>
 * значение как обычное сообщение. Поэтому лейблы кнопок здесь — это публичный «протокол»:
 * они обязаны 1:1 совпадать с матчингом в {@code SubscriptionBot.handleMenuText}. Менять лейбл =
 * менять обе стороны.
 *
 * <p>Кастомные (анимированные) эмодзи в reply-кнопках Telegram не поддерживает — только
 * обычный unicode. Анимация живёт в тексте сообщений (см. {@link Emoji}), а меню остаётся
 * простым и предсказуемым.
 */
public final class Keyboards {

    // Текст-метки без эмодзи: эмодзи на нижней клавиатуре даёт анимированная иконка
    // (icon_custom_emoji_id), иначе был бы двойной значок (иконка + эмодзи в тексте).
    public static final String MENU_BUY = "Подписка";
    public static final String MENU_ACCESS = "Мой доступ";
    public static final String MENU_CABINET = "Кабинет";
    public static final String MENU_SUPPORT = "Поддержка";

    private Keyboards() {
    }

    /**
     * Главное нижнее меню: две строки по две кнопки. {@code resize=true} — клавиатура
     * подгоняется под кнопки (не во весь экран), {@code isPersistent=true} — остаётся
     * видимой и не сворачивается после нажатия.
     */
    public static ReplyKeyboardMarkup mainMenu(Emoji emoji) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(menuButton(MENU_BUY, emoji.id("rocket")));
        row1.add(menuButton(MENU_ACCESS, emoji.id("shield")));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(menuButton(MENU_CABINET, emoji.id("person")));
        row2.add(menuButton(MENU_SUPPORT, emoji.id("question")));

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(List.of(row1, row2));
        markup.setResizeKeyboard(true);
        markup.setIsPersistent(true);
        markup.setSelective(false);
        return markup;
    }

    /**
     * Кнопка нижнего меню. Если задан custom_emoji_id — добавляем анимированную иконку
     * (Bot API 9.4). Текст-метка (с обычным эмодзи) остаётся и как «протокол» меню
     * (по нему матчится {@link #isMenuButton}), и как fallback.
     * <p>ВНИМАНИЕ: рендер custom-эмодзи на reply-клавиатуре зависит от клиента Telegram —
     * на части клиентов иконка может не показаться (в отличие от inline-кнопок).
     */
    private static KeyboardButton menuButton(String text, Long iconId) {
        var b = KeyboardButton.builder().text(text);
        if (iconId != null) {
            b.iconCustomEmojiId(String.valueOf(iconId));
        }
        return b.build();
    }

    /** true, если текст сообщения — это нажатие одной из кнопок нижнего меню. */
    public static boolean isMenuButton(String text) {
        return MENU_BUY.equals(text)
                || MENU_ACCESS.equals(text)
                || MENU_CABINET.equals(text)
                || MENU_SUPPORT.equals(text);
    }
}
