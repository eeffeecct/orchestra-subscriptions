package com.orchestra.subscriptions.util;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * Compat-обёртка: {@link SendMessage} с no-arg конструктором.
 *
 * <p>В 9.x публичный no-arg конструктор убрали — остались {@code (String chatId, String text)}
 * и билдер. В коде бота сообщение часто создаётся «пусто, потом сеттеры», поэтому даём no-arg
 * с плейсхолдерами, которые тут же перетираются {@code setChatId(...)}/{@code setText(...)}.
 */
public class IkSendMessage extends SendMessage {
    public IkSendMessage() {
        super("0", " ");
    }
}
