package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.bot.SubscriptionBot;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.util.Emoji;
import com.orchestra.subscriptions.util.Money;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import com.orchestra.subscriptions.util.TimeFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * Тонкая обёртка над SubscriptionBot — чтобы контроллеры/сервисы не знали о Telegram API
 * и чтобы избежать циклической зависимости SubscriptionBot ↔ PaymentProcessingService.
 */
@Slf4j
@Service
public class NotificationService {

    /** Порог "крупного платежа" — оповещаем владельца только о платежах от 700₽ (мес. 6+). */
    private static final long LARGE_PAYMENT_KOPEKS_THRESHOLD = 70_000L;

    private final SubscriptionBot subscriptionBot;
    private final AppConfig appConfig;
    private final EmailService emailService;
    /** Замороженные подписки: о них надо напомнить ровно в момент истечения работающей. */
    private final com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository;
    private final Emoji emoji;

    public NotificationService(SubscriptionBot subscriptionBot, AppConfig appConfig, EmailService emailService,
                               com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionBot = subscriptionBot;
        this.appConfig = appConfig;
        this.emailService = emailService;
        this.emoji = new Emoji(appConfig.getEmoji());
    }

    public void sendPaymentSuccess(Long telegramId, Customer customer, AppConfig.Plan plan) {
        String publicBaseUrl = appConfig.getTelegram() != null
                ? appConfig.getTelegram().getPublicBaseUrl()
                : null;
        StringBuilder text = new StringBuilder();
        text.append(emoji.e("check")).append(" <b>Оплата получена</b>\n\n")
                .append("Тариф: ").append(plan.getName()).append("\n")
                .append("Действует до: ").append(TimeFormat.mskDate(customer.getExpiresAt())).append("\n\n")
                .append(SubscriptionLinks.subscriptionLinkHtml(customer.getSubscriptionLink(), publicBaseUrl, emoji))
                .append("\n\n");
        text.append("Инструкция по подключению: /help");
        sendHtml(telegramId, text.toString());
    }

    /**
     * Оплата легла НЕ в работающую подписку: либо в замороженную, либо в только что созданную.
     * <p>
     * Отдельный текст обязателен. Обычное «Оплата получена, действует до …» здесь было бы
     * ложью — срок работающей подписки не изменился, а купленные дни лежат замороженными.
     * Человек, увидев привычное сообщение, решит, что всё в порядке, и обнаружит расхождение в
     * самый неудачный момент. Поэтому здесь прямо сказано три вещи: сколько дней и куда легло,
     * что подписка не работает, и что текущий доступ продолжает работать.
     * <p>
     * Ссылку замороженной подписки НЕ печатаем: она не работает до включения, и выдать её
     * значило бы отправить человека вставлять в приложение заведомо мёртвый конфиг.
     * Кнопка «Включить» приезжает вместе с экраном подписок — до тех пор включает поддержка.
     *
     * @param subscription    подписка-получатель; {@code null} допустим — тогда без номера
     * @param willBeActivated живой подписки у человека не осталось, поэтому оплаченная
     *                        включается сразу. Тогда «она заморожена, Ваш доступ работает» —
     *                        ложь дважды: доступ как раз не работает, а ссылку человек не получил
     *                        бы вовсе и решил бы, что оплата пропала
     */
    public void sendPaymentIntoOtherSubscription(Long telegramId, AppConfig.Plan plan,
                                                 Subscription subscription, boolean willBeActivated) {
        String number = subscription != null ? " #" + subscription.getSeq() : "";
        if (willBeActivated) {
            String publicBaseUrl = appConfig.getTelegram() != null
                    ? appConfig.getTelegram().getPublicBaseUrl() : null;
            String text = emoji.e("check") + " <b>Оплата получена</b>\n\n"
                    + "Тариф: " + plan.getName() + "\n"
                    + "Подписка" + number + " включена.\n\n"
                    + (subscription != null
                            ? SubscriptionLinks.subscriptionLinkHtml(
                                    subscription.getSubscriptionLink(), publicBaseUrl, emoji) + "\n\n"
                            : "")
                    + "⚠️ Ссылка новая. Замените её в приложении.\n"
                    + "Инструкция: /help";
            sendHtml(telegramId, text);
            return;
        }
        String text = emoji.e("check") + " <b>Оплата получена</b>\n\n"
                + "Тариф: " + plan.getName() + "\n"
                + "Срок зачислен в подписку" + number + " — она <b>заморожена</b>.\n\n"
                + "Срок замороженной подписки не расходуется, её ссылка заработает после "
                + "включения. Действующая подписка продолжает работать.";
        // Кнопка первым рядом. Без неё «оплатил — на устройстве ничего не изменилось»
        // остаётся без выхода: человек не знает, что подписку надо включить, и идёт
        // в поддержку с «деньги списали, доступ не поменялся».
        sendHtmlWithActivateButton(telegramId, text, subscription);
    }

    /**
     * Возврат прошёл по ЗАМОРОЖЕННОЙ подписке. Работающая при этом не изменилась ни на день,
     * поэтому обычные тексты возврата («подписка отключена» / «срок уменьшен до …») здесь
     * напугали бы человека, у которого доступ как работал, так и работает.
     */
    public void sendRefundFromParked(Long telegramId, AppConfig.Plan plan,
                                     Subscription parked, long refundedKopeks) {
        long daysLeft = SubscriptionTime.displayDays(
                parked.getBalanceSeconds() == null ? 0L : parked.getBalanceSeconds());
        // Про работающую подписку здесь ничего не обещаем. Возврат откатывает ещё и
        // реферальные бонусы, а они начислялись ЧЕЛОВЕКУ — то есть срок работающей подписки
        // всё-таки может уменьшиться. Обещание «его этот возврат не затронул» в таком случае
        // оказалось бы враньём ровно в тот момент, когда человек проверяет, что доступ живой.
        String text = "💸 <b>Возврат выполнен</b>\n\n"
                + "Возвращено " + Money.rub(refundedKopeks) + " ₽ за тариф «" + plan.getName() + "».\n"
                + "Срок списан с замороженной подписки #" + parked.getSeq()
                + ", остаток: " + daysLeft + " дн.\n\n"
                + "Текущий срок и статус: /status";
        sendHtml(telegramId, text);
    }

    /**
     * Отправляет magic-link для входа в web-ЛК. HTML с кнопкой + plain-text fallback.
     * <p>
     * Шаблон intentionally простой — inline-styles, без CSS-классов и сложного
     * layout'а. Outlook (особенно через Exchange) рендерит CSS-классы непоследовательно;
     * inline-style на каждом элементе — единственный надёжный способ. Кнопка собрана
     * через {@code <a style="...">} а не через {@code <button>}: button-теги в письмах
     * не кликабельны в большинстве почтовиков.
     * <p>
     * <b>Anti-forward warning:</b> magic-link даёт мгновенный доступ к ЛК без пароля.
     * Если юзер перешлёт письмо другу/жене «глянь подписку» — получатель кликом перехватит
     * аккаунт. Видимое предупреждение в теле снижает шанс на 95% случаев (благие
     * намерения). Технически защитить от пересылки нельзя без IP/UA-binding'а, что
     * ломает обычный кейс «прочитал на телефоне → кликнул на ноутбуке».
     * <p>
     * Plain-text fallback показывается клиентам без HTML и в превью inbox: содержит
     * ту же ссылку явным текстом, чтобы юзер мог её скопировать вручную.
     *
     * @param email   адрес получателя (предполагается что у юзера он есть; caller проверяет)
     * @param fullUrl полный URL вида https://example.com/auth?token=...
     * @return {@code true} если SMTP принял; {@code false} если адрес заблокирован
     *         провайдером (suppression list) или другой fail. Caller (контроллер) может
     *         показать юзеру внятную ошибку «не получилось отправить, проверьте адрес».
     */
    public boolean sendMagicLinkEmail(String email, String fullUrl) {
        if (email == null || email.isBlank()) {
            log.warn("sendMagicLinkEmail skipped: empty email");
            return false;
        }
        String subject = "Orchestra — вход в личный кабинет";

        // HTML-escape для URL — defence-in-depth. Сейчас fullUrl состоит из
        // publicBaseUrl (controlled config) + "/auth?token=" + token (base64url,
        // только [A-Za-z0-9_-]). Никаких HTML-метасимволов попасть не может.
        // Но если в будущем URL начнёт нести user-input (например ?source=...),
        // без escape он стал бы вектором HTML/JS-injection в письме.
        String safeUrl = HtmlUtils.htmlEscape(fullUrl);

        // HTML с inline-стилями. Тёмный фон + accent-зелёная кнопка — синхронизированы
        // с Mini App-палитрой (см. style.css :root). Не пытаемся max-width / responsive —
        // 480px достаточно для desktop и mobile почтовиков. Для Outlook добавил
        // mso-padding-alt, потому что без него padding'и игнорируются.
        String html = ""
                + "<!DOCTYPE html><html><body style=\"margin:0;padding:24px;background:#0d1117;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#e6edf3;\">"
                + "<div style=\"max-width:480px;margin:0 auto;background:#161b22;border:1px solid #30363d;"
                + "border-radius:16px;padding:32px;\">"
                + "<h1 style=\"margin:0 0 16px;font-size:20px;color:#3fb950;\">Orchestra</h1>"
                + "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.5;\">"
                + "Запрошен вход в Ваш личный кабинет Orchestra."
                + "</p>"
                + "<p style=\"margin:0 0 24px;font-size:15px;line-height:1.5;\">"
                + "Если это Вы, нажмите кнопку ниже. Ссылка действует <b>15 минут</b> и срабатывает один раз."
                + "</p>"
                + "<div style=\"text-align:center;margin:0 0 24px;\">"
                + "<a href=\"" + safeUrl + "\" "
                + "style=\"display:inline-block;background:#3fb950;color:#0d1117;text-decoration:none;"
                + "padding:14px 28px;border-radius:12px;font-weight:700;font-size:15px;"
                + "mso-padding-alt:14px 28px;\">Войти в кабинет</a>"
                + "</div>"
                + "<p style=\"margin:0 0 8px;font-size:12px;color:#8b949e;line-height:1.5;\">"
                + "Если кнопка не открывается, скопируйте ссылку в браузер:"
                + "</p>"
                + "<p style=\"margin:0 0 24px;font-size:12px;color:#8b949e;word-break:break-all;\">"
                + "<a href=\"" + safeUrl + "\" style=\"color:#7ec896;\">" + safeUrl + "</a>"
                + "</p>"
                + "<p style=\"margin:0 0 12px;padding:10px 12px;background:#2d1b0e;border-left:3px solid #ff8a80;"
                + "border-radius:4px;font-size:12px;color:#ff8a80;line-height:1.5;\">"
                + "⚠️ <b>Не пересылайте это письмо.</b> Ссылка открывает доступ к подписке без пароля: "
                + "получатель письма сможет войти в Ваш кабинет."
                + "</p>"
                + "<p style=\"margin:0;font-size:12px;color:#8b949e;line-height:1.5;\">"
                + "Если Вы не запрашивали вход, проигнорируйте письмо — действий не требуется."
                + "</p>"
                + "</div>"
                + "</body></html>";

        String plain = ""
                + "Orchestra — вход в личный кабинет\n\n"
                + "Запрошен вход в Ваш кабинет Orchestra.\n"
                + "Если это Вы, откройте ссылку ниже (действует 15 минут, срабатывает один раз):\n\n"
                + fullUrl + "\n\n"
                + "⚠️ Не пересылайте это письмо: ссылка открывает доступ к подписке без пароля.\n\n"
                + "Если Вы не запрашивали вход, проигнорируйте письмо.\n";

        return emailService.sendHtml(email, subject, html, plain);
    }

    /**
     * Резервная email-копия ссылки подписки после успешной оплаты. Юзер опционально
     * сохраняет email в Mini App (Профиль → Email); если сохранил — на каждый
     * payment.SUCCEEDED шлём ему то же содержимое, что и DM в Telegram, в виде
     * multipart-письма (HTML + plain-text fallback).
     * <p>
     * Цель: если Telegram заблокируют или потеряет аккаунт — у юзера всё равно есть
     * актуальная ссылка подписки. Это НЕ маркетинговая рассылка: письмо приходит
     * только в ответ на конкретное действие (оплату), и в нём — инструкция как
     * отписаться (удалить email в Mini App).
     * <p>
     * <b>Почему HTML (а не plain через {@link EmailService#send}):</b> Gmail и другие
     * крупные почтовики у транзакционных писем с новых/низко-репутационных доменов
     * агрессивно режут plain-text-only как подозрительное (нормальная транзакционка
     * почти всегда HTML с брендингом). Магик-линк через HTML доезжает, а plain «подписка
     * активирована» молча дропался — выявлено на тесте с адресом вида {@code user@example.com}:
     * SMTP-лог говорил «Email sent» (Unisender принял), а в Inbox/Spam/Promotions
     * у юзера ничего. Перевели на тот же шаблон, что и magic-link.
     * <p>
     * Контракт {@link EmailService#sendHtml}: исключения swallow'ятся → платёжный
     * флоу не упадёт, даже если SMTP лежит или {@code app.email.enabled=false}.
     */
    public void sendPaymentSuccessEmail(Customer customer, AppConfig.Plan plan) {
        String email = customer.getEmail();
        if (email == null || email.isBlank()) return;
        String subject = "Orchestra — подписка активирована";
        String guideUrl = appConfig.getTelegram() != null
                ? appConfig.getTelegram().getGuideUrl()
                : null;
        String subLink = customer.getSubscriptionLink();
        String publicBaseUrl = appConfig.getTelegram() != null
                ? appConfig.getTelegram().getPublicBaseUrl()
                : null;
        String accessLink = SubscriptionLinks.accessLink(subLink, publicBaseUrl);
        String expiresStr = TimeFormat.mskDate(customer.getExpiresAt());

        // Escape всех user-string'ов — defence-in-depth. plan.getName() ascii, но
        // если в будущем будет промо-план с символами «»<>& — без escape это
        // HTML-injection. subscriptionLink имеет & = ? — те же причины. guideUrl —
        // внешний URL из config'а.
        String safePlanName = HtmlUtils.htmlEscape(plan.getName());
        String safeSubLink = HtmlUtils.htmlEscape(subLink);
        String safeAccessLink = accessLink.isEmpty() ? "" : HtmlUtils.htmlEscape(accessLink);
        String safeGuide = guideUrl != null ? HtmlUtils.htmlEscape(guideUrl) : null;

        // HTML с inline-стилями — палитра и каркас совпадают с sendMagicLinkEmail
        // (тёмный фон + accent #3fb950, карточка 480px). Ссылка подписки в <pre>
        // с word-break — длинные подписочные URL не должны разламывать
        // вёрстку. mso-padding-alt — для Outlook'а, у него inline-padding на <a>
        // игнорируется без этого.
        StringBuilder html = new StringBuilder(2048);
        html.append("<!DOCTYPE html><html><body style=\"margin:0;padding:24px;background:#0d1117;")
                .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#e6edf3;\">")
                .append("<div style=\"max-width:480px;margin:0 auto;background:#161b22;border:1px solid #30363d;")
                .append("border-radius:16px;padding:32px;\">")
                .append("<h1 style=\"margin:0 0 16px;font-size:20px;color:#3fb950;\">Orchestra</h1>")
                .append("<p style=\"margin:0 0 20px;font-size:15px;line-height:1.5;\">")
                .append("✅ <b>Подписка активирована.</b>")
                .append("</p>")
                .append("<table style=\"width:100%;margin:0 0 20px;font-size:14px;line-height:1.7;\">")
                .append("<tr><td style=\"color:#8b949e;padding-right:12px;white-space:nowrap;\">Тариф:</td>")
                .append("<td><b>").append(safePlanName).append("</b></td></tr>")
                .append("<tr><td style=\"color:#8b949e;padding-right:12px;white-space:nowrap;\">Действует до:</td>")
                .append("<td>").append(expiresStr).append("</td></tr>")
                .append("</table>")
                .append("<p style=\"margin:0 0 8px;font-size:13px;color:#8b949e;line-height:1.5;\">")
                .append("Ссылка подписки — вставьте её в клиентское приложение:")
                .append("</p>")
                .append("<pre style=\"margin:0 0 24px;padding:12px;background:#0d1117;border:1px solid #30363d;")
                .append("border-radius:8px;font-size:12px;color:#7ec896;word-break:break-all;white-space:pre-wrap;")
                .append("font-family:'SF Mono',Monaco,Consolas,monospace;\">")
                .append(safeSubLink)
                .append("</pre>");
        if (safeGuide != null && !safeGuide.isBlank()) {
            html.append("<div style=\"text-align:center;margin:0 0 24px;\">")
                    .append("<a href=\"").append(safeGuide).append("\" ")
                    .append("style=\"display:inline-block;background:#3fb950;color:#0d1117;text-decoration:none;")
                    .append("padding:14px 28px;border-radius:12px;font-weight:700;font-size:15px;")
                    .append("mso-padding-alt:14px 28px;\">Инструкция по подключению</a>")
                    .append("</div>");
        }
        html.append("<p style=\"margin:0;font-size:12px;color:#8b949e;line-height:1.5;\">")
                .append("Это резервная копия. Основные уведомления приходят в Telegram-бот. ")
                .append("Чтобы отказаться от писем, удалите адрес в Mini App: ")
                .append("Профиль → Email.")
                .append("</p>")
                .append("</div></body></html>");

        // Plain-text fallback для клиентов, не отображающих HTML, и для inbox-previews.
        // Тот же контент без разметки. URL'ы — как есть.
        StringBuilder plain = new StringBuilder(512);
        plain.append("Orchestra\n\n")
                .append("Подписка активирована.\n")
                .append("Тариф: ").append(plan.getName()).append("\n")
                .append("Действует до: ").append(expiresStr).append("\n\n")
                .append("Ссылка подписки — вставьте её в клиентское приложение:\n")
                .append(subLink).append("\n\n");
        if (guideUrl != null && !guideUrl.isBlank()) {
            plain.append("Инструкция: ").append(guideUrl).append("\n\n");
        }
        plain.append("Это резервная копия. Основные уведомления приходят в Telegram-бот.\n")
                .append("Чтобы отказаться от писем, удалите адрес в Mini App: ")
                .append("Профиль → Email.\n");

        boolean sent = emailService.sendHtml(email, subject, html.toString(), plain.toString());
        if (!sent) {
            // SMTP-провайдер забраковал адрес (suppression list / hard bounce / spam complaint).
            // Юзер видит «email привязан» в Mini App, ждёт письмо — не доходит, думает мы
            // сломались. Шлём DM в Telegram с пояснением + указанием что ссылка-конфиг у него
            // уже есть выше (sendPaymentSuccess отправил её обычным DM до email'а).
            sendEmailDeliveryFailedDm(customer.getTelegramId());
        }
    }

    /**
     * Fallback DM когда не удалось доставить email (suppression list / bounce / etc).
     * Отдельный private helper, потому что вызывается только из {@link #sendPaymentSuccessEmail}
     * — magic-link имеет свой UX (юзер на форме видит result HTTP-запроса), а expiry/winback
     * (не реализовано через email) тоже не нуждаются — это TG-only.
     * <p>
     * Текст не дублирует ссылку-конфиг: её юзер ровно сейчас получил отдельным DM
     * через {@link #sendPaymentSuccess}. Просто извещаем что email-копия не дошла.
     */
    private void sendEmailDeliveryFailedDm(Long telegramId) {
        if (telegramId == null) return;
        String text = emoji.e("warn") + " <b>Копия подписки не дошла на email</b>\n\n"
                + "Почтовый провайдер заблокировал Ваш адрес.\n\n"
                + "Подписка активна, ссылка-конфиг — в сообщении выше.\n\n"
                + "Чтобы получать копию по почте, укажите другой адрес в Mini App: "
                + "<b>Профиль → Email</b>.";
        sendHtml(telegramId, text);
    }

    /**
     * DM юзеру, у которого email был автоматически очищен после 30 дней без верификации.
     * Шлётся из {@link EmailLifecycleService#releaseStaleUnverifiedEmails}.
     * <p>
     * Текст deliberately нейтральный: 30 дней назад юзер мог как **сам** добавить email
     * и забыть, так и **случайно** ввести чужой адрес. Не виним и не упрекаем.
     */
    public void sendEmailAutoReleasedDm(Long telegramId) {
        if (telegramId == null) return;
        String text = emoji.e("notice") + " <b>Email очищен из профиля</b>\n\n"
                + "Адрес не был подтверждён в течение 30 дней и удалён — этого требуют правила "
                + "обработки персональных данных.\n\n"
                + "Чтобы получать копию подписки по почте, добавьте email заново в Mini App: "
                + "<b>Профиль → Email</b> — и подтвердите его по ссылке из письма.";
        sendHtml(telegramId, text);
    }

    public void sendExpiryWarning24h(Long telegramId) {
        String text = emoji.e("warn") + " <b>Подписка истекает через 24 часа</b>\n\n"
                + "Продление без перерыва: /start\n"
                + "Оставшийся срок сохраняется, новые дни добавляются к нему."
                + frozenSubscriptionHint(telegramId);
        sendHtml(telegramId, text);
    }

    public void sendExpiryWarning1h(Long telegramId) {
        String text = emoji.e("urgent") + " <b>Подписка истекает через 1 час</b>\n\n"
                + "После истечения доступ будет отключён. Продление: /start";
        sendHtml(telegramId, text);
    }

    /**
     * @param chargedKopeks ФАКТИЧЕСКИ списанная сумма, а не {@code plan.priceKopeks}: цена
     *                      зависит от ступени устройств ({@code app.device-tiers}), и юзер на
     *                      «3 устройства» видел «179 ₽» при реальном списании 215 ₽ — читалось
     *                      как «списали больше, чем написано».
     */
    public void sendAutoRenewSuccess(Long telegramId, AppConfig.Plan plan, long chargedKopeks) {
        String text = emoji.e("recycle") + " <b>Автопродление</b>\n\n"
                + "Подписка <b>" + plan.getName() + "</b> продлена.\n"
                + "Списано: " + Money.rub(chargedKopeks) + " ₽\n\n"
                + "Настройки автопродления: /autorenew";
        sendHtml(telegramId, text);
    }

    public void sendAutoRenewFailed(Long telegramId) {
        String text = emoji.e("warn") + " <b>Автопродление не выполнено</b>\n\n"
                + "Карта отклонила платёж: недостаточно средств, лимит, истёкший срок "
                + "действия или отказ банка.\n\n"
                + "Продление вручную: /start\n"
                + "Настройки автопродления: /autorenew";
        sendHtml(telegramId, text);
    }

    /**
     * Напоминание: подписка оплачена, но не включена — деньги стоят, доступ у человека прежний.
     * Кнопка «Включить» обязательна: без неё напоминание сообщает о проблеме, но не даёт
     * способа её решить, и человек идёт в поддержку.
     */
    public void sendParkedSubscriptionReminder(Long telegramId, Subscription parked) {
        long days = SubscriptionTime.displayDays(
                parked.getBalanceSeconds() == null ? 0L : parked.getBalanceSeconds());
        String text = "❄️ <b>Подписка #" + parked.getSeq() + " не включена</b>\n\n"
                + "Остаток: " + days + " дн. Срок не расходуется. Доступ по ней не работает.\n\n"
                + "Одновременно действует одна подписка. Если Вас устраивает действующая, "
                + "действий не требуется.";
        sendHtmlWithActivateButton(telegramId, text, parked);
    }

    /**
     * Приписка «у Вас есть замороженная подписка на N дней» — к предупреждению об истечении
     * и к winback.
     * <p>
     * Момент истечения ровно тот, когда эта информация нужна: человек читает «доступ
     * отключён» и идёт покупать заново, хотя оплаченные дни у него уже лежат. Промолчать
     * здесь значит продать второй раз то, за что уже заплачено.
     */
    private String frozenSubscriptionHint(Long telegramId) {
        try {
            return subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId).stream()
                    .filter(s -> !s.isCurrent())
                    .filter(s -> s.getBalanceSeconds() != null && s.getBalanceSeconds() > 0)
                    .findFirst()
                    .map(s -> "\n\n\u2744\ufe0f У Вас есть замороженная подписка #" + s.getSeq()
                            + ", остаток " + com.orchestra.subscriptions.service.SubscriptionTime
                                    .displayDays(s.getBalanceSeconds())
                            + " дн. Включение: /mysubs")
                    .orElse("");
        } catch (Exception e) {
            // Подсказка не может быть причиной несостоявшегося предупреждения об истечении.
            log.warn("Не смог проверить замороженные подписки для telegramId={}", telegramId, e);
            return "";
        }
    }

    /** @param refundedKopeks фактически возвращённая сумма (см. {@link #sendAutoRenewSuccess}). */
    public void sendRefundDisabled(Long telegramId, AppConfig.Plan plan, long refundedKopeks) {
        String text = emoji.e("check") + " <b>Возврат подтверждён YooKassa</b>\n\n"
                + "Тариф «" + plan.getName() + "» — " + Money.rub(refundedKopeks) + " ₽.\n"
                + "Деньги придут на карту в течение <b>1–3 рабочих дней</b> "
                + "(срок зависит от банка-эмитента).\n\n"
                + "Подписка отключена.\n"
                + "Оформление новой подписки: /start";
        sendHtml(telegramId, text);
    }

    /**
     * Поздравление победителю конкурса в момент применения отложенного гранта.
     * Шлётся из {@code PendingGrantService.applyIfAny} — тот вызывается из {@code /start}
     * хука в боте и из {@code /api/me} в Mini App, так что сообщение придёт ровно один
     * раз: в момент первого взаимодействия с продуктом после выдачи приза.
     * <p>
     * Кнопка «Открыть Orchestra» — webApp-кнопка, по тапу нативно открывает Mini App
     * через встроенный WebView. Без неё юзер видел бы только текст и должен был сам
     * соображать «куда идти», как раньше при ручном {@code /extend}.
     */
    public void sendGrantApplied(Long telegramId, int totalDays, java.time.LocalDateTime expiresAt) {
        String text = emoji.e("party") + " <b>Начислена подписка</b>\n\n"
                + "Вам начислено <b>" + totalDays + " дн.</b> подписки Orchestra.\n"
                + "Действует до: <b>" + TimeFormat.mskDate(expiresAt) + "</b>\n\n"
                + "Подключение — в Mini App:";

        InlineKeyboardButton openAppButton = new InlineKeyboardButton("🪐 Открыть Orchestra");
        openAppButton.setWebApp(new WebAppInfo(appConfig.getTelegram().getMiniAppBaseUrl() + "/app/"));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(new ArrayList<>());
        markup.setKeyboard(List.of(new InlineKeyboardRow(openAppButton)));

        try {
            SendMessage msg = new SendMessage(telegramId.toString(), text);
            msg.setParseMode("HTML");
            msg.setReplyMarkup(markup);
            subscriptionBot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send grant DM to user {}", telegramId, e);
        }
    }

    /** Post-expiry winback: отправляется один раз через 2-4 дня после истечения подписки. */
    public void sendWinback(Long telegramId) {
        String text = emoji.e("notice") + " <b>Напоминание</b>\n\n"
                + "Подписка Orchestra истекла, доступ отключён.\n\n"
                + "Возобновление доступа: /start"
                + frozenSubscriptionHint(telegramId);
        sendHtml(telegramId, text);
    }

    /** @param refundedKopeks фактически возвращённая сумма (см. {@link #sendAutoRenewSuccess}). */
    public void sendRefundReduced(Long telegramId, AppConfig.Plan plan,
                                  java.time.LocalDateTime newExpiry, long refundedKopeks) {
        String text = emoji.e("check") + " <b>Возврат подтверждён YooKassa</b>\n\n"
                + "Тариф «" + plan.getName() + "» — " + Money.rub(refundedKopeks) + " ₽.\n"
                + "Деньги придут на карту в течение <b>1–3 рабочих дней</b> "
                + "(срок зависит от банка-эмитента).\n\n"
                + "Срок действующей подписки сокращён до: <b>"
                + TimeFormat.mskDate(newExpiry) + "</b>";
        sendHtml(telegramId, text);
    }

    /**
     * Уведомить владельца о возврате (любым провайдером — YooKassa, Stars, или ручной refund
     * через ЛК). Срабатывает на КАЖДЫЙ возврат, без порога — возвраты редкие и важные.
     */
    public void notifyOwnerRefund(Payment payment, AppConfig.Plan plan) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        String userTag = userTag(payment.getTelegramUsername(), payment.getTelegramId());
        long amountKopeks = payment.getRefundAmountKopeks() != null
                ? payment.getRefundAmountKopeks()
                : payment.getAmountKopeks();
        StringBuilder text = new StringBuilder("↩️ <b>Возврат</b>\n\n")
                .append("Юзер: ").append(userTag).append(" (id=").append(payment.getTelegramId()).append(")\n")
                .append("Тариф: ").append(plan.getName()).append("\n")
                .append("Сумма: ").append(Money.rub(amountKopeks)).append(" ₽\n")
                .append("Провайдер: ").append(payment.getProvider());
        sendHtmlSilently(ownerId, text.toString());
    }

    /**
     * Уведомить владельца, что в {@link ReferralService#maybeAwardReferral} частично
     * упало начисление бонуса (у одной из двух сторон — referee или referrer —
     * {@code activatePaidSubscription} выбросил исключение). paid_at уже проставлен,
     * поэтому автоматический ретрай не произойдёт — нужен ручной фикс владельцем.
     */
    public void notifyOwnerReferralBonusError(Long referrerTelegramId,
                                               Long refereeTelegramId,
                                               boolean refereeFailed,
                                               boolean referrerFailed,
                                               String exceptionMessage) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        StringBuilder text = new StringBuilder();
        text.append("⚠️ <b>Реферальный бонус — частичный сбой</b>\n\n")
                .append("Реферер: id=").append(referrerTelegramId).append("\n")
                .append("Реферал: id=").append(refereeTelegramId).append("\n\n")
                .append("Упало начисление: ");
        if (refereeFailed && referrerFailed) {
            text.append("оба (referee +").append(ReferralService.REFEREE_BONUS_DAYS)
                    .append("d и referrer +").append(ReferralService.REFERRER_BONUS_DAYS).append("d)");
        } else if (refereeFailed) {
            text.append("referee (+").append(ReferralService.REFEREE_BONUS_DAYS).append("d)");
        } else {
            text.append("referrer (+").append(ReferralService.REFERRER_BONUS_DAYS).append("d)");
        }
        text.append("\n\n")
                .append("paid_at уже проставлен — автоматического ретрая не будет. ")
                .append("Начислите недостающие дни вручную через /extend.\n\n")
                .append("Ошибка: ").append(exceptionMessage);
        sendHtmlSilently(ownerId, text.toString());
    }

    /**
     * Уведомить владельца, что автосписание у юзера упало (карта отклонена / network /
     * статус не succeeded). Без этого алерта первые failure после включения recurrent
     * payments будут невидимы: владелец узнает о проблеме только когда юзер придёт
     * в поддержку жаловаться, что доступ выключился без предупреждения.
     */
    public void notifyOwnerAutorenewFailure(Customer user, String errorMessage) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        String userTag = userTag(user.getTelegramUsername(), user.getTelegramId());
        String text = "❌ <b>Автопродление не сработало</b>\n\n"
                + "Юзер: " + userTag + " (id=" + user.getTelegramId() + ")\n"
                + "Тариф: " + user.getAutorenewPlanId() + "\n"
                + "Ошибка: " + (errorMessage != null ? errorMessage : "—") + "\n\n"
                + "Юзер получил уведомление, retry не будет (one-shot policy). "
                + "Если массово — проверьте кабинет YooKassa.";
        sendHtmlSilently(ownerId, text);
    }

    /**
     * Уведомить владельца о расхождении БД ↔ AccessProvider: DB-изменение применилось, а
     * AccessProvider-вызов упал (или наоборот — в activate-пути мы откатываем транзакцию, но
     * алерт всё равно шлём, чтобы владелец знал о недоступности панели). Без алерта
     * юзер может получить «подвисший» доступ или, наоборот, отсутствие доступа при
     * оплаченной подписке — и вернётся жаловаться.
     */
    public void notifyOwnerAccessSyncFailure(String context, Long telegramId,
                                              String providerUsername, String errorMessage) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        String text = "🛰️⚠️ <b>AccessProvider sync mismatch</b>\n\n"
                + "Контекст: " + context + "\n"
                + "Юзер: id=" + telegramId
                + (providerUsername != null ? " (" + providerUsername + ")" : "") + "\n"
                + "Ошибка: " + (errorMessage != null ? errorMessage : "—") + "\n\n"
                + "Проверьте панель провайдера — возможно, надо руками выровнять срок и статус.";
        sendHtmlSilently(ownerId, text);
    }

    /**
     * Уведомить владельца о том, что цель платежа не сошлась: названа несуществующая или чужая
     * подписка, либо к моменту активации целевая подписка перестала быть текущей.
     * <p>
     * Деньги при этом не зависают и платёж не остаётся PENDING — он зачисляется в текущую
     * подписку, то есть в ту, которой человек пользуется. Алерт нужен именно потому, что
     * снаружи всё выглядит нормально: без него единственным способом узнать о расхождении
     * была бы жалоба «заплатил, а дни не там».
     *
     * @param paymentId id строки платежа; {@code null}, если платежа ещё нет (отказ на чекауте)
     */
    public void notifyOwnerPaymentTargetAnomaly(Long telegramId, Long paymentId, String reason) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        String text = "🎯⚠️ <b>Цель платежа не сошлась</b>\n\n"
                + "Юзер: id=" + telegramId + "\n"
                + (paymentId != null ? "Платёж: id=" + paymentId + "\n" : "")
                + "Причина: " + HtmlUtils.htmlEscape(reason == null ? "—" : reason) + "\n\n"
                + "Деньги зачислены в текущую подписку — проверьте, туда ли рассчитывал юзер.";
        sendHtmlSilently(ownerId, text);
    }

    /**
     * Уведомить владельца о крупном платеже (>= {@value #LARGE_PAYMENT_KOPEKS_THRESHOLD} коп.).
     * Тихо игнорит мелкие платежи и отсутствие owner-id, чтобы не спамить логи.
     */
    public void notifyOwnerLargePayment(Payment payment, AppConfig.Plan plan) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        if (ownerId == null) return;
        if (payment.getAmountKopeks() < LARGE_PAYMENT_KOPEKS_THRESHOLD) return;
        String userTag = userTag(payment.getTelegramUsername(), payment.getTelegramId());
        String text = "💰 <b>Крупный платёж</b>\n\n"
                + "Юзер: " + userTag + " (id=" + payment.getTelegramId() + ")\n"
                + "Тариф: " + plan.getName() + "\n"
                + "Сумма: " + Money.rub(payment.getAmountKopeks()) + " ₽\n"
                + "Провайдер: " + payment.getProvider();
        sendHtmlSilently(ownerId, text);
    }

    /** Тег юзера для owner-уведомлений: «@username» если есть, иначе «id=&lt;telegramId&gt;». */
    private static String userTag(String username, Long telegramId) {
        return username != null && !username.isEmpty()
                ? "@" + username
                : "id=" + telegramId;
    }

    /** Шлёт HTML-сообщение, не выбрасывая исключения наверх — owner-уведомления не должны
     *  ломать платёжный или refund-флоу. */
    private void sendHtmlSilently(Long telegramId, String text) {
        try {
            SendMessage msg = new SendMessage(telegramId.toString(), text);
            msg.setParseMode("HTML");
            subscriptionBot.execute(msg);
        } catch (Exception e) {
            log.warn("Failed to send owner notification to {}: {}", telegramId, e.getMessage());
        }
    }

    public void sendPlainText(Long telegramId, String text) {
        try {
            SendMessage msg = new SendMessage(telegramId.toString(), text);
            subscriptionBot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send plain text to user {}", telegramId, e);
        }
    }


    /**
     * Отправляет сообщение с кнопкой «Включить подписку #N» первым рядом.
     * <p>
     * Подписка могла быть не передана (исторический вызов) — тогда просто текст: кнопка,
     * ведущая в никуда, хуже её отсутствия.
     */
    private void sendHtmlWithActivateButton(Long telegramId, String text, Subscription subscription) {
        if (subscription == null || subscription.getId() == null) {
            sendHtml(telegramId, text);
            return;
        }
        try {
            SendMessage msg = new SendMessage(telegramId.toString(), text);
            msg.setParseMode("HTML");
            InlineKeyboardButton activate = new InlineKeyboardButton(
                    "\u25b6\ufe0f Включить подписку #" + subscription.getSeq());
            activate.setCallbackData("sub:switch:" + subscription.getId());
            InlineKeyboardButton list = new InlineKeyboardButton("\ud83d\udccb Мои подписки");
            list.setCallbackData("sub:list");
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(new ArrayList<>());
            markup.setKeyboard(List.of(new InlineKeyboardRow(activate), new InlineKeyboardRow(list)));
            msg.setReplyMarkup(markup);
            subscriptionBot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send activate-button message to user {}", telegramId, e);
        }
    }

    private void sendHtml(Long telegramId, String text) {
        try {
            SendMessage msg = new SendMessage(telegramId.toString(), text);
            msg.setParseMode("HTML");
            subscriptionBot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send HTML message to user {}", telegramId, e);
        }
    }
}
