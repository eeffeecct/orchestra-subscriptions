package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.PromoCodeService;
import com.orchestra.subscriptions.service.ReferralService;
import com.orchestra.subscriptions.service.ServerStatusService;
import com.orchestra.subscriptions.service.AccessService;
import com.orchestra.subscriptions.util.DeviceNames;
import com.orchestra.subscriptions.util.Emoji;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkButtons;
import com.orchestra.subscriptions.util.IkMarkup;
import com.orchestra.subscriptions.util.IkSendMessage;
import com.orchestra.subscriptions.util.Money;
import com.orchestra.subscriptions.util.StarsAmount;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import com.orchestra.subscriptions.util.TimeFormat;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Пользовательские экраны бота: подписка/статус, кабинет, покупка (тарифы + способ оплаты),
 * история платежей, чеки, поддержка, помощь, оферта, рефералка. Вынесены из god-class SubscriptionBot
 * (PR-4). SubscriptionBot роутит сюда команды, кнопки нижнего меню и callback'и. Чистый рендер — данные
 * из AccessService/PaymentRepository/ReferralService, отправка через TelegramSender
 * (мокается в тестах). Поведение не менялось — move-not-rewrite.
 * <p>
 * Анимированные эмодзи — свой {@link Emoji} (намеренно не Spring-бин, как в SubscriptionBot), кнопки с
 * иконками — общий {@link IkButtons#icon}. Eager (без {@code @Lazy}): ни одна зависимость не
 * образует цикл с SubscriptionBot — здесь нет NotificationService/PaymentProcessingService/BroadcastService.
 */
@lombok.extern.slf4j.Slf4j
@Component
public class ScreenHandler {

    /**
     * Страницы рекомендованного клиентского приложения по платформам. Константы, а не текст
     * внутри экрана: при смене рекомендованного клиента правится одно место, и промахнуться
     * мимо одной из трёх кнопок нельзя.
     */
    private static final String CLIENT_ANDROID_URL = "https://example.com/download/android";
    private static final String CLIENT_IOS_URL = "https://example.com/download/ios";
    private static final String CLIENT_DESKTOP_URL = "https://example.com/download/desktop";

    private final AppConfig appConfig;
    private final AccessService accessService;
    private final PaymentRepository paymentRepository;
    /** Сколько у человека подписок — от этого зависит, показывать ли пункт «Мои подписки». */
    private final com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository;
    /** Факты работающей подписки: слоты и список устройств считаются в её пределах. */
    private final com.orchestra.subscriptions.service.SubscriptionLookupService subscriptionLookup;
    /** Куда пойдут деньги покупки — спрашиваем тот же резолвер, что проставит цель платежу. */
    private final com.orchestra.subscriptions.service.payment.PaymentTargetResolver paymentTargetResolver;
    private final ReferralService referralService;
    private final DeviceTrackingService deviceTrackingService;
    /** Что и когда спишется — тот же снимок, что показывают /autorenew и Mini App. */
    private final com.orchestra.subscriptions.service.AutoRenewInfoService autoRenewInfo;
    private final PendingInputService pendingInputService;
    private final PromoCodeService promoCodeService;
    private final ServerStatusService serverStatusService;
    private final TelegramSender telegramSender;
    private final Emoji emoji;

    public ScreenHandler(AppConfig appConfig,
                         AccessService accessService,
                         PaymentRepository paymentRepository,
                         com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository,
                         com.orchestra.subscriptions.service.SubscriptionLookupService subscriptionLookup,
                         // @Lazy рвёт цикл NotificationService → SubscriptionBot → ScreenHandler →
                         // PaymentTargetResolver → NotificationService. Резолвер нужен только
                         // при отрисовке экрана покупки, то есть уже после старта контекста.
                         @org.springframework.context.annotation.Lazy
                         com.orchestra.subscriptions.service.payment.PaymentTargetResolver paymentTargetResolver,
                         ReferralService referralService,
                         DeviceTrackingService deviceTrackingService,
                         com.orchestra.subscriptions.service.AutoRenewInfoService autoRenewInfo,
                         PendingInputService pendingInputService,
                         PromoCodeService promoCodeService,
                         ServerStatusService serverStatusService,
                         TelegramSender telegramSender) {
        this.appConfig = appConfig;
        this.accessService = accessService;
        this.paymentRepository = paymentRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionLookup = subscriptionLookup;
        this.paymentTargetResolver = paymentTargetResolver;
        this.referralService = referralService;
        this.deviceTrackingService = deviceTrackingService;
        this.autoRenewInfo = autoRenewInfo;
        this.pendingInputService = pendingInputService;
        this.promoCodeService = promoCodeService;
        this.serverStatusService = serverStatusService;
        this.telegramSender = telegramSender;
        this.emoji = new Emoji(appConfig.getEmoji());
    }

    public void sendReferralInfo(Long chatId, Long telegramId) throws TelegramApiException {
        ReferralService.ReferralStats stats = referralService.getStats(telegramId);
        String botUsername = appConfig.getTelegram().getBotUsername();
        String inviteLink = "https://t.me/" + botUsername + "?start=ref_" + telegramId;

        String text = emoji.e("people") + " <b>Приглашайте друзей — получайте дни бесплатно</b>\n\n"
                + "За каждого приглашённого, оплатившего подписку, Вам начисляется "
                + "<b>+" + ReferralService.REFERRER_BONUS_DAYS + " дней</b>, "
                + "а приглашённому — <b>+" + ReferralService.REFEREE_BONUS_DAYS + " дней</b>.\n\n"
                + "🔗 <b>Ваша ссылка:</b>\n"
                + "<code>" + inviteLink + "</code>\n\n"
                + "📊 <b>Статистика:</b>\n"
                + "• Приглашено: " + stats.invited() + "\n"
                + "• Оплатили: " + stats.rewarded() + "\n"
                + "• Всего получено: " + stats.totalBonusDays() + " дн.\n"
                + "• Осталось наград в этом месяце: " + stats.capRemaining()
                + "/" + ReferralService.MONTHLY_REWARDED_CAP;

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        telegramSender.execute(msg);
    }

    /**
     * Значки тарифов по позиции в списке (1/3/6/12 мес → ⚡️/⭐️/🚀/👑). Ключи — для
     * анимированных эмодзи в тексте (blockquote), fallback-массив — для inline-кнопок,
     * где кастомные эмодзи Telegram не поддерживает. Планов больше четырёх — берём последний.
     */
    private static final String[] TIER_KEYS = {"bolt", "star", "rocket", "crown"};
    private static final String[] TIER_EMOJI = {"⚡️", "⭐️", "🚀", "👑"};

    /**
     * Вход в покупку. Открывается с welcome / нижнего меню «🚀 Подписка». При включённых
     * ступенях устройств ({@code app.device-tiers}) сначала спрашиваем «сколько устройств»
     * ({@link #sendDeviceTierMenu}), потом срок ({@link #sendBuyMenuForTier}). Без ступеней —
     * legacy-экран тарифов, как раньше.
     */
    public void sendBuyMenu(Long chatId, Long telegramId) throws TelegramApiException {
        if (appConfig.deviceTiersEnabled()) {
            sendDeviceTierMenu(chatId);
            return;
        }
        sendBuyMenuForTier(chatId, telegramId, null);
    }

    /**
     * Шаг 1: сколько устройств. Кнопка на каждую ступень с ценой «от X ₽/мес»
     * (минимальный помесячный по самому длинному плану). Семейные ступени — отдельным блоком.
     */
    private void sendDeviceTierMenu(Long chatId) throws TelegramApiException {
        StringBuilder sb = new StringBuilder();
        sb.append(emoji.e("bag")).append(" <b>Подписки</b>\n\n")
                .append("Выберите, на скольких устройствах будет работать доступ.\n")
                .append("<i>Устройство — телефон, ноутбук, планшет, ТВ. ")
                .append("Семейная — одна ссылка на всех.\n")
                // «от 101 ₽/мес» без пояснения путает (владелец сам споткнулся): это
                // пересчёт годовой цены на месяц, а не цена месячного тарифа.
                .append("Цена «от» — в пересчёте на месяц при оплате за год, ")
                .append("точные цены — на следующем шаге.</i>\n");

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (AppConfig.DeviceTier tier : appConfig.getDeviceTiers()) {
            String key = tier.isFamily() ? "family" : "devices";
            String fallback = tier.isFamily() ? "👨‍👩‍👧" : "📱";
            InlineKeyboardButton btn = IkButtons.icon(emoji, key, fallback,
                    tier.getName() + " · от " + minPerMonthRub(tier) + " ₽/мес"
                            + (tier.isPopular() ? " ★" : ""));
            btn.setCallbackData("tier:" + tier.getId());
            rows.add(new InlineKeyboardRow(btn));
        }

        InlineKeyboardButton backBtn = new IkButton();
        backBtn.setText("⬅️ Назад");
        backBtn.setCallbackData("menu:start");
        rows.add(new InlineKeyboardRow(backBtn));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /** Минимальный помесячный тариф ступени (по самому длинному плану), в целых ₽.
     *  Math.round — как perMonth в Mini App (script.js), чтобы цифры не расходились на 1 ₽. */
    private long minPerMonthRub(AppConfig.DeviceTier tier) {
        return appConfig.getPlans().stream()
                .mapToLong(p -> Math.round(
                        appConfig.tierPriceKopeks(p, tier) * 30.0 / Math.max(30, p.getDays()) / 100.0))
                .min()
                .orElse(0);
    }

    /**
     * Шаг 2: срок. Тарифы — карточками-блокквотами (срок · устройства · цена · скидка за день).
     * Скидки считаются динамически от 30-дневного как baseline ({@link #formatDiscount}).
     * {@code tierId == null} — legacy-режим без ступеней (текст «до N устройств» из
     * глобального ip-limit, callback'и без ступени — старое поведение 1-в-1).
     */
    public void sendBuyMenuForTier(Long chatId, Long telegramId, String tierId) throws TelegramApiException {
        AppConfig.DeviceTier deviceTier = appConfig.findDeviceTierOptional(tierId).orElse(null);
        if (tierId != null && deviceTier == null) {
            // Кнопка из старого чата со ступенью, удалённой из конфига — начинаем заново.
            sendBuyMenu(chatId, telegramId);
            return;
        }

        String devicesLine;
        if (deviceTier != null) {
            devicesLine = deviceTier.getName();
        } else {
            int ipLimit = appConfig.getProvider() != null ? appConfig.getProvider().getDeviceLimit() : 5;
            devicesLine = ipLimit > 0 ? "До " + ipLimit + " устройств" : "Без лимита устройств";
        }
        String callbackSuffix = deviceTier != null ? ":" + deviceTier.getId() : "";

        StringBuilder sb = new StringBuilder();
        sb.append(emoji.e("bag")).append(" <b>Подписки</b>\n\n")
                .append("Чем длиннее срок, тем ниже цена дня. ")
                .append(devicesLine).append(deviceTier != null ? "." : " на любом тарифе.").append(" ")
                .append(targetSummaryLine(telegramId,
                        deviceTier != null ? deviceTier.getId() : null));

        // Даунгрейд-предупреждение: у юзера сейчас активных устройств больше, чем даёт
        // выбранная ступень — после оплаты лишние перейдут в «сверх лимита». Особенно
        // важно для legacy-юзеров (до фичи лимита не было вовсе).
        if (deviceTier != null && telegramId != null) {
            long used = deviceTrackingService.usedSlots(telegramId);
            if (used > deviceTier.getDevices()) {
                sb.append("\n⚠️ <b>Сейчас активных устройств: ").append(used).append("</b>. ")
                        .append("После оплаты этой ступени останется ").append(deviceTier.getDevices())
                        .append(", остальные будут отключены. Выберите ступень выше или ")
                        .append("отключите лишние устройства в разделе «Мои устройства».\n");
            }
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<AppConfig.Plan> plans = appConfig.getPlans();
        for (int i = 0; i < plans.size(); i++) {
            AppConfig.Plan plan = plans.get(i);
            int tier = Math.min(i, TIER_EMOJI.length - 1);
            long rub = appConfig.tierPriceKopeks(plan, deviceTier) / 100;

            sb.append("\n<blockquote>")
                    .append(emoji.e(TIER_KEYS[tier], TIER_EMOJI[tier])).append(" <b>").append(plan.getName()).append("</b>\n")
                    .append(emoji.e("link")).append(" ").append(devicesLine).append("\n")
                    .append(emoji.e("money")).append(" ").append(rub).append(" ₽ / ")
                    .append(plan.getDays()).append(" дней").append(formatDiscount(plan, deviceTier))
                    .append("</blockquote>");

            InlineKeyboardButton btn = new IkButton();
            Long iconId = emoji.id(TIER_KEYS[tier]);
            if (iconId != null) {
                // Анимированная иконка тарифа на кнопке (Bot API 9.4) — ведущий эмодзи из текста убираем.
                btn.setText(plan.getName() + " · " + rub + " ₽");
                btn.setIconCustomEmojiId(String.valueOf(iconId));
            } else {
                btn.setText(TIER_EMOJI[tier] + " " + plan.getName() + " · " + rub + " ₽");
            }
            btn.setCallbackData("plan:" + plan.getId() + callbackSuffix);
            rows.add(new InlineKeyboardRow(btn));
        }

        InlineKeyboardButton backBtn = new IkButton();
        backBtn.setText("⬅️ Назад");
        backBtn.setCallbackData(deviceTier != null ? "menu:buy" : "menu:start");
        rows.add(new InlineKeyboardRow(backBtn));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /**
     * Подписывает скидку относительно 30-дневного тарифа (например " (−22%)") — по ценам
     * ВЫБРАННОЙ ступени устройств: округление ступеней слегка меняет проценты, и без этого
     * бейдж расходился с Mini App (−22 vs −21 на семейных).
     * Возвращает пустую строку, если baseline-плана нет, тариф сам baseline или скидка ≤0.
     */
    private String formatDiscount(AppConfig.Plan plan, AppConfig.DeviceTier deviceTier) {
        AppConfig.Plan baseline = appConfig.getPlans().stream()
                .filter(p -> p.getDays() == 30)
                .findFirst()
                .orElse(null);
        if (baseline == null || plan.getDays() <= baseline.getDays()) return "";
        double baselinePerDay = (double) appConfig.tierPriceKopeks(baseline, deviceTier) / baseline.getDays();
        double planPerDay = (double) appConfig.tierPriceKopeks(plan, deviceTier) / plan.getDays();
        int pct = (int) Math.round(100 * (1 - planPerDay / baselinePerDay));
        return pct > 0 ? "  (−" + pct + "%)" : "";
    }

    // ========================
    // Пробный период
    // ========================

    public void handleTrial(Long chatId, User user) throws TelegramApiException {
        Customer customer;
        try {
            customer = accessService.createTrialAccess(user.getId(), user.getUserName());
        } catch (IllegalStateException e) {
            SendMessage msg = new SendMessage(chatId.toString(),
                    "Пробный период уже был использован.\n\nОформление подписки — кнопка «💳 Купить подписку».");
            telegramSender.execute(msg);
            return;
        }

        String text = "🎁 <b>Пробный период активирован</b>\n\n"
                + "Доступ открыт на 7 дней. Ссылка подключения — ниже, скопируйте и добавьте её в приложение:\n\n"
                + SubscriptionLinks.subscriptionLinkHtml(
                    customer.getSubscriptionLink(), appConfig.getTelegram().getPublicBaseUrl(), emoji)
                + "\n\n"
                + "Инструкция по подключению: /help\n"
                + "Оформление подписки: /start";

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    // ========================
    // Выбор способа оплаты
    // ========================

    /**
     * @param payload {@code <planId>} (legacy-кнопки без ступени) или {@code <planId>:<tierId>}
     *                (после выбора числа устройств)
     */
    public void sendPaymentMethodPicker(Long chatId, Long telegramId, String payload)
            throws TelegramApiException {
        String planId = payload;
        String tierId = null;
        int sep = payload.indexOf(':');
        if (sep >= 0) {
            planId = payload.substring(0, sep);
            tierId = payload.substring(sep + 1);
        }

        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(planId);
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Неизвестный тариф."));
            return;
        }
        AppConfig.DeviceTier deviceTier = appConfig.findDeviceTierOptional(tierId).orElse(null);
        long baseKopeks = appConfig.tierPriceKopeks(plan, deviceTier);
        String callbackSuffix = deviceTier != null ? ":" + deviceTier.getId() : "";

        // Применённый промокод (reply-флоу «🎟 Есть промокод?»): цены в кнопках сразу со
        // скидкой — юзер видит, сколько спишется. Протухший/невалидный код тихо снимаем.
        long chargeKopeks = baseKopeks;
        String promoLine = "";
        String appliedCode = telegramId != null ? pendingInputService.getAppliedPromo(telegramId) : null;
        if (appliedCode != null) {
            PromoCodeService.PromoResult promo = promoCodeService.preview(appliedCode, baseKopeks, telegramId);
            if (promo.valid()) {
                chargeKopeks = promo.finalAmountKopeks();
                promoLine = "\n" + emoji.e("ticket") + " Промокод <b>" + promo.code() + "</b>: −"
                        + Money.rub(promo.discountKopeks()) + " ₽";
            } else {
                pendingInputService.clearPromo(telegramId);
                appliedCode = null;
            }
        }
        // Money.rub, а не kopeks/100: процентный промокод даёт дробную сумму (−15% от 179 ₽
        // = 152.15 ₽), и кнопка обещала «152 ₽», хотя YooKassa списывала 152.15 ₽.
        String priceRub = Money.rub(chargeKopeks);

        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardButton cardBtn = new IkButton();
        Long cardIcon = emoji.id("card");
        cardBtn.setText((cardIcon != null ? "" : "💳 ") + "Картой — " + priceRub + " ₽");
        if (cardIcon != null) {
            cardBtn.setIconCustomEmojiId(String.valueOf(cardIcon));
        }
        cardBtn.setCallbackData("pay:yookassa:" + planId + callbackSuffix);
        rows.add(new InlineKeyboardRow(cardBtn));

        InlineKeyboardButton cryptoBtn = IkButtons.icon(emoji, "crypto", "💸", "Криптой — " + priceRub + " ₽");
        cryptoBtn.setCallbackData("pay:crypto:" + planId + callbackSuffix);
        rows.add(new InlineKeyboardRow(cryptoBtn));

        // Stars — только если цена в конфиге задана (>0), иначе этот тариф не поддерживается Stars.
        // Расчёт СТРОГО под гвардом: StarsAmount.compute бросает на priceStars <= 0, а такой
        // план — легальная конфигурация (кнопка Stars просто скрывается). Charge со скидкой —
        // тем же helper'ом пересчитает pre_checkout по payment.amountKopeks.
        if (plan.getPriceStars() > 0) {
            long priceStars = StarsAmount.compute(
                    plan.getPriceStars(), plan.getPriceKopeks(), chargeKopeks);
            InlineKeyboardButton starsBtn = new IkButton();
            Long starIcon = emoji.id("star");
            starsBtn.setText((starIcon != null ? "" : "⭐ ") + "Telegram Stars — " + priceStars + " ⭐");
            if (starIcon != null) {
                starsBtn.setIconCustomEmojiId(String.valueOf(starIcon));
            }
            starsBtn.setCallbackData("pay:stars:" + planId + callbackSuffix);
            rows.add(new InlineKeyboardRow(starsBtn));
        }

        // Промокод: ввод через reply-флоу; применённый показываем и даём убрать.
        InlineKeyboardButton promoBtn;
        if (appliedCode != null) {
            promoBtn = new IkButton();
            promoBtn.setText("✖️ Убрать промокод");
            promoBtn.setCallbackData("promo:clear:" + payload);
        } else {
            promoBtn = IkButtons.icon(emoji, "ticket", "🎟", "Есть промокод?");
            promoBtn.setCallbackData("promo:ask:" + payload);
        }
        rows.add(new InlineKeyboardRow(promoBtn));

        InlineKeyboardButton backBtn = new IkButton();
        backBtn.setText("◀ Назад");
        backBtn.setCallbackData(deviceTier != null ? "tier:" + deviceTier.getId() : "menu:buy");
        rows.add(new InlineKeyboardRow(backBtn));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(emoji.e("card") + " <b>Оплата</b>\n\n"
                + "Тариф: <b>" + plan.getName()
                + (deviceTier != null ? " · " + deviceTier.getName() : "") + "</b>"
                + promoLine + "\n"
                + paymentTargetNotice(telegramId,
                        deviceTier != null ? deviceTier.getId() : null)
                + "Выберите способ оплаты:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    // sendHelpMenu (старый хаб «Помощь и поддержка») удалён: его роль закрывают постоянное
    // нижнее меню + экран «Поддержка» (sendSupport). /help и callback menu:help ведут туда же.

    public void sendStatus(Long chatId, Long telegramId) throws TelegramApiException {
        Optional<Customer> userOpt = accessService.getUser(telegramId);
        if (userOpt.isEmpty()) {
            SendMessage msg = new SendMessage(chatId.toString(),
                    emoji.e("rocket") + " <b>Мой доступ</b>\n\n"
                    + "Подписка пока не оформлена.\n\n"
                    + "Выбрать тариф — кнопка «" + emoji.e("rocket") + " Подписка» внизу или /start. "
                    + emoji.e("gift") + " Первые 7 дней бесплатно.");
            msg.setParseMode("HTML");
            telegramSender.execute(msg);
            return;
        }

        Customer u = userOpt.get();
        boolean isActive = u.getStatus() == Customer.Status.ACTIVE
                && u.getExpiresAt() != null && u.getExpiresAt().isAfter(LocalDateTime.now());
        String devices = devicesStatusLine(u);

        // Экран DB-only: ни одного AccessProvider-вызова на единственном потоке апдейтов бота
        // (трафик / живой список IP — Фаза 2, off-thread). Тариф выводим из последней
        // успешной оплаты, остаток — из expires_at.
        // Подписок может быть несколько, и экран показывает РАБОТАЮЩУЮ. Без её номера человек
        // не понимает, к какой из своих подписок относятся срок, устройства и ссылка на этом
        // экране, — а ссылку он отсюда и копирует.
        List<com.orchestra.subscriptions.model.Subscription> subs =
                subscriptionRepository.findByTelegramIdOrderBySeqAsc(telegramId);
        com.orchestra.subscriptions.model.Subscription currentSub = subs.stream()
                .filter(com.orchestra.subscriptions.model.Subscription::isCurrent)
                .findFirst().orElse(null);
        boolean many = subs.size() > 1;

        StringBuilder sb = new StringBuilder();
        sb.append(emoji.e("rocket")).append(" Тариф <b>").append(currentPlanLabel(telegramId))
                .append(currentTierSuffix(telegramId, u)).append("</b>\n\n");
        if (many && currentSub != null) {
            int frozen = subs.size() - 1;
            sb.append(emoji.e("scroll", "📋")).append(" Подписка <b>#").append(currentSub.getSeq())
                    .append("</b> из ").append(subs.size())
                    // «остальные заморожены» при одной остальной читается как про многих.
                    .append(frozen == 1 ? " · ещё 1 заморожена" : " · ещё " + frozen + " заморожены")
                    .append("\n");
        }
        sb.append(emoji.e("link")).append(" Устройства: ").append(devices).append("\n")
                .append(emoji.e("hourglass")).append(" Осталось: <b>").append(formatTimeLeft(u.getExpiresAt())).append("</b>\n")
                // «Действует до» поверх неактивной подписки читается как «действует»: дата в
                // прошлом этому не мешает, её сопоставляют с сегодняшней не все и не сразу.
                .append(emoji.e("calendar")).append(isActive ? " Действует до: " : " Истекла: ")
                .append(TimeFormat.mskDate(u.getExpiresAt())).append("\n")
                .append(emoji.e(isActive ? "check" : "cross")).append(" Статус: ").append(isActive ? "активна" : "неактивна").append("\n");
        // Автопродление — факт про деньги, и место ему рядом со сроком, а не в отдельном
        // разделе, куда заходят единицы. Строка появляется, только когда списание реально
        // произойдёт: «включено» при отвязанной карте или выключенных рекуррентах — обещание,
        // которое сервис не выполнит.
        com.orchestra.subscriptions.service.AutoRenewInfoService.Info renew = autoRenewInfo.forUser(u);
        if (renew.willCharge()) {
            sb.append(emoji.e("recycle", "🔁")).append(" Автопродление: ");
            if (renew.amountRub() > 0) {
                sb.append("<b>").append(renew.amountRub()).append(" ₽</b>");
            } else {
                sb.append("включено");
            }
            if (renew.nextChargeAt() != null) {
                sb.append(" спишется ").append(TimeFormat.mskDate(renew.nextChargeAt()));
            }
            sb.append("\n");
        }
        sb.append("\n");

        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (isActive) {
            sb.append(SubscriptionLinks.subscriptionLinkHtml(
                            u.getSubscriptionLink(), appConfig.getTelegram().getPublicBaseUrl(), emoji))
                    .append("\n\n")
                    .append(emoji.e("down")).append(" Скопируйте ссылку выше и вставьте в приложение. ")
                    .append("Порядок действий — в разделе «Как установить».");

            InlineKeyboardButton openApp = IkButtons.icon(emoji, "shield", "🪐", "Открыть Orchestra");
            openApp.setWebApp(new org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo(
                    appConfig.getTelegram().getMiniAppBaseUrl() + "/app/"));
            openApp.setStyle("success"); // единственная зелёная кнопка — главный CTA
            rows.add(new InlineKeyboardRow(openApp));
        }

        // Оплаченные дни лежат на замороженной подписке, а доступа нет — самый дорогой для
        // человека случай: он идёт платить второй раз за то, что уже оплачено. Бесплатный
        // выход обязан стоять ВЫШЕ кнопки «Продлить», иначе его не увидят.
        com.orchestra.subscriptions.model.Subscription rescue = isActive
                ? null : com.orchestra.subscriptions.util.SubscriptionFormat.richestParked(subs);
        // Совет платить и подсказку «оплата не требуется» рядом ставить нельзя — они
        // противоречат друг другу, и человек выберет тот, что видит первым. Когда есть что
        // включить, строку про продление опускаем целиком: «неактивна» уже сказано статусом.
        if (!isActive && rescue == null) {
            sb.append("Подписка неактивна. Для возобновления доступа продлите её.");
        }
        if (rescue != null) {
            // Ровно одна пустая строка перед блоком, чем бы ни закончился предыдущий:
            // ветка «неактивна» может не написать ничего, и тогда получалась дыра в три строки.
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("\n\n").append(emoji.e("warn", "⚠️")).append(" На подписке #")
                    .append(rescue.getSeq()).append(" сохранено ")
                    .append(com.orchestra.subscriptions.service.SubscriptionTime.displayDays(
                            rescue.getBalanceSeconds()))
                    .append(" дн. Включите её — оплата не требуется.");
            InlineKeyboardButton switchBtn = new IkButton();
            switchBtn.setText("▶️ Включить #" + rescue.getSeq());
            switchBtn.setCallbackData("sub:switch:" + rescue.getId());
            rows.add(new InlineKeyboardRow(switchBtn));
        }

        InlineKeyboardButton extendBtn = new IkButton();
        extendBtn.setText("➕ Продлить");
        extendBtn.setCallbackData("menu:buy");
        InlineKeyboardButton howBtn = new IkButton();
        howBtn.setText("❔ Как установить");
        howBtn.setCallbackData("how_to");
        rows.add(new InlineKeyboardRow(extendBtn, howBtn));

        // Переход к списку — с того экрана, где человек и заметил, что подписок несколько.
        // Раньше пункт жил только в «Кабинете», куда за ним никто не шёл.
        if (many) {
            InlineKeyboardButton subsBtn = new IkButton();
            subsBtn.setText("📋 Мои подписки (" + subs.size() + ")");
            subsBtn.setCallbackData("sub:list");
            rows.add(new InlineKeyboardRow(subsBtn));
        }

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /**
     * Строка «Устройства» в статусе: с персональным лимитом — «2 из 3» (занятые слоты по
     * HWID-учёту), без лимита — legacy-текст от глобального ip-limit.
     */
    private String devicesStatusLine(Customer u) {
        Integer limit = u.getDeviceLimit();
        if (limit != null && limit > 0) {
            // Слоты считаем в разрезе РАБОТАЮЩЕЙ подписки — ровно так же, как их считает
            // enforcement. Версия «по человеку» показывала бы «занято 5 из 2» тому, у кого
            // часть устройств осталась на другой, спящей подписке: цифра пугает, а лимит
            // при этом не нарушен.
            return usedSlotsOfCurrent(u) + " из " + limit;
        }
        int ipLimit = appConfig.getProvider() != null ? appConfig.getProvider().getDeviceLimit() : 5;
        return ipLimit > 0 ? "до " + ipLimit : "без лимита";
    }

    /**
     * Куда на самом деле уйдут деньги этой покупки.
     * <p>
     * Спрашиваем ровно у того резолвера, который потом проставит цель платежу, — иначе экран
     * и деньги разойдутся: человек прочитает «дни добавятся к текущей», а они лягут в другую
     * подписку. Сбой резолвера трактуем как «продление текущей»: соврать в сторону привычного
     * поведения безопаснее, чем пообещать вторую подписку и не завести её.
     */
    private com.orchestra.subscriptions.service.payment.PaymentTarget purchaseTarget(Long telegramId, String tierId) {
        // tierId ОБЯЗАН быть разрешённым по конфигу, а не сырым из кнопки: ступень могли
        // удалить, и тогда оплата пойдёт legacy-путём (без ступени, то есть продлением),
        // а экран по сырому id насчитал бы «другая ступень → вторая подписка» и соврал.
        if (telegramId == null || tierId == null) {
            return null;
        }
        try {
            return paymentTargetResolver.resolve(telegramId, tierId,
                    com.orchestra.subscriptions.service.payment.TargetSpec.current());
        } catch (Exception e) {
            log.warn("Не смог определить цель покупки для telegramId={}", telegramId, e);
            return null;
        }
    }

    /**
     * Одна строка на экране выбора тарифа: куда пойдёт срок этой покупки.
     * <p>
     * Три исхода, а не два: с 14.08.2026 покупка ступени, которая уже есть у спящей подписки,
     * пополняет ЕЁ (раньше молча понижала тариф работающей). Строка обязана называть и этот
     * случай — иначе человек не найдёт оплаченные дни там, где их ждёт.
     */
    private String targetSummaryLine(Long telegramId, String tierId) {
        com.orchestra.subscriptions.service.payment.PaymentTarget target = purchaseTarget(telegramId, tierId);
        if (target == null) {
            return "Срок добавляется к уже оплаченному.\n";
        }
        if (target.kind() == com.orchestra.subscriptions.service.payment.PaymentTarget.Kind.SEPARATE) {
            return "Срок будет зачислен на отдельную подписку, текущая сохраняется.\n";
        }
        if (target.parked()) {
            return "Срок будет зачислен в замороженную подписку #" + target.seq() + ".\n";
        }
        return "Срок добавляется к уже оплаченному.\n";
    }

    /**
     * Что произойдёт с этой оплатой — до нажатия «Оплатить».
     * <p>
     * Две ветки, обе про деньги. <b>Отдельная подписка:</b> решение владельца в чистом виде —
     * один информационный экран и одна кнопка, без выбора. Кнопки «заменить текущую» здесь нет
     * намеренно: при понижении ступени она ничего не возвращает, а просто сжигает оплаченное,
     * и часть людей нажмёт не глядя. <b>Зачисление в замороженную подписку:</b> так теперь
     * ложится покупка ступени, которая уже есть у спящей подписки (раньше она понижала тариф
     * работающей). Молчать нельзя ни в одном случае: человек, ожидавший продления, не увидит
     * добавленных дней на работающей подписке и решит, что деньги пропали.
     */
    private String paymentTargetNotice(Long telegramId, String tierId) {
        com.orchestra.subscriptions.service.payment.PaymentTarget target = purchaseTarget(telegramId, tierId);
        if (target == null) {
            return "";
        }
        if (target.kind() == com.orchestra.subscriptions.service.payment.PaymentTarget.Kind.EXTEND
                && target.parked()) {
            return parkedTargetNotice(telegramId, target);
        }
        if (target.kind() != com.orchestra.subscriptions.service.payment.PaymentTarget.Kind.SEPARATE) {
            return "";
        }
        // ⚠️ Замораживается НОВАЯ подписка, а не текущая. Первая версия этого текста
        // утверждала обратное — фраза была списана с плана этапа, а реализация (PR 5.2)
        // намеренно делает наоборот: включить новую сразу значило бы молча оборвать
        // работающий доступ. Текст экрана и текст письма после оплаты обязаны совпадать,
        // иначе человек получает два противоположных утверждения об одной покупке.
        boolean currentIsLive = subscriptionLookup.currentFor(telegramId)
                .map(sub -> sub.activeAt(LocalDateTime.now()))
                .orElse(false);
        if (currentIsLive) {
            return "\n" + emoji.e("warn", "\u26a0\ufe0f") + " Выбран тариф, отличный от действующего, "
                    + "поэтому появится <b>вторая подписка</b>. Она создаётся замороженной: доступ "
                    + "продолжит работать по текущей подписке, срок новой не расходуется до её "
                    + "включения. Включение — кнопкой в сообщении после оплаты или в разделе "
                    + "«Мои подписки».\n";
        }
        // Живой подписки нет — купленная включится сама, и ссылка сменится. Промолчать про
        // смену ссылки нельзя: человек вставит старую и решит, что оплата не сработала.
        return "\n" + emoji.e("warn", "\u26a0\ufe0f") + " Выбран тариф, отличный от действующего, "
                + "поэтому появится <b>вторая подписка</b>. Она включится сразу — "
                + "<b>ссылка сменится</b>, её нужно будет заново вставить в приложение.\n";
    }

    /**
     * Деньги идут в ЗАМОРОЖЕННУЮ подписку — на работающей дни не прибавятся.
     * <p>
     * Так теперь ложится покупка ступени, которая уже есть у спящей подписки: раньше такая
     * оплата понижала тариф работающей. Формулировка ветвится по тому, работает ли что-то
     * сейчас: зачисление в замороженную при мёртвой текущей включает её немедленно (см.
     * {@code settleAfterCommit}), и обещание «не начнёт работать до включения» было бы прямой
     * ложью — вместе с ним человек не узнал бы про смену ссылки.
     */
    private String parkedTargetNotice(Long telegramId,
                                      com.orchestra.subscriptions.service.payment.PaymentTarget target) {
        boolean currentIsLive = subscriptionLookup.currentFor(telegramId)
                .map(sub -> sub.activeAt(LocalDateTime.now()))
                .orElse(false);
        String head = "\n" + emoji.e("warn", "⚠️") + " Срок будет зачислен в "
                + "<b>замороженную подписку #" + target.seq() + "</b>. "
                + "На действующей подписке дни не прибавятся.\n";
        return currentIsLive
                ? head + "Она не начнёт работать, пока Вы её не включите — кнопка в разделе "
                        + "«Мои подписки».\n"
                : head + "Действующих подписок нет, поэтому она включится сразу после оплаты — "
                        + "<b>ссылка сменится</b>, её нужно будет заново вставить в приложение.\n";
    }

    /** Занятые слоты работающей подписки; зеркала ещё нет — считаем по человеку, как раньше. */
    private long usedSlotsOfCurrent(Customer u) {
        return subscriptionLookup.currentFor(u.getTelegramId())
                .map(deviceTrackingService::usedSlots)
                .orElseGet(() -> deviceTrackingService.usedSlots(u.getTelegramId()));
    }

    /**
     * Ступень устройств припиской к тарифу: «· Семейная+ · до 10 устройств».
     * <p>
     * Человек покупает ДВЕ вещи разом — срок и число устройств, — а на экране до этого
     * стоял только срок. «Тариф свой не вижу, хочу видеть 10 устройств» — дословная жалоба
     * покупателя семейной ступени (13.08.2026).
     * <p>
     * Источник — ПОДПИСКА, а не последняя оплата: докупка слота меняет лимит, не создавая
     * новой оплаты плана, и ступень из платежа разошлась бы с тем, что реально действует.
     * У кого ступени нет (триал, legacy до появления ступеней) — приписки нет вовсе,
     * выдумывать «до 5 устройств» здесь нельзя: старое обещание безлимита в силе.
     */
    private String currentTierSuffix(Long telegramId, Customer u) {
        String tierId = subscriptionLookup.currentFor(telegramId)
                .map(com.orchestra.subscriptions.service.SubscriptionView::deviceTierId)
                .orElseGet(u::getDeviceTierId);
        if (tierId == null) {
            return "";
        }
        return appConfig.findDeviceTierOptional(tierId)
                .map(t -> " · " + t.getName())
                .orElse("");
    }

    /**
     * Человекочитаемое название текущего тарифа: «Пробный», если платных оплат не было,
     * иначе имя плана из последней SUCCEEDED-оплаты. DB-only (Top20 без AccessProvider).
     */
    private String currentPlanLabel(Long telegramId) {
        Payment lastPaid = paymentRepository.findTop20ByTelegramIdOrderByCreatedAtDesc(telegramId).stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCEEDED)
                // Докупка слота — не тариф: текущий план определяет последняя ПЛАНОВАЯ оплата.
                .filter(p -> !p.isDeviceAddon())
                .findFirst()
                .orElse(null);
        if (lastPaid == null) {
            return "Пробный";
        }
        try {
            return appConfig.findPlan(lastPaid.getPlanId()).getName();
        } catch (IllegalArgumentException e) {
            return lastPaid.getPlanId();
        }
    }

    /** Остаток подписки в формате «28 дн 4 ч» / «4 ч 30 мин» / «истекла». */
    private static String formatTimeLeft(LocalDateTime expiresAt) {
        if (expiresAt == null) {
            return "—";
        }
        java.time.Duration d = java.time.Duration.between(LocalDateTime.now(), expiresAt);
        if (d.isNegative() || d.isZero()) {
            return "истекла";
        }
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return days + " дн " + hours + " ч";
        }
        if (hours > 0) {
            return hours + " ч " + minutes + " мин";
        }
        return Math.max(1, minutes) + " мин";
    }

    // ========================
    // Кабинет (лёгкий профиль-хаб, без денежного баланса)
    // ========================

    public void sendCabinet(Long chatId, Long telegramId) throws TelegramApiException {
        Optional<Customer> userOpt = accessService.getUser(telegramId);
        String subLine;
        if (userOpt.isPresent() && userOpt.get().getExpiresAt() != null
                && userOpt.get().getStatus() == Customer.Status.ACTIVE
                && userOpt.get().getExpiresAt().isAfter(LocalDateTime.now())) {
            subLine = "активна до " + TimeFormat.mskDate(userOpt.get().getExpiresAt());
        } else if (userOpt.isPresent()) {
            subLine = "неактивна";
        } else {
            subLine = "не оформлена";
        }

        ReferralService.ReferralStats stats = referralService.getStats(telegramId);

        StringBuilder sb = new StringBuilder();
        sb.append(emoji.e("person")).append(" <b>Кабинет</b>\n\n")
                .append(emoji.e("chart")).append(" Подписка: ").append(subLine).append("\n")
                .append(emoji.e("people")).append(" Рефералы: ").append(stats.invited())
                .append(" · получено ").append(stats.totalBonusDays()).append(" дн.");

        List<InlineKeyboardRow> rows = new ArrayList<>();

        // «Мои подписки» появляется только когда подписок больше одной: человеку с одной
        // подпиской этот экран нечего показывать, а лишний пункт в кабинете он читает как
        // «у меня что-то ещё есть» и идёт искать несуществующее.
        long subscriptionCount = subscriptionRepository.countByTelegramId(telegramId);
        if (subscriptionCount > 1) {
            InlineKeyboardButton subsBtn = IkButtons.icon(emoji, "rocket", "🚀",
                    "Мои подписки (" + subscriptionCount + ")");
            subsBtn.setCallbackData("sub:list");
            rows.add(new InlineKeyboardRow(subsBtn));
        }

        InlineKeyboardButton devicesBtn = IkButtons.icon(emoji, "phone", "📱", "Мои устройства");
        devicesBtn.setCallbackData("devices");
        rows.add(new InlineKeyboardRow(devicesBtn));

        InlineKeyboardButton historyBtn = IkButtons.icon(emoji, "scroll", "📜", "История платежей");
        historyBtn.setCallbackData("history");
        rows.add(new InlineKeyboardRow(historyBtn));

        // Email — паритет с Mini App: восстановление доступа при потере Telegram.
        InlineKeyboardButton emailBtn = IkButtons.icon(emoji, "mail", "✉️", "Email");
        emailBtn.setCallbackData("email:open");
        rows.add(new InlineKeyboardRow(emailBtn));

        InlineKeyboardButton refBtn = IkButtons.icon(emoji, "people", "👥", "Пригласить друга");
        refBtn.setCallbackData("referral");
        rows.add(new InlineKeyboardRow(refBtn));

        // Автопродление — только если магазин YooKassa умеет рекуррентные платежи
        // (иначе пункт бессмысленный, см. recurrent-enabled).
        if (appConfig.getYookassa() != null && appConfig.getYookassa().isRecurrentEnabled()) {
            InlineKeyboardButton arBtn = IkButtons.icon(emoji, "recycle", "🔁", "Автопродление");
            arBtn.setCallbackData("autorenew:open");
            rows.add(new InlineKeyboardRow(arBtn));
        }

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    // ========================
    // Мои устройства (HWID-учёт с /sub/<token>)
    // ========================

    /**
     * Максимум устройств на экране: сообщение Telegram ограничено 4096 символами, клавиатура —
     * 100 кнопками; label частично из клиентских заголовков (до 128 симв. модель). 12 карточек
     * с запасом влезают даже на максимально длинных названиях. Хранение и так ограничено
     * потолком {@code DeviceTrackingService.MAX_TRACKED_DEVICES}.
     */
    private static final int MAX_DEVICES_SHOWN = 12;

    /**
     * Экран «Мои устройства»: список замеченных устройств (модель/ОС из клиентское приложение-заголовков,
     * последний фетч подписки), занятые слоты и кнопка «Отключить»/«Вернуть» на каждое.
     * Устройства видны только у клиентское приложение-совместимых клиентов (шлют x-hwid); v2rayN/Hiddify
     * в списке не появляются.
     */
    public void sendDevices(Long chatId, Long telegramId) throws TelegramApiException {
        Optional<Customer> userOpt = accessService.getUser(telegramId);
        // Список — тоже в разрезе работающей подписки: устройства спящей человек не может ни
        // отключить осмысленно, ни вернуть — они относятся к другой ссылке.
        List<UserDevice> allDevices = subscriptionLookup.currentFor(telegramId)
                .map(deviceTrackingService::listDevices)
                .orElseGet(() -> deviceTrackingService.listDevices(telegramId));
        // Свежие сверху — старьё за пределами MAX_DEVICES_SHOWN интересует меньше всего.
        List<UserDevice> devices = allDevices.stream()
                .sorted(java.util.Comparator.comparing(UserDevice::getLastSeenAt).reversed())
                .limit(MAX_DEVICES_SHOWN)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(emoji.e("phone", "📱")).append(" <b>Мои устройства</b>\n\n");

        Integer limit = userOpt.map(Customer::getDeviceLimit).orElse(null);
        if (limit != null && limit > 0) {
            sb.append("Занято слотов: <b>")
                    .append(userOpt.map(this::usedSlotsOfCurrent).orElse(0L))
                    .append(" из ").append(limit).append("</b>\n\n");
        }

        if (devices.isEmpty()) {
            sb.append("Устройства не зафиксированы.\n\n")
                    .append("<i>Устройство появляется в списке автоматически при добавлении или ")
                    .append("обновлении подписки в клиентское приложение. Клиенты без поддержки HWID ")
                    .append("(v2rayN, Hiddify) не отображаются.</i>");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (UserDevice d : devices) {
            // Label усечён до разумной длины ДО экранирования — заголовки клиентские,
            // максимально длинная модель не должна раздувать сообщение за лимит Telegram.
            // С пользовательским именем показываем «телефон мамы — iPhone 14 Pro (iOS 18)»;
            // переименовать можно и здесь — кнопка «✏️» ниже (reply-флоу), и в Mini App.
            String hardware = DeviceNames.label(d.getDeviceModel(), d.getDeviceOs(), d.getOsVersion());
            boolean named = d.getCustomName() != null && !d.getCustomName().isBlank();
            String label = truncate(named ? d.getCustomName() + " — " + hardware : hardware, 64);
            String statusMark = switch (d.getStatus()) {
                case ACTIVE -> emoji.e("check", "✔️") + " активно";
                case BLOCKED -> "⛔️ сверх лимита";
                case REVOKED -> "🚫 отключено";
            };
            // htmlEscape С UTF-8: только &<>"' — безымянные HTML-сущности (&hellip; за «…»
            // после truncate, &trade; и т.п.) Telegram-парсер НЕ понимает и валит всё сообщение.
            sb.append("\n<blockquote>")
                    .append(org.springframework.web.util.HtmlUtils.htmlEscape(label, "UTF-8"))
                    .append("\n").append(statusMark)
                    .append(" · был(о) в сети: ").append(TimeFormat.mskDate(d.getLastSeenAt()))
                    .append("</blockquote>");

            InlineKeyboardButton btn = new IkButton();
            String btnLabel = truncate(DeviceTrackingService.displayName(d), 20);
            if (d.getStatus() == UserDevice.Status.REVOKED) {
                btn.setText("↩️ Вернуть: " + btnLabel);
                btn.setCallbackData("device:restore:" + d.getId());
            } else {
                btn.setText("❌ Отключить: " + btnLabel);
                btn.setCallbackData("device:revoke:" + d.getId());
            }
            // ✏️ — имя устройства («телефон мамы»), reply-флоу как в Mini App
            InlineKeyboardButton renameBtn = new IkButton();
            renameBtn.setText("✏️");
            renameBtn.setCallbackData("device:name:" + d.getId());
            rows.add(new InlineKeyboardRow(btn, renameBtn));
        }

        if (allDevices.size() > devices.size()) {
            sb.append("\n<i>…и ещё ").append(allDevices.size() - devices.size())
                    .append(" устаревших устройств. Полный список — в Mini App.</i>");
        }

        // Докупка «+1 устройство» — если доступна (активная подписка со ступенью,
        // потолок доп-слотов не исчерпан). Цена pro-rata до конца срока.
        userOpt.flatMap(deviceTrackingService::addonQuote).ifPresent(quote -> {
            InlineKeyboardButton addonBtn = new IkButton();
            addonBtn.setText("➕ Добавить устройство — " + Money.rub(quote.priceKopeks()) + " ₽");
            addonBtn.setCallbackData("addon:open");
            rows.add(new InlineKeyboardRow(addonBtn));
        });

        // «Выйти со всех устройств»: перевыпуск подписочного токена (ссылка утекла /
        // перепродана / хочется начать с чистого листа). Двухшагово — см. sendRotateLinkConfirm.
        InlineKeyboardButton rotateBtn = new IkButton();
        rotateBtn.setText("🔄 Сменить ссылку подписки");
        rotateBtn.setCallbackData("devlink:rotate");
        rows.add(new InlineKeyboardRow(rotateBtn));

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

    /**
     * Обработка кнопок «Отключить»/«Вернуть» с экрана устройств. Принадлежность устройства
     * юзеру проверяет {@link DeviceTrackingService}. После действия перерисовываем экран.
     */
    public void handleDeviceAction(Long chatId, Long telegramId, String action, String deviceIdRaw)
            throws TelegramApiException {
        long deviceId;
        try {
            deviceId = Long.parseLong(deviceIdRaw);
        } catch (NumberFormatException e) {
            return; // мусорный callback — молча игнорируем, как незнакомые маршруты
        }
        if ("revoke".equals(action)) {
            boolean ok = deviceTrackingService.revoke(telegramId, deviceId);
            if (ok) {
                SendMessage note = new SendMessage(chatId.toString(),
                        "Устройство отключено. При следующем обновлении подписки оно перестанет "
                                + "получать серверы, слот освобождён.");
                telegramSender.execute(note);
            }
        } else if ("restore".equals(action)) {
            boolean activated = deviceTrackingService.restore(telegramId, deviceId);
            if (!activated) {
                SendMessage note = new SendMessage(chatId.toString(),
                        "Свободных слотов нет. Устройство вернётся автоматически при освобождении "
                                + "слота — либо отключите другое устройство.");
                telegramSender.execute(note);
            }
        }
        sendDevices(chatId, telegramId);
    }

    /**
     * «🎟 Есть промокод?» из пикера оплаты: ждём код следующим сообщением
     * ({@link PendingInputService}). payload = «planId[:tierId]» — контекст возврата в пикер.
     */
    public void askPromo(Long chatId, Long telegramId, String payload) throws TelegramApiException {
        pendingInputService.expect(telegramId, PendingInputService.Kind.PROMO, payload);
        telegramSender.execute(new SendMessage(chatId.toString(),
                "Отправьте промокод одним сообщением.\n\nОтмена: /cancel"));
    }

    /** «✖️ Убрать промокод» — снять применённый код и перерисовать пикер с базовой ценой. */
    public void clearPromo(Long chatId, Long telegramId, String payload) throws TelegramApiException {
        pendingInputService.clearPromo(telegramId);
        sendPaymentMethodPicker(chatId, telegramId, payload);
    }

    /** «✏️» на устройстве: ждём новое имя следующим сообщением. */
    public void askDeviceName(Long chatId, Long telegramId, String deviceId) throws TelegramApiException {
        pendingInputService.expect(telegramId, PendingInputService.Kind.DEVICE_NAME, deviceId);
        telegramSender.execute(new SendMessage(chatId.toString(),
                "Отправьте название устройства одним сообщением, например «телефон мамы». "
                        + "Пустое сообщение вернёт исходное название.\n\nОтмена: /cancel"));
    }

    /**
     * Статус серверов — паритет с экраном Mini App (для юзеров без работающего WebView).
     * {@link ServerStatusService#getStatuses} кеширует пробы на 30 сек, так что спам
     * кнопкой не долбит ноды.
     */
    public void sendServers(Long chatId) throws TelegramApiException {
        StringBuilder sb = new StringBuilder("🖥 <b>Статус серверов</b>\n\n");
        boolean anyUnknown = false;
        for (ServerStatusService.ServerStatus s : serverStatusService.getStatuses()) {
            sb.append(s.flag()).append(" ").append(org.springframework.web.util.HtmlUtils
                            .htmlEscape(s.name(), "UTF-8")).append(" — ");
            if (!s.known()) {
                // Мониторинг не ответил. «Недоступен» здесь был бы выдумкой про ноду, а
                // «работает» — выдумкой в пользу сервиса; и то и другое хуже честного прочерка.
                sb.append("⚪️ нет данных");
                anyUnknown = true;
            } else {
                sb.append(s.online() ? "✅ работает" : "❌ недоступен");
                // Аптайм за сутки — то, чего экран не показывал никогда: нода может отвечать
                // сейчас и при этом падать по десять раз в день, и человек, выбирающий сервер,
                // видит только мгновенный снимок.
                if (s.uptime24() != null) {
                    sb.append(" · за сутки ").append(String.format("%.1f%%", s.uptime24()));
                }
                if (s.online() && s.pingMs() != null) {
                    sb.append(" · ").append(Math.round(s.pingMs())).append(" мс");
                }
            }
            sb.append("\n");
        }
        if (anyUnknown) {
            sb.append("\n<i>По части серверов мониторинг сейчас не отвечает — это не значит, "
                    + "что они не работают.</i>\n");
        }
        sb.append("\n<i>Серверы переключаются в приложении клиентское приложение: если один недоступен, "
                + "выберите другой.</i>");
        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    // Сырую AccessProvider-ссылку (без обхода РФ-сервисов) юзеру НЕ показываем нигде — решение
    // владельца 2026-07-15: везде только вариант сервиса со встроенной маршрутизацией
    // (SubscriptionLinks.subscriptionLinkHtml). Кнопка «полная ссылка» удалена.

    /**
     * Выбор способа оплаты докупки «+1 устройство». Котировка пересчитывается здесь же —
     * между экранами могла смениться (день прошёл, лимит выкуплен с другого устройства).
     */
    public void sendAddonPicker(Long chatId, Long telegramId) throws TelegramApiException {
        Optional<DeviceTrackingService.AddonQuote> quoteOpt =
                accessService.getUser(telegramId).flatMap(deviceTrackingService::addonQuote);
        if (quoteOpt.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Докупка устройства недоступна. Требуется активная подписка с выбранной "
                            + "ступенью, а лимит дополнительных слотов не должен быть исчерпан."));
            return;
        }
        DeviceTrackingService.AddonQuote quote = quoteOpt.get();
        String rub = Money.rub(quote.priceKopeks());

        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardButton cardBtn = IkButtons.icon(emoji, "card", "💳", "Картой — " + rub + " ₽");
        cardBtn.setCallbackData("addon:yookassa");
        rows.add(new InlineKeyboardRow(cardBtn));
        InlineKeyboardButton cryptoBtn = IkButtons.icon(emoji, "crypto", "💸", "Криптой — " + rub + " ₽");
        cryptoBtn.setCallbackData("addon:crypto");
        rows.add(new InlineKeyboardRow(cryptoBtn));
        if (quote.priceStars() > 0) {
            InlineKeyboardButton starsBtn = IkButtons.icon(emoji, "star", "⭐",
                    "Telegram Stars — " + quote.priceStars() + " ⭐");
            starsBtn.setCallbackData("addon:stars");
            rows.add(new InlineKeyboardRow(starsBtn));
        }
        InlineKeyboardButton backBtn = new IkButton();
        backBtn.setText("⬅️ Назад");
        backBtn.setCallbackData("devices");
        rows.add(new InlineKeyboardRow(backBtn));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("➕ <b>Дополнительное устройство</b>\n\n"
                + "Лимит увеличится до <b>" + (quote.currentLimit() + 1) + "</b> устройств "
                + "на оставшиеся <b>" + quote.remainingDays() + " дн.</b> подписки. "
                + "Стоимость: " + rub + " ₽.\n\n"
                + "<i>Дополнительные слоты действуют до конца текущего срока: при продлении "
                + "лимит снова берётся из тарифа.</i>");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /**
     * Подтверждение смены ссылки подписки: действие «выйти со всех устройств» обрывает доступ
     * на всех устройствах юзера до ручного добавления новой ссылки — без двухшага рано
     * или поздно кто-нибудь нажмёт случайно.
     */
    public void sendRotateLinkConfirm(Long chatId) throws TelegramApiException {
        InlineKeyboardButton yesBtn = new IkButton();
        yesBtn.setText("✅ Да, сменить ссылку");
        yesBtn.setCallbackData("devlink:rotate:confirm");
        InlineKeyboardButton cancelBtn = new IkButton();
        cancelBtn.setText("⬅️ Отмена");
        cancelBtn.setCallbackData("devices");

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(yesBtn), new InlineKeyboardRow(cancelBtn)));

        SendMessage msg = new SendMessage(chatId.toString(),
                "🔄 <b>Сменить ссылку подписки?</b>\n\n"
                        + "Старая ссылка перестанет работать <b>на всех устройствах сразу</b>: "
                        + "доступ отключится везде, список устройств будет очищен. Новую ссылку "
                        + "потребуется добавить в клиентское приложение на тех устройствах, которые Вы оставляете.\n\n"
                        + "Смена нужна, если ссылка утекла или ей пользуется посторонний.");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /** Выполняет смену ссылки (после подтверждения) и присылает новую. */
    public void handleRotateLink(Long chatId, Long telegramId) throws TelegramApiException {
        Customer user;
        try {
            user = accessService.rotateSubscriptionLink(telegramId);
        } catch (Exception e) {
            // Ротация ходит в AccessProvider: без записи в лог отказ виден только пользователю,
            // а владелец узнаёт о нём из обращения в поддержку.
            log.warn("Смена ссылки подписки не удалась для telegramId={}", telegramId, e);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Сменить ссылку не удалось. Повторите попытку позже или обратитесь в поддержку."));
            return;
        }
        SendMessage msg = new SendMessage(chatId.toString(),
                "✅ <b>Ссылка подписки обновлена</b>\n\n"
                        + "Старая ссылка больше не работает, список устройств очищен.\n\n"
                        + SubscriptionLinks.subscriptionLinkHtml(
                                user.getSubscriptionLink(), appConfig.getTelegram().getPublicBaseUrl(), emoji)
                        + "\n\nДобавьте новую ссылку в клиентское приложение на своих устройствах. "
                        + "Инструкция: /help");
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    /**
     * Усечение по code point'ам, а не char'ам — label содержит клиентские строки, обрезка
     * посреди суррогатной пары (эмодзи в названии модели) дала бы невалидный UTF-16,
     * который Telegram отклоняет целым сообщением.
     */
    private static String truncate(String label, int maxCodePoints) {
        if (label.codePointCount(0, label.length()) <= maxCodePoints) {
            return label;
        }
        int endIndex = label.offsetByCodePoints(0, maxCodePoints - 1);
        return label.substring(0, endIndex) + "…";
    }

    /**
     * История платежей пользователя (последние 15, все статусы — успешные/возвраты/отменённые),
     * чтобы человек видел полную картину. Это то, чего просил пользователь явно.
     */
    public void sendPaymentHistory(Long chatId, Long telegramId) throws TelegramApiException {
        List<Payment> payments = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(telegramId).stream()
                .limit(15)
                .toList();

        StringBuilder sb = new StringBuilder(emoji.e("scroll")).append(" <b>История платежей</b>\n\n");
        if (payments.isEmpty()) {
            sb.append("Оплат не было.");
        } else {
            for (Payment p : payments) {
                LocalDateTime when = p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt();
                String planName;
                if (p.isDeviceAddon()) {
                    planName = "Доп. устройство";
                } else try {
                    planName = appConfig.findPlan(p.getPlanId()).getName();
                } catch (IllegalArgumentException e) {
                    planName = p.getPlanId();
                }
                sb.append(paymentStatusEmoji(p.getStatus())).append(" ")
                        .append(TimeFormat.mskDate(when)).append(" · ")
                        .append(planName).append(" · ")
                        .append(Money.rub(p.getAmountKopeks())).append(" ₽ · ")
                        .append(providerLabel(p.getProvider()))
                        .append(p.getStatus() == Payment.Status.REFUNDED ? " · возврат" : "")
                        .append("\n");
            }
        }

        InlineKeyboardButton backBtn = new IkButton();
        backBtn.setText("⬅️ Назад в Кабинет");
        backBtn.setCallbackData("cabinet");

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(backBtn)));

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    private static String paymentStatusEmoji(Payment.Status status) {
        if (status == null) {
            return "•";
        }
        return switch (status) {
            case SUCCEEDED -> "✅";
            case REFUNDED -> "↩️";
            case PENDING -> "⏳";
            case CANCELED, FAILED -> "❌";
        };
    }

    private static String providerLabel(Payment.Provider provider) {
        if (provider == null) {
            return "—";
        }
        return switch (provider) {
            case YOOKASSA -> "картой";
            case STARS -> "Stars";
            case CRYPTOBOT -> "крипта";
        };
    }

    // ========================
    // Поддержка
    // ========================

    public void sendSupport(Long chatId) throws TelegramApiException {
        String supportUrl = appConfig.getTelegram().getSupportUrl();

        String text = emoji.e("question") + " <b>Поддержка</b>\n\n"
                + "Поддержка отвечает по вопросам подключения, оплаты и подписки.\n\n"
                + "• Опишите проблему подробно — это ускорит решение";

        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (supportUrl != null && !supportUrl.isBlank()) {
            InlineKeyboardButton writeBtn = IkButtons.icon(emoji, "mail", "✉️", "Написать в поддержку");
            writeBtn.setUrl(supportUrl);
            rows.add(new InlineKeyboardRow(writeBtn));
        }

        InlineKeyboardButton guideBtn = IkButtons.icon(emoji, "book", "📖", "Как подключить доступ");
        guideBtn.setCallbackData("how_to");
        rows.add(new InlineKeyboardRow(guideBtn));

        // Статус серверов — паритет с Mini App (тот же ServerStatusService).
        InlineKeyboardButton serversBtn = IkButtons.icon(emoji, "chart", "🖥", "Статус серверов");
        serversBtn.setCallbackData("servers");
        rows.add(new InlineKeyboardRow(serversBtn));

        String newsUrl = appConfig.getTelegram().getNewsChannelUrl();
        if (newsUrl != null && !newsUrl.isBlank()) {
            InlineKeyboardButton newsBtn = IkButtons.icon(emoji, "news", "🔈", "Канал с новостями");
            newsBtn.setUrl(newsUrl);
            rows.add(new InlineKeyboardRow(newsBtn));
        }

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    public void sendHelp(Long chatId) throws TelegramApiException {
        sendHowToVideo(chatId);
        String text = emoji.e("book") + " <b>Как подключить доступ — клиентское приложение</b>\n\n"
                + emoji.e("plus") + " <b>1.</b> Скачайте <b>клиентское приложение</b> (кнопки ниже) на свою ОС.\n\n"
                + emoji.e("link") + " <b>2.</b> В боте: «" + emoji.e("rocket") + " Мой доступ» → «Копировать» (или /status) — ссылка в буфере.\n\n"
                + emoji.e("down") + " <b>3.</b> Откройте клиентское приложение → <b>«Добавить подписку»</b> / вставьте ссылку из буфера.\n\n"
                + emoji.e("check") + " <b>4.</b> Выберите сервер и нажмите <b>«Подключиться»</b>.\n\n"
                + "Если серверы не загружаются, переподключите Wi-Fi или мобильный интернет "
                + "и повторите попытку.";

        List<InlineKeyboardRow> rows = new ArrayList<>();

        // Скачать приложение — ссылки на страницы клиента под каждую ОС.
        // Адреса вынесены в константы: их меняют при смене рекомендованного клиента,
        // а не правкой текста экрана.
        InlineKeyboardButton androidBtn = new IkButton();
        androidBtn.setText("🤖 Android");
        androidBtn.setUrl(CLIENT_ANDROID_URL);
        InlineKeyboardButton iosBtn = new IkButton();
        iosBtn.setText("🍎 iPhone");
        iosBtn.setUrl(CLIENT_IOS_URL);
        InlineKeyboardButton winBtn = new IkButton();
        winBtn.setText("💻 Windows");
        winBtn.setUrl(CLIENT_DESKTOP_URL);
        rows.add(new InlineKeyboardRow(androidBtn, iosBtn, winBtn));

        InlineKeyboardButton backBtn = new IkButton();
        backBtn.setText("⬅️ В меню");
        backBtn.setCallbackData("menu:start");
        rows.add(new InlineKeyboardRow(backBtn));

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /**
     * Ролик «как подключиться» перед текстовой инструкцией. Не настроен {@code how-to-video-id}
     * — просто ничего не отправляем.
     * <p>
     * Ошибка отправки НЕ роняет экран: видео здесь — дополнение, а инструкция — суть. Протухший
     * {@code file_id} (видео удалили из Telegram, сменили бота) не должен оставлять человека,
     * который нажал «Как подключиться», вообще без ответа. Поэтому исключение гасится в WARN,
     * а текст уходит дальше как обычно.
     * <p>
     * Отдельным сообщением, а не подписью к видео: подпись ограничена 1024 символами, текст
     * инструкции близок к этой границе, и любое его дополнение однажды молча превратило бы
     * весь экран в ошибку Telegram.
     */
    private void sendHowToVideo(Long chatId) {
        String configured = appConfig.getTelegram() == null ? null : appConfig.getTelegram().getHowToVideoId();
        if (configured == null || configured.isBlank()) {
            return;
        }
        String value = configured.trim();
        boolean animation = value.startsWith(HOW_TO_ANIMATION_PREFIX);
        String fileId = animation ? value.substring(HOW_TO_ANIMATION_PREFIX.length()).trim() : value;
        try {
            if (animation) {
                telegramSender.execute(
                        org.telegram.telegrambots.meta.api.methods.send.SendAnimation.builder()
                                .chatId(chatId.toString())
                                .animation(new org.telegram.telegrambots.meta.api.objects.InputFile(fileId))
                                .caption(HOW_TO_CAPTION)
                                .build());
            } else {
                telegramSender.execute(
                        org.telegram.telegrambots.meta.api.methods.send.SendVideo.builder()
                                .chatId(chatId.toString())
                                .video(new org.telegram.telegrambots.meta.api.objects.InputFile(fileId))
                                .caption(HOW_TO_CAPTION)
                                .supportsStreaming(true)
                                .build());
            }
        } catch (Exception e) {
            log.warn("Ролик «как подключиться» не отправился (chatId={}, animation={}): {}",
                    chatId, animation, e.getMessage());
        }
    }

    /**
     * Пометка в {@code HOW_TO_VIDEO_ID}, что идентификатор принадлежит анимации, а не видео.
     * <p>
     * Различать обязательно: mp4 без звуковой дорожки Telegram считает гифкой и отдаёт в
     * {@code message.animation}, а {@code file_id} привязан к типу — идентификатор анимации
     * через {@code sendVideo} не отправится. Владельцу помнить об этом не нужно: бот в ответ
     * на присланный ролик печатает готовую строку для {@code .env} вместе с этой пометкой.
     */
    static final String HOW_TO_ANIMATION_PREFIX = "animation:";

    private static final String HOW_TO_CAPTION = "Как это выглядит целиком";

    /**
     * /terms (или /offer) — публичная оферта. Если URL не настроен (OFFER_URL не задан),
     * отдаём краткое сообщение что оферта в подготовке + контакт владельца.
     */
    public void sendTerms(Long chatId) throws TelegramApiException {
        String offerUrl = appConfig.getTelegram().getOfferUrl();
        if (offerUrl == null || offerUrl.isBlank()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "📄 Публичная оферта в подготовке.\n\n"
                    + "По вопросам обслуживания обращайтесь к владельцу бота."));
            return;
        }
        String text = "📄 <b>Публичная оферта</b>\n\n"
                + "Условия использования сервиса и порядок оплаты — по ссылке ниже. "
                + "Оплата подписки означает принятие этих условий.";

        InlineKeyboardButton offerBtn = new IkButton();
        offerBtn.setText("📄 Открыть оферту");
        offerBtn.setUrl(offerUrl);

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(offerBtn)));

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }
}
