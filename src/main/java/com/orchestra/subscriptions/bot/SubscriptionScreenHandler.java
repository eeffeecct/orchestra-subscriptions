package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.service.AutoRenewInfoService;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.SubscriptionSwitchService;
import com.orchestra.subscriptions.service.SubscriptionTime;
import com.orchestra.subscriptions.util.Emoji;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkMarkup;
import com.orchestra.subscriptions.util.IkSendMessage;
import com.orchestra.subscriptions.util.SubscriptionFormat;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран «Мои подписки»: список и переключение между ними.
 * <p>
 * Отдельный кластер, а не ещё сто строк в {@code ScreenHandler} — тот уже за 1100 строк, и
 * добавлять туда целый экран значит делать его ещё менее пригодным к правкам.
 * <p>
 * <b>Главное свойство экрана подтверждения — назвать ВСЕ последствия до нажатия.</b>
 * Переключение меняет четыре вещи разом: чьи дни идут, чьи замирают, какая ссылка рабочая и
 * какие устройства остаются в слотах. Человек, узнавший про смену ссылки постфактум, считает
 * это поломкой сервиса и идёт в поддержку — а он всего лишь нажал кнопку, которая ему этого
 * не сказала.
 */
@Slf4j
@Component
public class SubscriptionScreenHandler {

    /** Сколько устройств назвать поимённо, прежде чем свернуть в «и ещё N». */
    private static final int MAX_DEVICES_NAMED = 5;

    private final AppConfig appConfig;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionSwitchService switchService;
    private final DeviceTrackingService deviceTrackingService;
    /** Какая из подписок продлится сама — на списке это второй вопрос после «какая работает». */
    private final com.orchestra.subscriptions.repository.CustomerRepository userRepository;
    private final AutoRenewInfoService autoRenewInfo;
    private final TelegramSender telegramSender;
    private final Emoji emoji;

    public SubscriptionScreenHandler(AppConfig appConfig,
                                     SubscriptionRepository subscriptionRepository,
                                     SubscriptionSwitchService switchService,
                                     DeviceTrackingService deviceTrackingService,
                                     com.orchestra.subscriptions.repository.CustomerRepository userRepository,
                                     AutoRenewInfoService autoRenewInfo,
                                     TelegramSender telegramSender) {
        this.appConfig = appConfig;
        this.subscriptionRepository = subscriptionRepository;
        this.switchService = switchService;
        this.deviceTrackingService = deviceTrackingService;
        this.userRepository = userRepository;
        this.autoRenewInfo = autoRenewInfo;
        this.telegramSender = telegramSender;
        this.emoji = new Emoji(appConfig.getEmoji());
    }

    // ========================
    //  Список
    // ========================

    /** Список подписок человека. У работающей печатаем ссылку, у замороженных — никогда. */
    public void sendSubscriptionList(Long chatId, Long telegramId) throws TelegramApiException {
        List<Subscription> subs = subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId);
        if (subs.isEmpty()) {
            send(chatId, emoji.e("rocket") + " <b>Мои подписки</b>\n\n"
                    + "Подписок нет.\n\nОформление: /start", null);
            return;
        }

        // Кого продлят автоматически. Без этой пометки список отвечает на вопрос «какая
        // работает» и молчит о втором по частоте — «за какую с меня спишут деньги».
        AutoRenewInfoService.Info renew = userRepository.findByTelegramId(telegramId)
                .map(autoRenewInfo::forUser).orElse(null);
        boolean autoRenews = renew != null && renew.willCharge();

        StringBuilder sb = new StringBuilder(emoji.e("rocket")).append(" <b>Мои подписки</b>\n\n");
        for (Subscription s : subs) {
            sb.append(SubscriptionFormat.line(s, SubscriptionFormat.Mode.USER, tierName(s)));
            if (autoRenews && s.isCurrent()) {
                sb.append("   🔁 продлевается автоматически");
                if (renew.nextChargeAt() != null) {
                    sb.append(" · списание ")
                            .append(com.orchestra.subscriptions.util.TimeFormat.mskDate(renew.nextChargeAt()));
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        // Ссылку печатаем только у ЖИВОЙ подписки: у истёкшей она есть, но доступа не даёт,
        // и человек будет вставлять её в приложение, пока не поймёт, что дело в сроке.
        Subscription current = subs.stream()
                .filter(s -> SubscriptionFormat.live(s, LocalDateTime.now()))
                .findFirst().orElse(null);
        if (current != null && current.getSubscriptionLink() != null) {
            sb.append(SubscriptionLinks.subscriptionLinkHtml(
                    current.getSubscriptionLink(), appConfig.getTelegram().getPublicBaseUrl(), emoji));
            sb.append("\n\n");
        }
        // Ничего не работает, а оплаченные дни лежат на замороженной — это ровно тот случай,
        // когда человек платит второй раз за то, что у него уже есть. Ставим подсказку выше
        // общего пояснения: она про деньги, а не про устройство сервиса.
        Subscription rescue = current == null ? SubscriptionFormat.richestParked(subs) : null;
        if (rescue != null) {
            sb.append(emoji.e("warn", "⚠️")).append(" Действующей подписки нет, но на ")
                    .append("подписке #").append(rescue.getSeq()).append(" сохранено ")
                    .append(SubscriptionTime.displayDays(rescue.getBalanceSeconds()))
                    .append(" дн. Включите её — оплата не требуется.\n\n");
        }
        if (subs.size() > 1) {
            sb.append("Одновременно действует одна подписка. На остальных срок заморожен "
                    + "и не расходуется.");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Subscription s : subs) {
            if (s.isCurrent()) {
                continue;
            }
            InlineKeyboardButton btn = new IkButton();
            btn.setText("▶️ Включить #" + s.getSeq());
            btn.setCallbackData("sub:switch:" + s.getId());
            rows.add(new InlineKeyboardRow(btn));
        }
        InlineKeyboardButton back = new IkButton();
        back.setText("⬅️ В кабинет");
        back.setCallbackData("cabinet");
        rows.add(new InlineKeyboardRow(back));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    // ========================
    //  Подтверждение
    // ========================

    /** Экран подтверждения: все последствия переключения до того, как человек нажал. */
    public void sendSwitchConfirm(Long chatId, Long telegramId, String rawId) throws TelegramApiException {
        Subscription target = ownedSubscription(telegramId, rawId);
        if (target == null) {
            sendSubscriptionList(chatId, telegramId);
            return;
        }
        if (target.isCurrent()) {
            send(chatId, "ℹ️ Подписка #" + target.getSeq() + " уже действует.", null);
            sendSubscriptionList(chatId, telegramId);
            return;
        }

        long balance = target.getBalanceSeconds() == null ? 0L : target.getBalanceSeconds();
        if (balance <= 0) {
            sendEmptyTargetRefusal(chatId, target);
            return;
        }

        Subscription current = subscriptionRepository
                .findByTelegramIdAndCurrentTrue(telegramId).orElse(null);
        long targetDays = SubscriptionTime.displayDays(balance);

        StringBuilder sb = new StringBuilder("🔀 <b>Включить подписку #")
                .append(target.getSeq()).append("?</b>\n\n");
        sb.append("• Начнёт расходоваться срок подписки #").append(target.getSeq())
                .append(": ").append(targetDays).append(" дн., до ")
                .append(com.orchestra.subscriptions.util.TimeFormat.mskDate(
                        SubscriptionTime.expiryFrom(LocalDateTime.now(), balance)))
                .append('\n');
        if (current != null) {
            long currentLeft = SubscriptionTime.displayDays(
                    SubscriptionTime.remainingSeconds(current.getExpiresAt(), LocalDateTime.now()));
            sb.append("• Подписка #").append(current.getSeq())
                    .append(" будет заморожена: остаток ").append(currentLeft)
                    .append(" дн. сохраняется\n");
        }
        sb.append("• <b>Ссылка сменится</b> — в приложении её нужно заменить\n");

        // Автопродление привязано к человеку и всегда продлевает РАБОТАЮЩУЮ подписку. После
        // переключения списание уедет на другую подписку, и человек, не предупреждённый об
        // этом, ждёт продления той, которую только что заморозил.
        AutoRenewInfoService.Info renew = userRepository.findByTelegramId(telegramId)
                .map(autoRenewInfo::forUser).orElse(null);
        if (renew != null && renew.willCharge()) {
            // Про смену ступени здесь больше сказать нечего: с 14.08.2026 списание берёт
            // ступень самой продлеваемой подписки, поэтому переключение меняет ТОЛЬКО то,
            // какую подписку продлят, а её число устройств остаётся своим.
            sb.append("• Автопродление перейдёт на подписку #").append(target.getSeq())
                    .append(" — продлеваться будет она\n");
        }

        List<UserDevice> demoted = deviceTrackingService.previewDemotion(target);
        if (!demoted.isEmpty()) {
            sb.append("• Будут отключены устройства: ").append(deviceNames(demoted)).append('\n');
        }
        sb.append("\nВо время переключения доступ может не работать до минуты.");

        InlineKeyboardButton go = new IkButton();
        go.setText("✅ Включить #" + target.getSeq());
        go.setCallbackData("sub:go:" + target.getId());
        InlineKeyboardButton back = new IkButton();
        back.setText("⬅️ Назад");
        back.setCallbackData("sub:list");
        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(go), new InlineKeyboardRow(back)));
        send(chatId, sb.toString(), markup);
    }

    /**
     * Ветка-отказ: на подписке не осталось оплаченных дней.
     * <p>
     * Кнопки «всё равно включить» здесь нет намеренно. Включение пустой подписки означает
     * «остаться без доступа прямо сейчас», и предлагать это одним нажатием рядом с «Назад» —
     * значит гарантированно поймать тех, кто жмёт не глядя.
     */
    private void sendEmptyTargetRefusal(Long chatId, Subscription target) throws TelegramApiException {
        String text = "⚠️ <b>На подписке #" + target.getSeq() + " не осталось оплаченных дней</b>\n\n"
                + "Её включение отключит доступ: действующая подписка будет заморожена, "
                + "а срока на этой нет.\n\n"
                + "Пополните подписку — после этого её можно включить.";
        InlineKeyboardButton back = new IkButton();
        back.setText("⬅️ К подпискам");
        back.setCallbackData("sub:list");
        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(back)));
        send(chatId, text, markup);
    }

    // ========================
    //  Переключение
    // ========================

    /** Выполняет переключение после подтверждения. */
    public void handleSwitch(Long chatId, Long telegramId, String rawId) throws TelegramApiException {
        Subscription target = ownedSubscription(telegramId, rawId);
        if (target == null) {
            sendSubscriptionList(chatId, telegramId);
            return;
        }

        SubscriptionSwitchService.Result result;
        try {
            // Ловим СНАРУЖИ транзакции переключения: нарушение уникального индекса «одна
            // текущая подписка» отравляет транзакцию Postgres, и обработать его внутри нельзя.
            // switchByUser — а не switchTo: аварийный тормоз и суточный лимит живут в сервисе,
            // иначе второй канал (Mini App) обходил бы их самим фактом своего существования.
            result = switchService.switchByUser(telegramId, target.getId());
        } catch (Exception e) {
            log.error("Переключение не удалось: telegramId={} subId={}", telegramId, target.getId(), e);
            send(chatId, "❌ Переключение не выполнено. Состояние подписок не изменилось. "
                    + "Повторите попытку или обратитесь в поддержку.", null);
            return;
        }

        switch (result.outcome()) {
            case NOT_FOUND, ALREADY_CURRENT -> {
                // Не ошибка: двойное нажатие или устаревшая кнопка из истории чата.
                sendSubscriptionList(chatId, telegramId);
                return;
            }
            case EMPTY -> {
                // Пока кнопка висела в истории чата, дни на подписке кончились.
                sendEmptyTargetRefusal(chatId, target);
                return;
            }
            case PAUSED -> {
                // Аварийный тормоз панели. Молча прятать кнопку нельзя — у человека там
                // замороженные деньги, и исчезнувшая кнопка читается как «деньги пропали».
                send(chatId, "⏸ Переключение подписок временно недоступно. "
                        + "Сроки подписок не изменяются. Повторите попытку позже.", null);
                return;
            }
            case RATE_LIMITED -> {
                send(chatId, "⏳ Исчерпан суточный лимит переключений. "
                        + "Повторите попытку завтра. Сроки подписок не изменяются.", null);
                return;
            }
            case SWITCHED -> { /* дальше — ответ человеку и досылка в панель */ }
        }

        Subscription active = subscriptionRepository.findById(result.activatedSubscriptionId())
                .orElse(target);
        StringBuilder sb = new StringBuilder("✅ <b>Подписка #")
                .append(active.getSeq()).append(" включена</b>\n\n")
                .append("Остаток: ")
                .append(SubscriptionTime.displayDays(result.activatedRemainingSeconds()))
                .append(" дн.\n\n");
        if (active.getSubscriptionLink() != null) {
            sb.append(SubscriptionLinks.subscriptionLinkHtml(
                    active.getSubscriptionLink(), appConfig.getTelegram().getPublicBaseUrl(), emoji));
            sb.append("\n\n");
        }
        sb.append("⚠️ Ссылка изменилась. Замените её в приложении — иначе доступ продолжит "
                + "работать по прежней подписке.");

        InlineKeyboardButton how = new IkButton();
        how.setText("❔ Как вставить ссылку");
        how.setCallbackData("how_to");
        InlineKeyboardButton list = new IkButton();
        list.setText("📋 Мои подписки");
        list.setCallbackData("sub:list");
        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(how), new InlineKeyboardRow(list)));
        // Отвечаем ДО обращения к панели. Переключение уже зафиксировано в базе, и человек
        // обязан узнать об этом и получить новую ссылку независимо от того, доступна ли
        // панель: иначе сбой доставки в панель оставил бы его вообще без ответа на действие,
        // которое на самом деле произошло.
        send(chatId, sb.toString(), markup);

        try {
            // Панель обновляем ПОСЛЕ фиксации в базе: недоступная панель не должна отменять
            // уже принятое решение, её догонит реконсилер.
            switchService.pushToPanelAfterCommit(result);
        } catch (Exception e) {
            log.warn("Не смог сразу досдать переключение в панель (telegramId={}) — доведёт реконсилер",
                    telegramId, e);
        }
    }

    // ========================
    //  Внутренности
    // ========================

    /** Подписка по id из кнопки — только своя. Чужой/битый id молча уводит к списку. */
    private Subscription ownedSubscription(Long telegramId, String rawId) {
        try {
            return subscriptionRepository
                    .findByIdAndTelegramId(Long.parseLong(rawId.trim()), telegramId)
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String deviceNames(List<UserDevice> devices) {
        List<String> names = devices.stream()
                .limit(MAX_DEVICES_NAMED)
                .map(DeviceTrackingService::displayName)
                .map(n -> org.springframework.web.util.HtmlUtils.htmlEscape(n, "UTF-8"))
                .toList();
        String joined = String.join(", ", names);
        int rest = devices.size() - names.size();
        return rest > 0 ? joined + " и ещё " + rest : joined;
    }

    /** Человекочитаемое имя ступени; ступень удалили из конфига — покажем её id. */
    private String tierName(Subscription s) {
        if (s.getDeviceTierId() == null) {
            return null;
        }
        return appConfig.findDeviceTierOptional(s.getDeviceTierId())
                .map(AppConfig.DeviceTier::getName)
                .orElse(s.getDeviceTierId());
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup markup) throws TelegramApiException {
        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("HTML");
        if (markup != null) {
            msg.setReplyMarkup(markup);
        }
        telegramSender.execute(msg);
    }
}
