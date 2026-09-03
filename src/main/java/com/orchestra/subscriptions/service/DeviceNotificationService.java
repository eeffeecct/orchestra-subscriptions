package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.bot.TelegramSender;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkMarkup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * DM-уведомления про устройства (детект на {@code /sub/<token>}). Отдельный сервис
 * поверх {@link TelegramSender}, НЕ {@link NotificationService}: тот тянет SubscriptionBot и
 * потребовал бы новых {@code @Lazy}-разрывов, а вызывается это из hot-path подписки —
 * поэтому все методы {@code @Async} (у приложения включён {@code @EnableAsync}) и
 * проглатывают ошибки: недоставленное уведомление не должно ломать выдачу подписки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceNotificationService {

    private final TelegramSender telegramSender;
    private final AppConfig appConfig;

    /** «📲 Подключено новое устройство: iPhone 15 (iOS 18.3) — 2 из 3». */
    @Async
    public void sendDeviceConnected(Long telegramId, String deviceLabel, long used, int limit) {
        // Везде htmlEscape С UTF-8: только &<>"' — именованные HTML-сущности (&hellip;, &trade;
        // из клиентских строк) Telegram-парсер не принимает и отклоняет всё сообщение.
        String slots = limit > 0 ? " — " + used + " из " + limit : "";
        sendSilently(telegramId,
                "📲 <b>Подключено новое устройство</b>\n\n"
                        + HtmlUtils.htmlEscape(deviceLabel, "UTF-8") + slots + "\n\n"
                        + "Если подключение выполнили не Вы, отключите устройство и смените ссылку "
                        + "подписки: «Кабинет» → «Мои устройства».",
                markup(devicesButton()));
    }

    /** Устройство сверх лимита тарифа: заблокировано; кнопки апгрейда и управления устройствами. */
    @Async
    public void sendDeviceLimitReached(Long telegramId, String deviceLabel, int limit) {
        InlineKeyboardButton upgrade = new IkButton();
        upgrade.setText("⬆️ Улучшить тариф");
        upgrade.setCallbackData("menu:buy");
        sendSilently(telegramId,
                "⛔️ <b>Лимит устройств тарифа исчерпан</b>\n\n"
                        + "Новое устройство " + HtmlUtils.htmlEscape(deviceLabel, "UTF-8")
                        + " не подключено: по Вашему тарифу доступно " + limit + " устр.\n\n"
                        + "Освободите слот («Отключить» в разделе «Мои устройства») либо перейдите "
                        + "на тариф с бóльшим числом устройств.",
                markup(upgrade, devicesButton()));
    }

    /**
     * Лимит устройств понизился (купили меньшую ступень / откат ступени после возврата) —
     * часть устройств потеряла слот. Шлём сразу, чтобы юзер не гадал, почему на одном из
     * устройств вместо серверов появилась заглушка: экран покупки его об этом предупреждал.
     */
    @Async
    public void sendDevicesDemoted(Long telegramId, int demoted, int limit) {
        sendSilently(telegramId,
                "📱 <b>Лимит устройств обновлён</b>\n\n"
                        + "По текущему тарифу доступно " + limit + " устр. Серверы перестали получать "
                        + demoted + " устр. — те, что дольше остальных не выходили на связь.\n\n"
                        + "Устройство вернётся в работу после освобождения слота "
                        + "(«Отключить» в разделе «Мои устройства») либо при переходе на тариф "
                        + "с бóльшим числом устройств.",
                markup(devicesButton()));
    }

    /**
     * Owner-алерт: на одной подписке аномально много HWID — похоже на перепродажу/утечку
     * ссылки. Троттлится в {@link DeviceTrackingService} (не чаще 1/час на юзера).
     */
    @Async
    public void notifyOwnerDeviceAnomaly(Long telegramId, String telegramUsername,
                                         long deviceCount, Integer deviceLimit) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        String tag = telegramUsername != null && !telegramUsername.isBlank()
                ? "@" + HtmlUtils.htmlEscape(telegramUsername, "UTF-8") : "id=" + telegramId;
        sendSilently(ownerId,
                "🚨 <b>Аномалия по устройствам</b>\n\n"
                        + "Подписка " + tag + " накопила <b>" + deviceCount + "</b> HWID"
                        + (deviceLimit != null && deviceLimit > 0 ? " при лимите " + deviceLimit : "")
                        + " — похоже на перепродажу/утечку ссылки.\n\n"
                        + "Карточка: /user " + telegramId + " · устройства: /devices " + telegramId,
                null);
    }

    private static InlineKeyboardButton devicesButton() {
        InlineKeyboardButton btn = new IkButton();
        btn.setText("📱 Мои устройства");
        btn.setCallbackData("devices");
        return btn;
    }

    /** По кнопке на ряд — на мобильном длинные подписи не режутся. */
    private static InlineKeyboardMarkup markup(InlineKeyboardButton... buttons) {
        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(java.util.Arrays.stream(buttons)
                .map(b -> new InlineKeyboardRow(b))
                .toList());
        return markup;
    }

    private void sendSilently(Long telegramId, String text, InlineKeyboardMarkup markup) {
        if (telegramId == null) return;
        try {
            SendMessage msg = new SendMessage(telegramId.toString(), text);
            msg.setParseMode("HTML");
            if (markup != null) {
                msg.setReplyMarkup(markup);
            }
            telegramSender.execute(msg);
        } catch (Exception e) {
            log.warn("Failed to send device notification to telegramId={}", telegramId, e);
        }
    }
}
