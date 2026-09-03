package com.orchestra.subscriptions.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Compat-обёртка: {@link InlineKeyboardMarkup} с no-arg конструктором.
 *
 * <p>В 9.x no-arg конструктор убрали, а тип рядов сменился на {@code List<InlineKeyboardRow>}
 * (раньше {@code List<List<InlineKeyboardButton>>}). Здесь стартуем с пустого списка рядов;
 * реальные ряды выставляются через {@code setKeyboard(List<InlineKeyboardRow>)} как и прежде.
 */
public class IkMarkup extends InlineKeyboardMarkup {
    public IkMarkup() {
        super(new ArrayList<InlineKeyboardRow>());
    }

    public IkMarkup(List<InlineKeyboardRow> keyboard) {
        super(keyboard);
    }
}
