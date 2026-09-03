package com.orchestra.subscriptions.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Compat-обёртка: {@link InlineKeyboardButton} с no-arg конструктором.
 *
 * <p>В telegrambots 9.x ({@code @SuperBuilder}/{@code @Jacksonized}) публичный no-arg
 * конструктор убрали — остались только {@code (String text)} и билдер. В коде бота кнопки
 * всегда создаются «пусто, потом сеттеры», поэтому даём no-arg с плейсхолдер-текстом,
 * который тут же перетирается {@code setText(...)}. Новые поля 9.4 ({@code style},
 * {@code icon_custom_emoji_id}) доступны через унаследованные сеттеры.
 */
public class IkButton extends InlineKeyboardButton {
    public IkButton() {
        super(" ");
        // Цвет по умолчанию — обычный. Зелёный (style="success") ставим точечно только там,
        // где нужно (кнопка «Открыть Orchestra»). Иконка — setIconCustomEmojiId(...).
    }
}
