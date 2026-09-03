package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.service.AutoRenewInfoService;
import com.orchestra.subscriptions.service.AutoRenewService;
import com.orchestra.subscriptions.service.AccessService;
import com.orchestra.subscriptions.util.Emoji;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkMarkup;
import com.orchestra.subscriptions.util.IkSendMessage;
import com.orchestra.subscriptions.util.TimeFormat;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Optional;

/**
 * Управление автопродлением (recurring-платежи YooKassa): экран /autorenew, переключение
 * вкл/выкл, отвязка карты с двухшаговым подтверждением. Вынесено из god-class SubscriptionBot (PR-5).
 * Отправка через {@link TelegramSender} (мокается в тестах). SubscriptionBot инжектит
 * {@code @Lazy AutoRenewHandler} — autoRenewService в цикле через NotificationService → SubscriptionBot.
 */
@Component
public class AutoRenewHandler {

    private final AppConfig appConfig;
    private final AccessService accessService;
    private final AutoRenewService autoRenewService;
    /** Что и когда спишется — общий счёт для бота и Mini App, чтобы экраны не разъезжались. */
    private final AutoRenewInfoService autoRenewInfo;
    private final TelegramSender telegramSender;
    private final Emoji emoji;

    public AutoRenewHandler(AppConfig appConfig, AccessService accessService,
                            AutoRenewService autoRenewService,
                            AutoRenewInfoService autoRenewInfo,
                            TelegramSender telegramSender) {
        this.appConfig = appConfig;
        this.accessService = accessService;
        this.autoRenewService = autoRenewService;
        this.autoRenewInfo = autoRenewInfo;
        this.telegramSender = telegramSender;
        this.emoji = new Emoji(appConfig.getEmoji());
    }

    public void sendAutoRenewInfo(Long chatId, Long telegramId) throws TelegramApiException {
        // Фича выключена на уровне магазина — не пудрим юзеру голову деталями.
        if (!appConfig.getYookassa().isRecurrentEnabled()) {
            SendMessage msg = new SendMessage(chatId.toString(),
                    "🔁 Автопродление временно недоступно.");
            telegramSender.execute(msg);
            return;
        }

        Optional<Customer> userOpt = accessService.getUser(telegramId);
        if (userOpt.isEmpty() || userOpt.get().getAutorenewMethodId() == null) {
            // Нет сохранённой карты — автопродление не привязано.
            String text = emoji.e("recycle") + " <b>Автопродление</b>\n\n"
                    + "Не настроено. Автопродление подключается при оплате картой ЮKassa в Mini App, "
                    + "если выбран вариант <b>«С автопродлением»</b>.\n\n"
                    + "Оформление подписки: /start → «🪐 Открыть Orchestra»";
            SendMessage msg = new SendMessage(chatId.toString(), text);
            msg.setParseMode("HTML");
            telegramSender.execute(msg);
            return;
        }

        Customer user = userOpt.get();
        // Что именно спишется — считает AutoRenewInfoService, тот же снимок читает Mini App.
        // Раньше каждый экран считал сам, и Mini App показывал только слово «включено»: узнать,
        // за что спишут деньги, там было негде.
        AutoRenewInfoService.Info info = autoRenewInfo.forUser(user);

        String tariffLine;
        if (info.planGone() || info.planName() == null) {
            // Тариф сняли с продажи — автопродление отвалится при следующей попытке
            // (chargeAndActivate выключит его), поэтому честно предупреждаем заранее.
            tariffLine = (info.planId() != null ? info.planId() : "—")
                    + " <i>(тариф снят с продажи, продление только вручную)</i>";
        } else {
            tariffLine = info.planName()
                    + (info.tierName() != null ? " · " + info.tierName() : "")
                    + " — <b>" + info.amountRub() + " ₽</b>";
        }

        String statusEmoji = user.isAutorenewEnabled()
                ? emoji.e("check") + " Включено"
                : emoji.e("pause") + " Выключено";
        String nextCharge = info.nextChargeAt() != null
                ? TimeFormat.mskDate(info.nextChargeAt())
                : "—";

        StringBuilder sb = new StringBuilder();
        sb.append(emoji.e("recycle")).append(" <b>Автопродление</b>\n\n")
                .append("Статус: ").append(statusEmoji).append("\n");
        // Какую именно подписку продлят — строкой рядом с суммой, а не абзацем внизу. Человек
        // с двумя подписками задаёт этот вопрос первым, и ответ обязан стоять там же, где
        // цифры, иначе он его не читает.
        if (info.needsSubscriptionName()) {
            sb.append("Продлевается: <b>подписка #").append(info.targetSeq()).append("</b>\n");
        }
        sb.append("Спишется: ").append(tariffLine).append("\n")
                .append("Когда: ").append(nextCharge).append("\n\n");
        if (info.lastFailureAt() != null) {
            sb.append(emoji.e("warn")).append(" Последняя попытка списания не удалась ")
                    .append(TimeFormat.mskDateTime(info.lastFailureAt()))
                    .append(". Вероятная причина — отказ карты.\n\n");
        }
        // «Срок последней покупки», а не «тариф последней покупки»: число устройств теперь
        // берётся у самой подписки, и прежняя формулировка обещала бы обратное.
        sb.append("Списание выполняется за 24 часа до истечения подписки. Продлевается на срок ")
                .append("последней покупки, число устройств сохраняется. ")
                .append("Отключение не удаляет привязанную карту: повторное включение не требует новой оплаты.");
        // Человеку с несколькими подписками нужно знать, ЧТО именно продлевается: иначе он
        // считает, что карта поддерживает их все, и обнаруживает обратное, когда спящая
        // подписка не ожила сама.
        if (info.needsSubscriptionName()) {
            sb.append("\n\nЗамороженные подписки автоматически <b>не продлеваются</b>, ")
                    .append("их срок не расходуется. Чтобы продлевалась другая подписка, ")
                    .append("включите её в разделе «Мои подписки».");
        }

        InlineKeyboardButton toggleBtn = new IkButton();
        toggleBtn.setText(user.isAutorenewEnabled() ? "⏸ Выключить автопродление" : "▶️ Включить автопродление");
        toggleBtn.setCallbackData("autorenew:toggle");

        InlineKeyboardButton unbindBtn = new IkButton();
        unbindBtn.setText("🗑 Отвязать карту");
        unbindBtn.setCallbackData("autorenew:unbind");

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(toggleBtn), new InlineKeyboardRow(unbindBtn)));

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    public void sendAutoRenewUnbindConfirm(Long chatId) throws TelegramApiException {
        String text = "🗑 <b>Отвязать карту?</b>\n\n"
                + "Привязка карты будет удалена, автопродление отключено.\n\n"
                + "Для повторного подключения потребуется новый платёж с отметкой "
                + "«С автопродлением».\n\n"
                + "Подтвердить?";

        InlineKeyboardButton confirmBtn = new IkButton();
        confirmBtn.setText("🗑 Да, отвязать");
        confirmBtn.setCallbackData("autorenew:unbind_confirm");

        InlineKeyboardButton cancelBtn = new IkButton();
        cancelBtn.setText("❌ Отмена");
        cancelBtn.setCallbackData("autorenew:unbind_cancel");

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(confirmBtn), new InlineKeyboardRow(cancelBtn)));

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    public void handleAutoRenewUnbindConfirmed(Long chatId, Long telegramId) throws TelegramApiException {
        boolean unbound = autoRenewService.unbindCard(telegramId);
        String text = unbound
                ? "✅ Карта отвязана, автосписания прекращены.\n\n"
                + "Повторное подключение автопродления — новым платежом в Orchestra (/start → «🪐 Открыть Orchestra»)."
                : "⚠️ Карта не была привязана.";
        SendMessage msg = new SendMessage(chatId.toString(), text);
        telegramSender.execute(msg);
    }

    public void handleAutoRenewToggle(Long chatId, Long telegramId) throws TelegramApiException {
        Optional<Customer> userOpt = accessService.getUser(telegramId);
        if (userOpt.isEmpty() || userOpt.get().getAutorenewMethodId() == null) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ Автопродление не настроено. Оплатите подписку с опцией «С автопродлением» в Orchestra (/start → «🪐 Открыть Orchestra»)."));
            return;
        }
        boolean wasEnabled = userOpt.get().isAutorenewEnabled();
        if (wasEnabled) {
            autoRenewService.disableAutoRenew(telegramId);
        } else {
            autoRenewService.enableAutoRenew(telegramId);
        }
        // Показываем обновлённое состояние заново.
        sendAutoRenewInfo(chatId, telegramId);
    }
}
