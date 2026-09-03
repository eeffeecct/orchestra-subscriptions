package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.service.EmailSettingsService;
import com.orchestra.subscriptions.service.AccessService;
import com.orchestra.subscriptions.util.Emoji;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkButtons;
import com.orchestra.subscriptions.util.IkMarkup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Email-флоу в чат-боте — паритет с Mini App для юзеров, у которых WebView не открывается:
 * статус адреса, ввод/смена (через reply-флоу {@link PendingInputService}), удаление
 * (двухшагово — отзыв согласия 152-ФЗ) и отправка magic-link для входа в web-ЛК.
 * Вся логика в {@link EmailSettingsService} (общая с Mini App), здесь — только рендер.
 */
@Slf4j
@Component
public class EmailBotHandler {

    private final EmailSettingsService emailSettingsService;
    private final AccessService accessService;
    private final PendingInputService pendingInputService;
    private final TelegramSender telegramSender;
    private final Emoji emoji;

    public EmailBotHandler(EmailSettingsService emailSettingsService, AccessService accessService,
                           PendingInputService pendingInputService, TelegramSender telegramSender,
                           AppConfig appConfig) {
        this.emailSettingsService = emailSettingsService;
        this.accessService = accessService;
        this.pendingInputService = pendingInputService;
        this.telegramSender = telegramSender;
        this.emoji = new Emoji(appConfig.getEmoji());
    }

    /** Экран «✉️ Email»: текущий адрес/статус + действия. */
    public void sendEmailScreen(Long chatId, Long telegramId) throws TelegramApiException {
        Optional<Customer> userOpt = accessService.getUser(telegramId);
        String email = userOpt.map(Customer::getEmail).orElse(null);
        boolean verified = userOpt.map(u -> u.getEmailVerifiedAt() != null).orElse(false);

        StringBuilder sb = new StringBuilder("✉️ <b>Email</b>\n\n");
        if (email == null || email.isBlank()) {
            sb.append("Адрес не указан.\n\n")
                    .append("<i>Email нужен на случай потери доступа к Telegram: на него приходит ")
                    .append("ссылка для входа в веб-кабинет.</i>");
        } else {
            sb.append("Адрес: <code>").append(HtmlUtils.htmlEscape(email, "UTF-8")).append("</code>\n")
                    .append("Статус: ").append(verified ? "подтверждён ✅" : "не подтверждён")
                    .append("\n\n<i>Вход в веб-кабинет: запросите ссылку, письмо придёт на этот адрес.</i>");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardButton setBtn = new IkButton();
        setBtn.setText(email == null || email.isBlank() ? "✏️ Указать email" : "✏️ Сменить email");
        setBtn.setCallbackData("email:ask");
        rows.add(new InlineKeyboardRow(setBtn));
        if (email != null && !email.isBlank()) {
            InlineKeyboardButton linkBtn = IkButtons.icon(emoji, "key", "🔑", "Ссылка для входа в веб-кабинет");
            linkBtn.setCallbackData("email:link");
            rows.add(new InlineKeyboardRow(linkBtn));
            InlineKeyboardButton delBtn = new IkButton();
            delBtn.setText("🗑 Удалить email");
            delBtn.setCallbackData("email:delete");
            rows.add(new InlineKeyboardRow(delBtn));
        }
        InlineKeyboardButton backBtn = new IkButton();
        backBtn.setText("⬅️ Назад");
        backBtn.setCallbackData("cabinet");
        rows.add(new InlineKeyboardRow(backBtn));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /** «Указать/сменить email» → ждём адрес следующим сообщением (152-ФЗ согласие — в тексте). */
    public void askEmail(Long chatId, Long telegramId) throws TelegramApiException {
        pendingInputService.expect(telegramId, PendingInputService.Kind.EMAIL, null);
        SendMessage msg = new SendMessage(chatId.toString(),
                "Отправьте email одним сообщением.\n\n"
                        + "<i>Отправляя адрес, Вы соглашаетесь на обработку email для восстановления "
                        + "доступа и сервисных писем (152-ФЗ). Адрес можно удалить в любой момент "
                        + "в этом же меню.</i>\n\nОтмена: /cancel");
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    /** Ввод адреса из reply-флоу (consent = факт отправки после явного уведомления в askEmail). */
    public void handleEmailInput(Long chatId, Long telegramId, String text) throws TelegramApiException {
        EmailSettingsService.Outcome outcome = emailSettingsService.updateEmail(telegramId, text, true);
        if (!outcome.ok()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ " + outcome.message() + (
                            "invalid_format".equals(outcome.error())
                                    ? "\n\nПовторите ввод: «✏️ Указать email»." : "")));
            return;
        }
        SendMessage ok = new SendMessage(chatId.toString(),
                "✅ Email сохранён: <code>" + HtmlUtils.htmlEscape(outcome.email(), "UTF-8") + "</code>\n\n"
                        + "Ссылку для входа в веб-кабинет можно запросить в этом же меню.");
        ok.setParseMode("HTML");
        telegramSender.execute(ok);
        sendEmailScreen(chatId, telegramId);
    }

    /** Подтверждение удаления (отзыв согласия — не должно случаться missclick'ом). */
    public void confirmDelete(Long chatId) throws TelegramApiException {
        InlineKeyboardButton yes = new IkButton();
        yes.setText("🗑 Да, удалить");
        yes.setCallbackData("email:delete:confirm");
        InlineKeyboardButton no = new IkButton();
        no.setText("⬅️ Отмена");
        no.setCallbackData("email:open");
        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(yes), new InlineKeyboardRow(no)));
        SendMessage msg = new SendMessage(chatId.toString(),
                "Удалить email? Вход в веб-кабинет по ссылке из письма перестанет работать.");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    public void deleteConfirmed(Long chatId, Long telegramId) throws TelegramApiException {
        EmailSettingsService.Outcome outcome = emailSettingsService.deleteEmail(telegramId);
        telegramSender.execute(new SendMessage(chatId.toString(),
                outcome.ok() ? "Email удалён, согласие на обработку отозвано."
                        : "⚠️ " + outcome.message()));
        sendEmailScreen(chatId, telegramId);
    }

    public void sendLoginLink(Long chatId, Long telegramId) throws TelegramApiException {
        EmailSettingsService.Outcome outcome = emailSettingsService.sendLoginLink(telegramId);
        telegramSender.execute(new SendMessage(chatId.toString(),
                outcome.ok() ? "📮 " + outcome.message() : "⚠️ " + outcome.message()));
    }
}
