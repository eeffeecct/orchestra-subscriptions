package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.util.PromoMessages;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.PromoCodeService;
import com.orchestra.subscriptions.util.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Диспетчер reply-флоу: бот через {@link PendingInputService} ждёт от юзера текст
 * (промокод / имя устройства / email), а SubscriptionBot передаёт следующее сообщение сюда.
 * Каждая ветка валидирует ввод своим сервисом и перерисовывает соответствующий экран.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInputHandler {

    private final PendingInputService pendingInputService;
    private final PromoCodeService promoCodeService;
    private final DeviceTrackingService deviceTrackingService;
    private final EmailBotHandler emailBotHandler;
    private final ScreenHandler screenHandler;
    private final AppConfig appConfig;
    private final TelegramSender telegramSender;

    public void handle(PendingInputService.PendingInput input, String text, Long chatId, User user)
            throws TelegramApiException {
        switch (input.kind()) {
            case PROMO -> handlePromo(input.payload(), text.trim(), chatId, user.getId());
            case DEVICE_NAME -> handleDeviceName(input.payload(), text, chatId, user.getId());
            case EMAIL -> emailBotHandler.handleEmailInput(chatId, user.getId(), text.trim());
        }
    }

    /**
     * Промокод для покупки. payload = «planId[:tierId]» (контекст выбранного тарифа).
     * Валидный код запоминается в {@link PendingInputService#applyPromo} — pay-хендлеры
     * применят его к сумме, а пикер покажет скидку.
     */
    private void handlePromo(String payload, String code, Long chatId, Long telegramId)
            throws TelegramApiException {
        int sep = payload.indexOf(':');
        String planId = sep >= 0 ? payload.substring(0, sep) : payload;
        String tierId = sep >= 0 ? payload.substring(sep + 1) : null;

        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(planId);
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Тариф не найден — начните выбор заново: «Подписка»."));
            return;
        }
        long baseKopeks = appConfig.tierPriceKopeks(
                plan, appConfig.findDeviceTierOptional(tierId).orElse(null));

        PromoCodeService.PromoResult result = promoCodeService.preview(code, baseKopeks, telegramId);
        if (!result.valid()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ " + PromoMessages.reasonMessage(result.reason())
                            + "\n\nПопробовать другой код — «🎟 Есть промокод?», отмена — /cancel."));
            return;
        }
        pendingInputService.applyPromo(telegramId, result.code());
        telegramSender.execute(new SendMessage(chatId.toString(),
                "✅ Промокод " + result.code() + " применён: −"
                        + Money.rub(result.discountKopeks()) + " ₽."));
        screenHandler.sendPaymentMethodPicker(chatId, telegramId, payload);
    }

    private void handleDeviceName(String deviceIdRaw, String name, Long chatId, Long telegramId)
            throws TelegramApiException {
        long deviceId;
        try {
            deviceId = Long.parseLong(deviceIdRaw);
        } catch (NumberFormatException e) {
            return;
        }
        boolean ok = deviceTrackingService.rename(telegramId, deviceId, name);
        telegramSender.execute(new SendMessage(chatId.toString(),
                ok ? "✅ Устройство переименовано." : "⚠️ Устройство не найдено."));
        screenHandler.sendDevices(chatId, telegramId);
    }
}
