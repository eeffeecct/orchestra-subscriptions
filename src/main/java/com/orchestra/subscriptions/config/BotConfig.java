package com.orchestra.subscriptions.config;

import com.orchestra.subscriptions.bot.SubscriptionBot;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class BotConfig {

    private final SubscriptionBot subscriptionBot;
    private final AppConfig appConfig;

    /**
     * Регистрацию бота в 9.x делает сам telegrambots-springboot-longpolling-starter:
     * он находит бин, реализующий SpringLongPollingBot ({@link SubscriptionBot}), и поднимает long polling.
     * Здесь остаётся только разовая настройка меню команд и кнопки меню при старте.
     */
    @PostConstruct
    public void setupBotMenus() {
        registerSlashCommandsMenu();
        registerMenuButton();
    }

    /**
     * Прописывает нативное меню слэш-команд (выпадает из иконки «☰» рядом с полем ввода).
     * Вызывается раз при старте; Telegram кэширует результат на стороне клиента.
     * Ошибка здесь не критична — бот продолжит работать без меню.
     */
    private void registerSlashCommandsMenu() {
        try {
            SetMyCommands cmd = SetMyCommands.builder()
                    .commands(List.of(
                            new BotCommand("start", "Главное меню"),
                            new BotCommand("status", "Моя подписка"),
                            new BotCommand("referral", "Пригласить друга"),
                            new BotCommand("terms", "Публичная оферта"),
                            new BotCommand("help", "Помощь и подключение")
                    ))
                    .build();
            subscriptionBot.execute(cmd);
            log.info("Bot slash-commands menu registered");
        } catch (Exception e) {
            log.warn("Failed to register slash-commands menu (bot will work without it)", e);
        }
    }

    /**
     * Подменяет дефолтную кнопку меню (которая обычно «☰» с командами или «Menu» текстом)
     * на одну большую кнопку «🪐 Orchestra», которая по тапу открывает Mini App. Видна
     * слева от поля ввода в чате с ботом — главный CTA для нового юзера. Идемпотентно:
     * Telegram перезаписывает дефолт при каждом вызове.
     * <p>
     * Без chatId в SetChatMenuButton — это глобальный дефолт для ВСЕХ юзеров. Per-chat
     * override остаётся возможным через тот же метод с chatId, но мы его не используем.
     * <p>
     * Slash-команды остаются доступны: пока установлена MenuButtonWebApp, иконка «☰»
     * не показывается, но команды работают через ввод с автодополнением.
     */
    private void registerMenuButton() {
        try {
            String miniAppUrl = appConfig.getTelegram().getMiniAppBaseUrl() + "/app/";
            MenuButtonWebApp button = MenuButtonWebApp.builder()
                    .text("Orchestra")
                    .webAppInfo(new WebAppInfo(miniAppUrl))
                    .build();
            SetChatMenuButton method = SetChatMenuButton.builder()
                    .menuButton(button)
                    .build();
            subscriptionBot.execute(method);
            log.info("Bot menu button set to WebApp: {}", miniAppUrl);
        } catch (Exception e) {
            log.warn("Failed to set bot menu button (bot will work without it)", e);
        }
    }
}