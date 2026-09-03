package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;

/**
 * Единственная точка отправки в Telegram. В 9.x приём апдейтов ({@link SubscriptionBot}) и отправка
 * ({@link TelegramClient}) разделены — раньше клиент жил прямо в конструкторе {@code SubscriptionBot},
 * и любой, кому нужно было что-то послать, тянул за собой весь god-class.
 * <p>
 * Вынос в отдельный бин даёт две вещи:
 * <ul>
 *   <li><b>Развязку зависимостей.</b> Хендлерам больше не
 *       нужен {@code SubscriptionBot} ради {@code execute(...)} — они инжектят этот тонкий бин,
 *       у которого нет обратных рёбер в граф (зависит только от {@link AppConfig}).</li>
 *   <li><b>Тестируемость.</b> В юнит-тестах хендлера достаточно {@code mock(TelegramSender.class)}
 *       вместо подъёма всего {@code SubscriptionBot} с 14 моками.</li>
 * </ul>
 * {@code SubscriptionBot.execute(...)} сохранён как делегат к этому бину — внешний код
 * (NotificationService, BroadcastService, BotConfig, WebAppController) не переписываем.
 */
@Component
public class TelegramSender {

    private final TelegramClient telegramClient;

    public TelegramSender(AppConfig appConfig) {
        this.telegramClient = new OkHttpTelegramClient(appConfig.getTelegram().getBotToken());
    }

    /**
     * Отправляет метод Bot API и возвращает типизированный ответ. Сигнатура 1-в-1
     * повторяет прежний {@code SubscriptionBot.execute(...)}, чтобы вызовы не переписывать.
     */
    public <T extends Serializable, M extends BotApiMethod<T>> T execute(M method) throws TelegramApiException {
        return telegramClient.execute(method);
    }

    /**
     * Отправка фото. Отдельным методом по той же причине, что видео и анимация: медиа уходит
     * multipart-запросом, поэтому {@code SendPhoto} — не {@code BotApiMethod} и под generic-
     * сигнатуру {@link #execute(BotApiMethod)} не подходит.
     * <p>
     * Используется рассылкой ({@code /broadcast} с картинкой) и шлёт не файл, а {@code file_id}
     * уже залитого в Telegram изображения.
     */
    public org.telegram.telegrambots.meta.api.objects.message.Message execute(
            org.telegram.telegrambots.meta.api.methods.send.SendPhoto method) throws TelegramApiException {
        return telegramClient.execute(method);
    }

    /**
     * Отправка видео. Отдельным методом, а не через {@link #execute(BotApiMethod)}: медиа в Bot
     * API уходит multipart-запросом, поэтому {@code SendVideo} — не {@code BotApiMethod}, и под
     * generic-сигнатуру выше не подходит.
     * <p>
     * Единственный сегодняшний вызывающий — экран «как подключиться», и он передаёт не файл, а
     * {@code file_id} уже залитого в Telegram ролика.
     */
    public org.telegram.telegrambots.meta.api.objects.message.Message execute(
            org.telegram.telegrambots.meta.api.methods.send.SendVideo method) throws TelegramApiException {
        return telegramClient.execute(method);
    }

    /**
     * Отправка анимации (mp4 без звуковой дорожки — Telegram считает такой файл гифкой).
     * Отдельный метод по той же причине, что и {@link #execute(org.telegram.telegrambots.meta.api.methods.send.SendVideo)}:
     * multipart, а не {@code BotApiMethod}.
     * <p>
     * Нужен потому, что {@code file_id} в Telegram привязан к типу: идентификатор анимации
     * через {@code sendVideo} не отправится.
     */
    public org.telegram.telegrambots.meta.api.objects.message.Message execute(
            org.telegram.telegrambots.meta.api.methods.send.SendAnimation method) throws TelegramApiException {
        return telegramClient.execute(method);
    }
}
