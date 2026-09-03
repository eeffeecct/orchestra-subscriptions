package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.service.AutoRenewInfoService;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.MultiSubscriptionPolicy;
import com.orchestra.subscriptions.service.SubscriptionLookupService;
import com.orchestra.subscriptions.service.SubscriptionView;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сборка ответа {@code GET /api/me} — состояние человека в том виде, в каком его рендерит
 * Mini App.
 * <p>
 * Вынесено из контроллера, потому что этот же payload обязан отдавать
 * {@code POST /api/subscriptions/{id}/activate}: переключение меняет срок, ссылку и лимит
 * устройств разом, и фронт должен перерисоваться из ОДНОГО согласованного снимка. Если бы
 * активация отдавала свой урезанный ответ, а полное состояние фронт дочитывал вторым
 * запросом, между ними существовало бы окно, в котором на экране новая подписка со старой
 * ссылкой — то есть человек копирует ссылку, которая уже не работает.
 * <p>
 * <b>Плоские поля верхнего уровня менять нельзя.</b> Mini App кешируется у людей на
 * устройствах, и фронт прошлой версии продолжает читать именно их. Новое приезжает
 * ДОПОЛНИТЕЛЬНЫМИ ключами ({@code subscriptions}, {@code multiSub}), которые старый фронт
 * просто не заметит.
 */
@Component
@RequiredArgsConstructor
public class MeViewAssembler {

    private final PaymentRepository paymentRepository;
    private final SubscriptionLookupService subscriptionLookup;
    private final DeviceTrackingService deviceTrackingService;
    private final MultiSubscriptionPolicy policy;
    /** Что и когда спишется — общий счёт с ботом, чтобы экраны не отвечали по-разному. */
    private final AutoRenewInfoService autoRenewInfo;
    /** Список подписок — общий с web-ЛК, чтобы два интерфейса не разошлись в формулировках. */
    private final SubscriptionListView subscriptionListView;
    private final AppConfig appConfig;

    /**
     * @param user {@code null} — человека в базе нет (не завёлся триал); тогда ответ
     *             ограничивается {@code hasSubscription: false} и конфигом
     */
    public Map<String, Object> assemble(long telegramId, Customer user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("telegramId", telegramId);

        if (user == null) {
            result.put("hasSubscription", false);
        } else {
            // isTrial определяется по факту ЗАВЕРШЁННОЙ оплаты — если юзер хоть раз
            // проходил SUCCEEDED payment, он уже платный (даже если provider_username
            // остался "trial_*" после апгрейда). Старая проверка по префиксу давала
            // ложный "пробный период" для юзеров которые начинали с триала и купили.
            long paidCount = paymentRepository.countByTelegramIdAndStatus(
                    user.getTelegramId(), Payment.Status.SUCCEEDED);
            boolean isTrial = paidCount == 0;

            // Факты ПОДПИСКИ (срок, статус, ссылка) читаем через фасад — источник за флагом.
            // Факты ЧЕЛОВЕКА (почта, привязанная карта) остаются на юзере: они и принадлежат
            // человеку, а не подписке, и после появления нескольких подписок не задвоятся.
            final Customer owner = user;
            SubscriptionView sub = subscriptionLookup
                    .currentFor(owner.getTelegramId())
                    .orElseGet(() -> SubscriptionView.ofUser(owner));

            long expiresAtEpoch = sub.expiresAt() == null ? 0
                    : sub.expiresAt().toEpochSecond(ZoneOffset.UTC);

            result.put("hasSubscription", true);
            result.put("status", sub.status().toString());
            result.put("isTrial", isTrial);
            result.put("expiresAtEpoch", expiresAtEpoch);
            result.put("autorenewEnabled", user.isAutorenewEnabled());
            result.put("autorenewAvailable", user.getAutorenewMethodId() != null);
            result.put("subscriptionLink", orEmpty(sub.subscriptionLink()));
            // Ссылка, которую человек вставляет в клиентское приложение: она ведёт на НАШ
            // эндпоинт выдачи (AccessDeliveryController), а не на провайдера напрямую —
            // поэтому учёт устройств, срок и заморозка применяются на каждом обновлении.
            result.put("accessLink", accessLink(sub.subscriptionLink()));
            // Email: фронт по этим полям решает показывать ли capture-баннер на Home
            // (email пустой) и какой статус рендерить в email-settings (verified или нет).
            result.put("email", orEmpty(user.getEmail()));
            result.put("emailVerified", user.getEmailVerifiedAt() != null);
            // Устройства (HWID-учёт): лимит тарифа (0 = не задан/безлимит) и занятые слоты —
            // для карточки «Устройства» в профиле; полный список — GET /api/devices.
            //
            // Считаем по ТЕКУЩЕЙ ПОДПИСКЕ, а не по человеку: устройства принадлежат подписке
            // с этапа 5.3, и у человека с двумя подписками счёт по человеку сложил бы вместе
            // парки обеих — экран показал бы «5 из 2» и предложил освободить слоты, которых
            // на этой подписке никто не занимал. У человека с одной подпиской оба счёта
            // совпадают, поэтому для сегодняшнего прода это не изменение.
            int deviceLimit = sub.deviceLimit() != null ? sub.deviceLimit() : 0;
            result.put("deviceLimit", deviceLimit);
            result.put("devicesUsed", deviceTrackingService.usedSlots(sub));

            // --- ниже только ДОПОЛНИТЕЛЬНЫЕ ключи этапа 5 ---
            result.put("subscriptions", subscriptionListView.forUser(owner));
            result.put("multiSub", multiSub(telegramId));
            // Что и когда спишется. До этого блока Mini App знал про автопродление ровно одно
            // слово — «включено»: ни суммы, ни даты, ни того, какую подписку продлят. Бот всё
            // это показывал, и два экрана про одну карту отвечали по-разному.
            result.put("autorenewInfo", autorenewInfo(owner));
        }

        // Конфиг — URL-ы для Setup / Support экранов. Лучше отдать одним запросом,
        // чем городить отдельный /api/config.
        // Используем Map с Object value чтобы класть и String, и Boolean (recurrentEnabled).
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("guideUrl", orEmpty(appConfig.getTelegram().getGuideUrl()));
        config.put("offerUrl", orEmpty(appConfig.getTelegram().getOfferUrl()));
        config.put("botUsername", orEmpty(appConfig.getTelegram().getBotUsername()));
        // News channel — фронт показывает кнопку «Канал с новостями» только если URL задан.
        config.put("newsChannelUrl", orEmpty(appConfig.getTelegram().getNewsChannelUrl()));
        // recurrentEnabled — флаг YOOKASSA_RECURRENT_ENABLED. Фронт по этому флагу
        // показывает/скрывает чекбокс «С автопродлением» в payment drawer.
        // Когда флаг на проде flipнется в true → рестарт бота → чекбокс появится
        // у юзеров без передеплоя фронта.
        config.put("recurrentEnabled", appConfig.getYookassa().isRecurrentEnabled());
        result.put("config", config);

        return result;
    }

    /**
     * Снимок автопродления для экрана: те же цифры, что показывает бот в {@code /autorenew}.
     * <p>
     * {@code willCharge} отдаётся отдельным полем, а не выводится фронтом из {@code enabled}:
     * списания не будет и при отвязанной карте, и при выключенных рекуррентах у магазина, а
     * фронт про второе не знает вовсе — и написал бы «продлится автоматически» там, где не
     * произойдёт ни одной попытки.
     */
    private Map<String, Object> autorenewInfo(Customer user) {
        AutoRenewInfoService.Info info = autoRenewInfo.forUser(user);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("enabled", info.enabled());
        block.put("cardBound", info.cardBound());
        block.put("willCharge", info.willCharge());
        block.put("planName", orEmpty(info.planName()));
        block.put("planGone", info.planGone());
        block.put("tierName", orEmpty(info.tierName()));
        block.put("amountRub", info.amountRub());
        block.put("nextChargeEpoch", info.nextChargeAt() == null ? 0
                : info.nextChargeAt().toEpochSecond(ZoneOffset.UTC));
        block.put("targetSeq", info.targetSeq() == null ? 0 : info.targetSeq());
        block.put("lastFailureEpoch", info.lastFailureAt() == null ? 0
                : info.lastFailureAt().toEpochSecond(ZoneOffset.UTC));
        return block;
    }

    /** Что фронту разрешено предлагать: завести новую подписку и переключаться. */
    private Map<String, Object> multiSub(long telegramId) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("canOpenNew", policy.canOpenNew(telegramId));
        block.put("maxPerUser", policy.maxPerUser());
        block.put("switchingEnabled", policy.switchingEnabled());
        return block;
    }

    /**
     * клиентское приложение-ссылка со встроенным split-routing'ом. Сырую AccessProvider-ссылку человеку не отдаём
     * нигде (решение владельца 2026-07-15) — кроме плоского {@code subscriptionLink},
     * который остаётся ради старого закешированного фронта.
     */
    private String accessLink(String providerLink) {
        return SubscriptionLinks.accessLink(providerLink, appConfig.getTelegram().getPublicBaseUrl());
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }
}
