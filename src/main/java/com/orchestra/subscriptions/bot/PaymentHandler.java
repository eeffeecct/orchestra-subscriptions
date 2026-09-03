package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.service.PaymentProcessingService;
import com.orchestra.subscriptions.service.payment.PaymentDraft;
import com.orchestra.subscriptions.service.payment.PaymentFactory;
import com.orchestra.subscriptions.service.payment.TargetSpec;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkMarkup;
import com.orchestra.subscriptions.util.IkSendMessage;
import com.orchestra.subscriptions.util.Money;
import com.orchestra.subscriptions.util.StarsAmount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Money-path оплаты, вынесенный из god-class SubscriptionBot (PR-3): in-chat создание счетов
 * (YooKassa / CryptoBot / Telegram Stars) + Telegram-side хуки (pre_checkout_query,
 * successful_payment). Чисто платёжная логика — без emoji/UI-рендера (экраны выбора
 * тарифа остаются в SubscriptionBot до PR-4).
 * <p>
 * Отправка — через {@link TelegramSender} (мокается в тестах). SubscriptionBot инжектит
 * {@code @Lazy PaymentHandler}: цикл SubscriptionBot → PaymentHandler → PaymentProcessingService
 * → NotificationService → SubscriptionBot разрывается на старте, поэтому сам хендлер eager.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentHandler {

    private final AppConfig appConfig;
    private final PaymentRepository paymentRepository;
    /** Строку платежа заводит только фабрика — она же пишет цель (в какую подписку зачислять). */
    private final PaymentFactory paymentFactory;
    private final YooKassaClient yooKassaClient;
    private final CryptoBotClient cryptoBotClient;
    private final PaymentProcessingService paymentProcessingService;
    private final com.orchestra.subscriptions.service.AccessService accessService;
    private final com.orchestra.subscriptions.service.DeviceTrackingService deviceTrackingService;
    private final com.orchestra.subscriptions.service.PromoCodeService promoCodeService;
    private final PendingInputService pendingInputService;
    private final TelegramSender telegramSender;

    /**
     * Итог применения промокода к счёту. {@code rejectedReason != null} — счёт со скидкой не
     * состоялся, и вызывающий обязан сообщить причину, а не идти к провайдеру: списать больше
     * показанного нельзя.
     */
    private record PromoApplied(long chargeKopeks, String rejectedReason) {
        boolean rejected() {
            return rejectedReason != null;
        }
    }

    /**
     * Промокод из бот-флоу («🎟 Есть промокод?»): резервирует запомненный код под УЖЕ созданный
     * счёт и пересчитывает его сумму. Без кода — базовая цена.
     * <p>
     * Слот кода занимается здесь, вместе со счётом, а не на webhook'е SUCCEEDED: read-only-проверку
     * N параллельных чекаутов проходили все и все получали скидку (см.
     * {@code PromoCodeService.reserveForCheckout}). Поэтому строка платежа заводится ДО применения
     * промокода — резерву нужен её id, — а не после, как раньше.
     */
    private PromoApplied applyBotPromo(Payment pending, Long telegramId, long baseKopeks) {
        String code = pendingInputService.getAppliedPromo(telegramId);
        if (code == null) {
            return new PromoApplied(baseKopeks, null);
        }
        var result = promoCodeService.reserveForCheckout(code, baseKopeks, telegramId, pending.getId());
        if (!result.valid()) {
            pendingInputService.clearPromo(telegramId);
            // Счёт со скидкой не состоялся — PENDING-строку не оставляем висеть.
            pending.setStatus(Payment.Status.CANCELED);
            paymentRepository.save(pending);
            log.info("Промокод отклонён в боте: code={} reason={} telegramId={}",
                    code, result.reason(), telegramId);
            return new PromoApplied(0, result.reason() == null ? "invalid" : result.reason());
        }
        pending.setPromoCodeUsed(result.code());
        if (result.discountKopeks() > 0) {
            pending.setAmountKopeks(result.finalAmountKopeks());
        }
        paymentRepository.save(pending);
        return new PromoApplied(result.finalAmountKopeks(), null);
    }

    /**
     * Снимает резерв промокода со счёта, который выставить не удалось. Ошибку глотаем: человеку
     * важнее ответ о несостоявшемся платеже, а невзятый резерв протухнет и сам.
     */
    private void releasePromoReservation(Payment pending) {
        if (pending.getPromoCodeUsed() == null) {
            return;
        }
        try {
            promoCodeService.releaseReservation(pending.getId());
        } catch (Exception e) {
            log.warn("Не удалось снять резерв промокода по платежу id={}", pending.getId(), e);
        }
    }

    /**
     * Почему счёт со скидкой не создан. «Уже висит неоплаченный счёт с этим кодом» — не то же
     * самое, что «код больше не действует»: в первом случае человеку надо оплатить или дождаться
     * отмены счёта, и отправлять его выбирать тариф заново значило бы гонять его по кругу.
     */
    private static String promoRejectionMessage(String reason) {
        if ("pending_checkout".equals(reason)) {
            return "⚠️ Счёт с этим промокодом уже выставлен, новый не создан.\n\n"
                    + "Оплатите выставленный счёт или дождитесь его отмены — после этого "
                    + "код снова станет доступен.";
        }
        return PROMO_EXPIRED_MSG;
    }

    private static final String PROMO_EXPIRED_MSG =
            "⚠️ Промокод применить не удалось: он больше не действует или уже использован. "
                    + "Счёт не создан.\n\nОткройте выбор тарифа заново.";

    /** Описание докупки слота — идёт в счёт платёжного провайдера. */
    private static final String ADDON_DESCRIPTION = "Orchestra — дополнительное устройство";

    // ========================
    // Оплата через ЮKassa
    // ========================

    /**
     * Разовый платёж картой через ЮKassa. {@code savePaymentMethod} оставлен параметром
     * для совместимости сигнатуры, но из чата всегда вызывается с {@code false} —
     * автопродление настраивается только в Mini App.
     * <p>
     * {@code deviceTierId} — ступень устройств ({@code app.device-tiers}); null/невалидна →
     * legacy-цена плана без назначения лимита (старые кнопки в чатах продолжают работать).
     */
    public void handleYooKassaPayment(Long chatId, User user, String planId, String deviceTierId,
                                       boolean savePaymentMethod) throws TelegramApiException {
        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(planId);
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Неизвестный тариф."));
            return;
        }
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(deviceTierId).orElse(null);

        // 1. Создаём запись о платеже в БД (PENDING) по базовой цене. Цель — текущая подписка:
        //    именно её состояние человек видел на экране, с которого пришёл сюда.
        Payment pending = paymentFactory.create(PaymentDraft.builder()
                .telegramId(user.getId())
                .telegramUsername(user.getUserName())
                .planId(plan.getId())
                .deviceTierId(tier != null ? tier.getId() : null)
                .amountKopeks(appConfig.tierPriceKopeks(plan, tier))
                .provider(Payment.Provider.YOOKASSA)
                .build(), TargetSpec.current());

        // 2. Промокод: резервируем слот под этот счёт и пересчитываем сумму.
        PromoApplied promo = applyBotPromo(pending, user.getId(), pending.getAmountKopeks());
        if (promo.rejected()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    promoRejectionMessage(promo.rejectedReason())));
            return;
        }
        long priceKopeks = promo.chargeKopeks();

        // 3. Обращаемся в ЮKassa за confirmation_url
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("telegram_id", user.getId().toString());
        metadata.put("plan_id", plan.getId());
        metadata.put("payment_row_id", pending.getId().toString());

        YooKassaClient.PaymentResponse resp;
        try {
            resp = yooKassaClient.createPayment(
                    priceKopeks,
                    describe(plan, tier),
                    appConfig.getYookassa().getReturnUrl(),
                    metadata,
                    UUID.randomUUID().toString(),
                    savePaymentMethod
            );
        } catch (Exception e) {
            log.error("YooKassa createPayment failed for telegramId={}", user.getId(), e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            releasePromoReservation(pending);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Создать платёж не удалось. Повторите попытку через минуту или обратитесь в поддержку."));
            return;
        }

        // 4. Сохраняем id платежа ЮKassa для последующей сверки в webhook
        pending.setYookassaPaymentId(resp.getId());
        paymentRepository.save(pending);

        // 5. Отправляем пользователю кнопку перехода к оплате
        InlineKeyboardButton payBtn = new IkButton();
        payBtn.setText("Перейти к оплате");
        payBtn.setUrl(resp.getConfirmation().getConfirmationUrl());

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(payBtn)));

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("Тариф: <b>" + tariffLabel(plan, tier) + "</b>\n"
                + "Сумма: " + Money.rub(priceKopeks) + " ₽\n\n"
                + "Для оплаты нажмите кнопку ниже. После зачисления платежа ссылка подключения "
                + "придёт в этот чат автоматически.");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    // ========================
    // Оплата через CryptoBot
    // ========================

    public void handleCryptoPayment(Long chatId, User user, String planId, String deviceTierId)
            throws TelegramApiException {
        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(planId);
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Неизвестный тариф."));
            return;
        }
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(deviceTierId).orElse(null);

        // 1. PENDING-запись с провайдером CRYPTOBOT по базовой цене, цель — текущая подписка
        Payment pending = paymentFactory.create(PaymentDraft.builder()
                .telegramId(user.getId())
                .telegramUsername(user.getUserName())
                .planId(plan.getId())
                .deviceTierId(tier != null ? tier.getId() : null)
                .amountKopeks(appConfig.tierPriceKopeks(plan, tier))
                .provider(Payment.Provider.CRYPTOBOT)
                .build(), TargetSpec.current());

        // 2. Промокод: резервируем слот под этот счёт и пересчитываем сумму.
        PromoApplied promo = applyBotPromo(pending, user.getId(), pending.getAmountKopeks());
        if (promo.rejected()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    promoRejectionMessage(promo.rejectedReason())));
            return;
        }
        long priceKopeks = promo.chargeKopeks();

        // 3. Инвойс в CryptoBot. Цена в рублях — провайдер сам конвертирует в выбранную крипту.
        //    cfg может быть null в dev (CRYPTOBOT_* не заданы) — фолбэк на дефолтные ассеты.
        AppConfig.CryptoBotConfig cfg = appConfig.getCryptobot();
        String acceptedAssets = cfg != null && cfg.getAcceptedAssets() != null
                ? cfg.getAcceptedAssets() : "USDT,TON,BTC,ETH";
        String paidBtnUrl = cfg != null ? cfg.getPaidBtnUrl() : null;

        CryptoBotClient.InvoiceResponse resp;
        try {
            resp = cryptoBotClient.createInvoice(
                    priceKopeks,
                    describe(plan, tier),
                    pending.getId().toString(),
                    acceptedAssets,
                    paidBtnUrl
            );
        } catch (Exception e) {
            log.error("CryptoBot createInvoice failed for telegramId={}", user.getId(), e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            releasePromoReservation(pending);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Создать крипто-счёт не удалось. Повторите попытку через минуту или выберите оплату картой."));
            return;
        }

        // 4. Сохраняем invoice_id для сверки в webhook
        pending.setCryptoInvoiceId(String.valueOf(resp.getInvoiceId()));
        paymentRepository.save(pending);

        // 5. Кнопка перехода к оплате. Предпочитаем miniApp URL (открывается внутри Telegram),
        //    падаем на web/bot URL как запасной вариант.
        String url = resp.getMiniAppInvoiceUrl();
        if (url == null || url.isBlank()) url = resp.getWebAppInvoiceUrl();
        if (url == null || url.isBlank()) url = resp.getBotInvoiceUrl();
        if (url == null || url.isBlank()) url = resp.getPayUrl();

        InlineKeyboardButton payBtn = new IkButton();
        payBtn.setText("Оплатить криптой");
        payBtn.setUrl(url);

        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(payBtn)));

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("Тариф: <b>" + tariffLabel(plan, tier) + "</b>\n"
                + "Сумма: " + Money.rub(priceKopeks) + " ₽ (≈ по курсу USDT/TON/BTC/ETH)\n\n"
                + "Для оплаты через @CryptoBot нажмите кнопку ниже. После зачисления платежа "
                + "ссылка подключения придёт в этот чат автоматически.");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    // ========================
    // Оплата через Telegram Stars
    // ========================

    public void handleStarsPayment(Long chatId, User user, String planId, String deviceTierId)
            throws TelegramApiException {
        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(planId);
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Неизвестный тариф."));
            return;
        }
        if (plan.getPriceStars() <= 0) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Оплата Stars для этого тарифа временно недоступна. Выберите другой способ."));
            return;
        }
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(deviceTierId).orElse(null);

        // 1. PENDING-запись с провайдером STARS по базовой цене, цель — текущая подписка
        Payment pending = paymentFactory.create(PaymentDraft.builder()
                .telegramId(user.getId())
                .telegramUsername(user.getUserName())
                .planId(plan.getId())
                .deviceTierId(tier != null ? tier.getId() : null)
                .amountKopeks(appConfig.tierPriceKopeks(plan, tier))
                .provider(Payment.Provider.STARS)
                .build(), TargetSpec.current());

        // 2. Промокод: резервируем слот под этот счёт и пересчитываем сумму.
        PromoApplied promo = applyBotPromo(pending, user.getId(), pending.getAmountKopeks());
        if (promo.rejected()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    promoRejectionMessage(promo.rejectedReason())));
            return;
        }
        long priceKopeks = promo.chargeKopeks();
        // Stars пропорционально от рублёвой цены ступени (со скидкой) — тем же helper'ом,
        // которым handlePreCheckout потом перепроверит сумму (по payment.amountKopeks).
        long priceStars = StarsAmount.compute(plan.getPriceStars(), plan.getPriceKopeks(), priceKopeks);

        // 3. sendInvoice с currency="XTR" для Telegram Stars. В 9.5 это нативно: providerToken
        //    и startParameter необязательны (validate() их не проверяет), поэтому прежний костыль
        //    с анонимным subclass и переопределением validate() больше не нужен.
        //    payload = наш payment.id — вернётся в pre_checkout_query и successful_payment.
        SendInvoice invoice = SendInvoice.builder()
                .chatId(chatId.toString())
                .title(tariffLabel(plan, tier))
                .description(describe(plan, tier))
                .payload(pending.getId().toString())
                .currency("XTR")
                .prices(List.of(LabeledPrice.builder()
                        .label(tariffLabel(plan, tier))
                        .amount(Math.toIntExact(priceStars))
                        .build()))
                .build();

        try {
            telegramSender.execute(invoice);
        } catch (TelegramApiException e) {
            log.error("Telegram sendInvoice (Stars) failed for telegramId={}", user.getId(), e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            releasePromoReservation(pending);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Создать счёт Stars не удалось. Повторите попытку или выберите другой способ оплаты."));
        }
    }

    // ========================
    // Платежи (создание счетов / pre-checkout / successful_payment)
    // ========================
    //
    // Счета выставляются из ДВУХ мест, и оба живые:
    //  - бот, прямо в чате: кнопки «pay:yookassa:/pay:crypto:/pay:stars:/addon:*» рисует
    //    ScreenHandler, маршрутизирует CallbackRouter, а обрабатывают handleYooKassaPayment /
    //    handleCryptoPayment / handleStarsPayment / handleAddonPayment выше в этом классе;
    //  - Mini App: WebAppController#createYooKassaPayment и соседние эндпоинты.
    //
    // Ниже — Telegram-side хуки платежей:
    //  - pre_checkout_query (validate Stars-инвойс перед списанием)
    //  - successful_payment (Stars прошли — активируем подписку)
    // Они нужны независимо от того, откуда пришёл инвойс — ботовский sendInvoice
    // или Mini-App-овский createInvoiceLink.


    /**
     * Pre-checkout от Telegram: отвечаем в течение 10 секунд, иначе платёж отменится.
     * Валидируем, что invoice_payload — это наш существующий PENDING-платёж со Stars,
     * и что сумма совпадает с тем, что мы попросили. Валюта тоже должна быть XTR.
     */
    public void handlePreCheckout(PreCheckoutQuery query) throws TelegramApiException {
        String payload = query.getInvoicePayload();
        Long paymentId;
        try {
            paymentId = Long.parseLong(payload);
        } catch (NumberFormatException e) {
            answerPreCheckout(query.getId(), false, "Некорректный счёт. Создайте новый через /start.");
            return;
        }

        Optional<Payment> opt = paymentRepository.findById(paymentId);
        if (opt.isEmpty()) {
            answerPreCheckout(query.getId(), false, "Счёт не найден. Создайте новый через /start.");
            return;
        }
        Payment p = opt.get();

        if (p.getProvider() != Payment.Provider.STARS) {
            answerPreCheckout(query.getId(), false, "Счёт относится к другому способу оплаты.");
            return;
        }
        if (p.getStatus() != Payment.Status.PENDING) {
            answerPreCheckout(query.getId(), false, "Этот счёт уже обработан. Создайте новый через /start.");
            return;
        }
        if (!"XTR".equals(query.getCurrency())) {
            answerPreCheckout(query.getId(), false, "Неподдерживаемая валюта.");
            return;
        }
        // Сверяем сумму через тот же helper, что использовался при создании инвойса
        // (см. WebAppController.createStarsPayment / handleAddonPayment). До 2026-04-30
        // сравнивали с full plan.priceStars, что молча резало ВСЕ Stars-оплаты с промокодом.
        long expectedStars;
        if (p.isDeviceAddon()) {
            AppConfig.DeviceAddonConfig addonCfg = appConfig.getDeviceAddon();
            if (addonCfg == null || addonCfg.getPriceStarsPerMonth() <= 0) {
                answerPreCheckout(query.getId(), false, "Оплата Stars недоступна. Создайте новый счёт.");
                return;
            }
            expectedStars = StarsAmount.compute(
                    addonCfg.getPriceStarsPerMonth(), addonCfg.getPriceKopeksPerMonth(),
                    p.getAmountKopeks());
        } else {
            AppConfig.Plan plan;
            try {
                plan = appConfig.findPlan(p.getPlanId());
            } catch (IllegalArgumentException e) {
                answerPreCheckout(query.getId(), false, "Неизвестный тариф.");
                return;
            }
            expectedStars = StarsAmount.compute(
                    plan.getPriceStars(), plan.getPriceKopeks(), p.getAmountKopeks());
        }
        if (query.getTotalAmount() == null || query.getTotalAmount() != expectedStars) {
            log.warn("Pre-checkout amount mismatch: expected={} got={} (planId={}, paymentKopeks={})",
                    expectedStars, query.getTotalAmount(),
                    p.getPlanId(), p.getAmountKopeks());
            answerPreCheckout(query.getId(), false, "Сумма не совпадает. Создайте новый счёт через /start.");
            return;
        }

        answerPreCheckout(query.getId(), true, null);
    }

    // ========================
    // Докупка «+1 устройство» (app.device-addon)
    // ========================

    /**
     * Покупка слота устройства из чата: цена pro-rata до конца подписки
     * ({@link com.orchestra.subscriptions.service.DeviceTrackingService#addonQuote}), платёж с
     * {@code planId = device_addon} — webhook инкрементит {@code device_limit} вместо дней.
     *
     * @param provider {@code yookassa} | {@code crypto} | {@code stars}
     */
    public void handleAddonPayment(Long chatId, User user, String provider) throws TelegramApiException {
        var customer = accessService.getUser(user.getId()).orElse(null);
        var quoteOpt = customer == null
                ? java.util.Optional.<com.orchestra.subscriptions.service.DeviceTrackingService.AddonQuote>empty()
                : deviceTrackingService.addonQuote(customer);
        if (quoteOpt.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Докупка устройства недоступна. Требуется активная подписка с выбранной "
                            + "ступенью, а лимит дополнительных слотов не должен быть исчерпан."));
            return;
        }
        var quote = quoteOpt.get();
        // Stars-гвард ДО создания PENDING — иначе каждый тап оставлял бы orphan-строку.
        if ("stars".equals(provider) && quote.priceStars() <= 0) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Оплата Stars для докупки устройства недоступна. Выберите другой способ."));
            return;
        }

        // Докупка слота считается pro-rata из остатка текущей подписки — цель у неё
        // всегда текущая, выбор не предлагается (см. PaymentFactory.createAddon).
        Payment pending = paymentFactory.createAddon(PaymentDraft.builder()
                .telegramId(user.getId())
                .telegramUsername(user.getUserName())
                .planId(Payment.DEVICE_ADDON_PLAN_ID)
                .amountKopeks(quote.priceKopeks())
                .provider(switch (provider) {
                    case "crypto" -> Payment.Provider.CRYPTOBOT;
                    case "stars" -> Payment.Provider.STARS;
                    default -> Payment.Provider.YOOKASSA;
                })
                .build());

        switch (provider) {
            case "yookassa" -> {
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("telegram_id", user.getId().toString());
                metadata.put("plan_id", Payment.DEVICE_ADDON_PLAN_ID);
                metadata.put("payment_row_id", pending.getId().toString());
                YooKassaClient.PaymentResponse resp;
                try {
                    resp = yooKassaClient.createPayment(
                            quote.priceKopeks(), ADDON_DESCRIPTION,
                            appConfig.getYookassa().getReturnUrl(),
                            metadata, UUID.randomUUID().toString(), false);
                } catch (Exception e) {
                    log.error("YooKassa addon createPayment failed for telegramId={}", user.getId(), e);
                    failAddonPending(chatId, pending);
                    return;
                }
                pending.setYookassaPaymentId(resp.getId());
                paymentRepository.save(pending);
                sendPayButton(chatId, "Перейти к оплате",
                        resp.getConfirmation().getConfirmationUrl(), quote.priceKopeks());
            }
            case "crypto" -> {
                CryptoBotClient.InvoiceResponse resp;
                try {
                    AppConfig.CryptoBotConfig cfg = appConfig.getCryptobot();
                    resp = cryptoBotClient.createInvoice(
                            quote.priceKopeks(), ADDON_DESCRIPTION, pending.getId().toString(),
                            cfg != null && cfg.getAcceptedAssets() != null ? cfg.getAcceptedAssets() : "USDT,TON,BTC,ETH",
                            cfg != null ? cfg.getPaidBtnUrl() : null);
                } catch (Exception e) {
                    log.error("CryptoBot addon createInvoice failed for telegramId={}", user.getId(), e);
                    failAddonPending(chatId, pending);
                    return;
                }
                pending.setCryptoInvoiceId(String.valueOf(resp.getInvoiceId()));
                paymentRepository.save(pending);
                String url = resp.getMiniAppInvoiceUrl();
                if (url == null || url.isBlank()) url = resp.getWebAppInvoiceUrl();
                if (url == null || url.isBlank()) url = resp.getBotInvoiceUrl();
                if (url == null || url.isBlank()) url = resp.getPayUrl();
                sendPayButton(chatId, "Оплатить криптой", url, quote.priceKopeks());
            }
            case "stars" -> {
                SendInvoice invoice = SendInvoice.builder()
                        .chatId(chatId.toString())
                        .title("Дополнительное устройство")
                        .description(ADDON_DESCRIPTION + " (до конца подписки)")
                        .payload(pending.getId().toString())
                        .currency("XTR")
                        .prices(List.of(LabeledPrice.builder()
                                .label("Дополнительное устройство")
                                .amount(Math.toIntExact(quote.priceStars()))
                                .build()))
                        .build();
                try {
                    telegramSender.execute(invoice);
                } catch (TelegramApiException e) {
                    log.error("Stars addon sendInvoice failed for telegramId={}", user.getId(), e);
                    failAddonPending(chatId, pending);
                }
            }
            default -> telegramSender.execute(
                    new SendMessage(chatId.toString(), "Неизвестный способ оплаты."));
        }
    }

    private void failAddonPending(Long chatId, Payment pending) throws TelegramApiException {
        pending.setStatus(Payment.Status.FAILED);
        paymentRepository.save(pending);
        telegramSender.execute(new SendMessage(chatId.toString(),
                "Создать платёж не удалось. Повторите попытку через минуту."));
    }

    private void sendPayButton(Long chatId, String buttonText, String url, long amountKopeks)
            throws TelegramApiException {
        InlineKeyboardButton payBtn = new IkButton();
        payBtn.setText(buttonText);
        payBtn.setUrl(url);
        InlineKeyboardMarkup markup = new IkMarkup();
        markup.setKeyboard(List.of(new InlineKeyboardRow(payBtn)));
        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("Дополнительное устройство — <b>" + Money.rub(amountKopeks) + " ₽</b> "
                + "(до конца текущей подписки).\n\n"
                + "Лимит увеличится автоматически после оплаты.");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /** «1 месяц · 2 устройства» — для заголовков счётов и сообщений. */
    private static String tariffLabel(AppConfig.Plan plan, AppConfig.DeviceTier tier) {
        return tier == null ? plan.getName() : plan.getName() + " · " + tier.getName();
    }

    /** Описание для провайдера (идёт в счёт и чек): описание плана + ступень устройств. */
    private static String describe(AppConfig.Plan plan, AppConfig.DeviceTier tier) {
        return tier == null ? plan.getDescription() : plan.getDescription() + " · " + tier.getName();
    }

    private void answerPreCheckout(String queryId, boolean ok, String errorMsg) throws TelegramApiException {
        AnswerPreCheckoutQuery.AnswerPreCheckoutQueryBuilder b = AnswerPreCheckoutQuery.builder()
                .preCheckoutQueryId(queryId)
                .ok(ok);
        if (!ok && errorMsg != null) {
            b.errorMessage(errorMsg);
        }
        telegramSender.execute(b.build());
    }

    /**
     * Успешная оплата Stars. Telegram шлёт это через update.message.successful_payment.
     * Дальше активируем подписку через PaymentProcessingService — тот же путь что у ЮKassa/крипты,
     * только без чека в МойНалог (см. {@link PaymentProcessingService#handleStarsSucceeded}).
     */
    public void handleSuccessfulPayment(Update update) {
        SuccessfulPayment sp = update.getMessage().getSuccessfulPayment();
        String payload = sp.getInvoicePayload();
        String chargeId = sp.getTelegramPaymentChargeId();
        Integer totalAmount = sp.getTotalAmount();
        long totalStars = totalAmount == null ? 0L : totalAmount;
        log.info("successful_payment received: payload={} chargeId={} currency={} total={}",
                payload, chargeId, sp.getCurrency(), totalStars);
        paymentProcessingService.handleStarsSucceeded(payload, chargeId, totalStars);
    }
}
