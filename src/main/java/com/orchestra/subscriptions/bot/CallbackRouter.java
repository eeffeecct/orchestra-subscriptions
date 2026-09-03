package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.function.Predicate;

/**
 * Роутер inline-кнопок (callback_data): декларативный реестр «матчер строки → действие»,
 * пришедший на смену длинной if/switch-цепочке в {@code SubscriptionBot.handleCallback} (PR-6 распила).
 * Каждый маршрут — это предикат по {@code callback_data} (точное совпадение или префикс с
 * полезной нагрузкой), необязательный owner-гейт и лямбда-действие, делегирующее в нужный
 * хендлер. Неизвестные/устаревшие callback'и — silent ignore.
 * <p>
 * SubscriptionBot инжектит {@code @Lazy CallbackRouter}: роутер тянет хендлеры, часть которых через
 * NotificationService/PaymentProcessingService замыкается на SubscriptionBot — Lazy разрывает цикл.
 */
@Slf4j
@Component
public class CallbackRouter {

    /**
     * Контекст одного callback'а — всё, что нужно действиям. {@code messageId} нужен тем,
     * кто убирает за собой сообщение с кнопкой (экран согласия с офертой).
     */
    private record Ctx(Long chatId, Integer messageId, User user, String data) {
        Long telegramId() {
            return user.getId();
        }

        /** Часть callback_data после префикса (полезная нагрузка, напр. planId). */
        String suffix(String prefix) {
            return data.substring(prefix.length());
        }
    }

    @FunctionalInterface
    private interface Action {
        void run(Ctx ctx) throws TelegramApiException;
    }

    /** Маршрут: чем матчим callback_data, нужен ли владелец, и что делаем. */
    private record Route(Predicate<String> matches, boolean ownerOnly, Action action) {
    }

    private final AppConfig appConfig;
    private final TelegramSender telegramSender;
    /** Обязательная подписка на канал: перехватывает чужие callback'и до маршрутизации. */
    private final ChannelGateHandler channelGate;
    private final List<Route> routes;

    public CallbackRouter(AppConfig appConfig,
                          TelegramSender telegramSender,
                          ChannelGateHandler channelGate,
                          AdminCommandHandler admin,
                          PaymentHandler payment,
                          ScreenHandler screen,
                          AutoRenewHandler autoRenew,
                          OnboardingHandler onboarding,
                          EmailBotHandler emailBot,
                          SubscriptionScreenHandler subscriptions) {
        this.appConfig = appConfig;
        this.telegramSender = telegramSender;
        this.channelGate = channelGate;
        // Порядок как в исходной цепочке: префиксы (динамические) раньше точных совпадений.
        // Пересечений нет (ни один exact не начинается с admin:/plan:/pay:), так что порядок
        // среди них не критичен — оставлен ради читаемости и 1-в-1 соответствия оригиналу.
        this.routes = List.of(
                // Админ — только владельцу, первым делом.
                prefixOwner("admin:", ctx -> admin.handleAdminCallback(ctx.chatId(), ctx.data())),
                // Покупка: число устройств → срок → способ оплаты → конкретный провайдер.
                // Payload у plan:/pay:* — «planId» (legacy-кнопки) или «planId:tierId» (ступень
                // устройств, app.device-tiers) — разбирается planId()/tierId().
                prefix("tier:", ctx -> screen.sendBuyMenuForTier(
                        ctx.chatId(), ctx.telegramId(), ctx.suffix("tier:"))),
                prefix("plan:", ctx -> screen.sendPaymentMethodPicker(
                        ctx.chatId(), ctx.telegramId(), ctx.suffix("plan:"))),
                // Промокод в бот-флоу: ввод следующим сообщением / снятие применённого.
                prefix("promo:ask:", ctx -> screen.askPromo(
                        ctx.chatId(), ctx.telegramId(), ctx.suffix("promo:ask:"))),
                prefix("promo:clear:", ctx -> screen.clearPromo(
                        ctx.chatId(), ctx.telegramId(), ctx.suffix("promo:clear:"))),
                prefix("pay:yookassa:", ctx -> payment.handleYooKassaPayment(
                        ctx.chatId(), ctx.user(),
                        planId(ctx.suffix("pay:yookassa:")), tierId(ctx.suffix("pay:yookassa:")), false)),
                prefix("pay:crypto:", ctx -> payment.handleCryptoPayment(
                        ctx.chatId(), ctx.user(),
                        planId(ctx.suffix("pay:crypto:")), tierId(ctx.suffix("pay:crypto:")))),
                prefix("pay:stars:", ctx -> payment.handleStarsPayment(
                        ctx.chatId(), ctx.user(),
                        planId(ctx.suffix("pay:stars:")), tierId(ctx.suffix("pay:stars:")))),
                // Мои устройства: список + отключить/вернуть (device:revoke:<id> / device:restore:<id>)
                // + смена ссылки подписки («выйти со всех устройств», двухшаговое подтверждение).
                exact("devices", ctx -> screen.sendDevices(ctx.chatId(), ctx.telegramId())),
                prefix("device:revoke:", ctx -> screen.handleDeviceAction(
                        ctx.chatId(), ctx.telegramId(), "revoke", ctx.suffix("device:revoke:"))),
                prefix("device:restore:", ctx -> screen.handleDeviceAction(
                        ctx.chatId(), ctx.telegramId(), "restore", ctx.suffix("device:restore:"))),
                prefix("device:name:", ctx -> screen.askDeviceName(
                        ctx.chatId(), ctx.telegramId(), ctx.suffix("device:name:"))),
                exact("devlink:rotate", ctx -> screen.sendRotateLinkConfirm(ctx.chatId())),
                exact("devlink:rotate:confirm", ctx -> screen.handleRotateLink(ctx.chatId(), ctx.telegramId())),
                // Докупка «+1 устройство»: пикер способов + запуск оплаты у провайдера.
                exact("addon:open", ctx -> screen.sendAddonPicker(ctx.chatId(), ctx.telegramId())),
                exact("addon:yookassa", ctx -> payment.handleAddonPayment(ctx.chatId(), ctx.user(), "yookassa")),
                exact("addon:crypto", ctx -> payment.handleAddonPayment(ctx.chatId(), ctx.user(), "crypto")),
                exact("addon:stars", ctx -> payment.handleAddonPayment(ctx.chatId(), ctx.user(), "stars")),
                // Автопродление.
                exact("autorenew:toggle", ctx -> autoRenew.handleAutoRenewToggle(ctx.chatId(), ctx.telegramId())),
                exact("autorenew:unbind", ctx -> autoRenew.sendAutoRenewUnbindConfirm(ctx.chatId())),
                exact("autorenew:unbind_confirm", ctx -> autoRenew.handleAutoRenewUnbindConfirmed(ctx.chatId(), ctx.telegramId())),
                exact("autorenew:unbind_cancel", ctx -> autoRenew.sendAutoRenewInfo(ctx.chatId(), ctx.telegramId())),
                exact("autorenew:open", ctx -> autoRenew.sendAutoRenewInfo(ctx.chatId(), ctx.telegramId())),
                // Мои подписки: список, экран подтверждения, выполнение переключения.
                // ⚠️ Маршруты только С ГЛАГОЛОМ: формы «sub:<id>» не существует намеренно —
                // иначе одна опечатка в кнопке превратила бы показ экрана в переключение.
                exact("sub:list", ctx -> subscriptions.sendSubscriptionList(ctx.chatId(), ctx.telegramId())),
                prefix("sub:switch:", ctx -> subscriptions.sendSwitchConfirm(
                        ctx.chatId(), ctx.telegramId(), ctx.suffix("sub:switch:"))),
                prefix("sub:go:", ctx -> subscriptions.handleSwitch(
                        ctx.chatId(), ctx.telegramId(), ctx.suffix("sub:go:"))),
                // Согласие с офертой на первом запуске: экран сам себя убирает и пускает в бот.
                exact("offer:accept", ctx -> onboarding.acceptOffer(
                        ctx.chatId(), ctx.telegramId(), ctx.messageId())),
                // Экраны и нижнее меню.
                exact("trial", ctx -> screen.handleTrial(ctx.chatId(), ctx.user())),
                exact("status", ctx -> screen.sendStatus(ctx.chatId(), ctx.telegramId())),
                exact("how_to", ctx -> screen.sendHelp(ctx.chatId())),
                exact("referral", ctx -> screen.sendReferralInfo(ctx.chatId(), ctx.telegramId())),
                exact("cabinet", ctx -> screen.sendCabinet(ctx.chatId(), ctx.telegramId())),
                exact("history", ctx -> screen.sendPaymentHistory(ctx.chatId(), ctx.telegramId())),
                exact("menu:start", ctx -> onboarding.sendWelcome(ctx.chatId(), ctx.telegramId())),
                exact("menu:buy", ctx -> screen.sendBuyMenu(ctx.chatId(), ctx.telegramId())),
                exact("menu:help", ctx -> screen.sendSupport(ctx.chatId())),
                // Паритет с Mini App: статус серверов, email-настройки.
                exact("servers", ctx -> screen.sendServers(ctx.chatId())),
                exact("email:open", ctx -> emailBot.sendEmailScreen(ctx.chatId(), ctx.telegramId())),
                exact("email:ask", ctx -> emailBot.askEmail(ctx.chatId(), ctx.telegramId())),
                exact("email:link", ctx -> emailBot.sendLoginLink(ctx.chatId(), ctx.telegramId())),
                exact("email:delete", ctx -> emailBot.confirmDelete(ctx.chatId())),
                exact("email:delete:confirm", ctx -> emailBot.deleteConfirmed(ctx.chatId(), ctx.telegramId()))
        );
    }

    /**
     * Гасит loader на кнопке и выполняет первое подходящее действие из таблицы. Неизвестные
     * callback'и молча игнорируются (юзер со старым чатом не получит ошибку; через /start
     * получит свежий welcome с актуальными кнопками).
     */
    public void route(Update update) throws TelegramApiException {
        // Обязательная подписка на канал — ДО ack: гейту нужно ответить на query своим
        // текстом (всплывашкой), а ack ниже гасит loader молча, и второй ответ на тот же
        // query Telegram уже не примет. Владельца и неизвестные callback'и не задевает.
        if (channelGate.interceptCallback(update)) {
            return;
        }

        // Гасим loader СРАЗУ — иначе Telegram крутит «обрабатывается» до 30 сек, даже если
        // мы давно отправили ответ. Один ack на все маршруты.
        ackCallbackSilently(update.getCallbackQuery().getId());

        String data = update.getCallbackQuery().getData();
        Ctx ctx = new Ctx(
                update.getCallbackQuery().getMessage().getChatId(),
                update.getCallbackQuery().getMessage().getMessageId(),
                update.getCallbackQuery().getFrom(),
                data);

        for (Route r : routes) {
            if (!r.matches().test(data)) {
                continue;
            }
            // Owner-гейт (admin): не-владельцу молча игнорируем — не палим, что команда есть.
            if (r.ownerOnly() && !isOwner(ctx.telegramId())) {
                return;
            }
            r.action().run(ctx);
            return;
        }
        // Совпадений нет — silent ignore.
    }

    /** «planId» или «planId:tierId» → planId. */
    private static String planId(String payload) {
        int sep = payload.indexOf(':');
        return sep >= 0 ? payload.substring(0, sep) : payload;
    }

    /** «planId» или «planId:tierId» → tierId (null для legacy-кнопок без ступени). */
    private static String tierId(String payload) {
        int sep = payload.indexOf(':');
        return sep >= 0 ? payload.substring(sep + 1) : null;
    }

    private static Route exact(String value, Action action) {
        return new Route(value::equals, false, action);
    }

    private static Route prefix(String value, Action action) {
        return new Route(s -> s.startsWith(value), false, action);
    }

    private static Route prefixOwner(String value, Action action) {
        return new Route(s -> s.startsWith(value), true, action);
    }

    /**
     * Гасит loader на inline-кнопке, не показывая текст/alert. Никогда не бросает наружу —
     * ack чисто косметический, не должен валить основной callback-flow.
     */
    private void ackCallbackSilently(String callbackQueryId) {
        try {
            telegramSender.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (Exception e) {
            log.debug("Failed to ack callback {}: {}", callbackQueryId, e.getMessage());
        }
    }

    private boolean isOwner(Long userId) {
        Long ownerId = appConfig.getTelegram().getOwnerId();
        return ownerId != null && ownerId.equals(userId);
    }
}
