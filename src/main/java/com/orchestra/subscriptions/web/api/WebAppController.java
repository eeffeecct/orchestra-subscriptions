package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.bot.SubscriptionBot;
import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.util.PromoMessages;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.service.AutoRenewService;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.EmailSettingsService;
import com.orchestra.subscriptions.util.DeviceNames;
import com.orchestra.subscriptions.service.PendingGrantService;
import com.orchestra.subscriptions.service.PromoCodeService;
import com.orchestra.subscriptions.service.ReferralService;
import com.orchestra.subscriptions.service.ServerStatusService;
import com.orchestra.subscriptions.service.AccessService;
import com.orchestra.subscriptions.service.payment.PaymentDraft;
import com.orchestra.subscriptions.service.payment.CheckoutRejectedException;
import com.orchestra.subscriptions.service.payment.PaymentFactory;
import com.orchestra.subscriptions.service.payment.TargetSpec;
import com.orchestra.subscriptions.util.StarsAmount;
import org.springframework.context.annotation.Lazy;
import org.telegram.telegrambots.meta.api.methods.invoices.CreateInvoiceLink;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API для Telegram Mini App. Все эндпоинты требуют заголовок
 * {@code X-Telegram-Init-Data} с оригинальной строкой {@code window.Telegram.WebApp.initData};
 * подпись проверяется {@link TelegramInitDataValidator}, без валидного initData — 401.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WebAppController {

    private final TelegramInitDataValidator validator;
    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    /** Строку платежа заводит только фабрика — она же пишет цель (в какую подписку зачислять). */
    private final PaymentFactory paymentFactory;
    private final YooKassaClient yooKassaClient;
    private final CryptoBotClient cryptoBotClient;
    private final AccessService accessService;
    private final ReferralService referralService;
    private final AutoRenewService autoRenewService;
    private final PromoCodeService promoCodeService;
    private final AppConfig appConfig;
    private final PendingGrantService pendingGrantService;
    private final ServerStatusService serverStatusService;
    /** Факты подписки читаем через фасад: источник (старая таблица или новая) за флагом. */
    private final com.orchestra.subscriptions.service.SubscriptionLookupService subscriptionLookup;
    private final RequestRateLimiter requestRateLimiter;
    private final DeviceTrackingService deviceTrackingService;
    private final EmailSettingsService emailSettingsService;
    /** Сборщик payload'а {@code /me} — общий с эндпоинтом активации подписки. */
    private final MeViewAssembler meViewAssembler;
    // @Lazy — SubscriptionBot косвенно тянет за собой этот же WebAppController через Spring MVC
    // context; без Lazy получаем circular dependency на старте. Lazy-proxy разрывает цикл.
    @Lazy private final SubscriptionBot subscriptionBot;

    /** Статус подписки текущего юзера + конфиг (URL-ы оферты/FAQ/username бота для openLink). */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {
        TelegramInitDataValidator.TelegramUser tgUser;
        try {
            tgUser = validator.validateAndExtractUser(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            log.warn("Mini App /api/me: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        long telegramId = tgUser.id();

        // Pending grant: если юзер выиграл в конкурсе и заходит в Mini App минуя
        // /start (например, через прямой deep-link t.me/<bot>/orchestra) — применяем
        // приз здесь. applyIfAny идемпотентен и сам шлёт DM с поздравлением; если
        // грантов нет — возвращает 0 и идём дальше в обычный auto-trial поток.
        try {
            pendingGrantService.applyIfAny(telegramId, tgUser.username());
        } catch (Exception e) {
            log.error("Mini App: applyIfAny failed for telegramId={}", telegramId, e);
        }

        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);

        // Первое открытие Mini App — автоматически выдаём триал. Это упрощает
        // onboarding: юзер сразу видит ссылку в профиле, не надо в бот за /trial.
        // existsByTelegramId внутри createTrialAccess защищает от повторной выдачи —
        // если pending grant выше уже создал юзера через activatePaidSubscription,
        // эта ветка выкинет IllegalStateException и тихо пропустит auto-trial.
        if (user == null) {
            try {
                user = accessService.createTrialAccess(telegramId, tgUser.username());
                log.info("Mini App: auto-trial created for telegramId={}", telegramId);
            } catch (IllegalStateException e) {
                // Триал уже был использован — юзер есть, но findByTelegramId вернул null
                // из-за какой-то рассинхронизации. Маловероятно; падаем в hasSubscription=false.
                log.warn("Mini App: auto-trial skipped for telegramId={}: {}", telegramId, e.getMessage());
            } catch (Exception e) {
                // AccessProvider упал или сеть — не роняем весь Mini App, показываем профиль без подписки.
                log.error("Mini App: auto-trial failed for telegramId={}", telegramId, e);
            }
        }

        // Сборка ответа — в общем ассемблере: тот же снимок отдаёт активация подписки,
        // и расходиться этим двум ответам нельзя (см. MeViewAssembler).
        return ResponseEntity.ok(meViewAssembler.assemble(telegramId, user));
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    // =====================================================================
    //  Email — opt-in capture для magic-link авторизации и резервной копии конфига.
    //  Логика вынесена в EmailSettingsService (общая с чат-ботом — флоу «✉️ Email»
    //  в Кабинете для юзеров, у которых не открывается Mini App); здесь — только
    //  HTTP-маппинг кодов ошибок в статусы. Коды и тексты сохранены 1-в-1.
    // =====================================================================

    /**
     * DTO для {@code POST /api/me/email}.
     * {@code consent} — явный opt-in юзера на обработку email (152-ФЗ ст.9). Требуется
     * только при сохранении нового или смене существующего email; для no-op кейса
     * (тот же адрес) согласие не перепроверяется.
     */
    public record UpdateEmailRequest(String email, Boolean consent) {}

    /** HTTP-статус для машинного кода ошибки {@link EmailSettingsService.Outcome}. */
    private static int emailErrorStatus(String error) {
        return switch (error) {
            case "user_not_found" -> 404;
            case "email_taken" -> 409;
            case "rate_limited" -> 429;
            case "email_delivery_failed" -> 502;
            default -> 400; // invalid_format, consent_required, no_email
        };
    }

    /**
     * Сохраняет/обновляет email текущего юзера. Email хранится в lowercase для
     * консистентности UNIQUE-конфликтов (Foo@Bar.com и foo@bar.com — один адрес).
     * При смене email на новый — {@code email_verified_at} сбрасывается: новый адрес
     * требует повторного подтверждения — юзер подтверждает его входом по magic-link.
     * <p>
     * Требует {@code consent=true} в теле запроса (152-ФЗ ст.9: согласие на обработку
     * ПДн должно быть конкретным, информированным, сознательным). При успехе ставит
     * {@code email_consent_at = now()} — audit-trail на случай спора.
     * <p>
     * Ответ: {@code {email, emailVerified}} — фронт сразу обновляет UI без повторного /api/me.
     * Ошибки: 400 invalid_format / consent_required, 404 user_not_found, 409 email_taken.
     */
    @PostMapping("/me/email")
    public ResponseEntity<?> updateEmail(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestBody UpdateEmailRequest request) {

        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        boolean consent = request != null && Boolean.TRUE.equals(request.consent());
        EmailSettingsService.Outcome outcome = emailSettingsService.updateEmail(
                telegramId, request == null ? null : request.email(), consent);
        if (!outcome.ok()) {
            return ResponseEntity.status(emailErrorStatus(outcome.error()))
                    .body(errorBody(outcome));
        }
        return ResponseEntity.ok(Map.of(
                "email", outcome.email(),
                "emailVerified", outcome.verified()));
    }

    /** Тело ошибки email-операций: {error[, message]} — как отдавали инлайновые проверки. */
    private static Map<String, Object> errorBody(EmailSettingsService.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", outcome.error());
        if (outcome.message() != null) {
            body.put("message", outcome.message());
        }
        return body;
    }

    /**
     * Удаляет email пользователя. 152-ФЗ требует возможность отозвать согласие на
     * обработку ПДн в любой момент — этот эндпоинт реализует «удалить email».
     * Не сохраняем soft-delete (зануляем оба поля), потому что хранить «удалённый»
     * адрес без активного согласия — нарушение того же 152-ФЗ.
     */
    @DeleteMapping("/me/email")
    public ResponseEntity<?> deleteEmail(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {

        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        EmailSettingsService.Outcome outcome = emailSettingsService.deleteEmail(telegramId);
        if (!outcome.ok()) {
            return ResponseEntity.status(emailErrorStatus(outcome.error()))
                    .body(errorBody(outcome));
        }
        return ResponseEntity.ok(Map.of("email", "", "emailVerified", false));
    }

    /**
     * Отправляет magic-link на email юзера. Используется из Mini App: «Войти в кабинет
     * через браузер». Юзер должен иметь сохранённый email; rate-limit защищает от
     * спама (см. {@link MagicLinkRateLimiter}).
     * <p>
     * Email с сохранённого адреса берём из БД, не из request — иначе юзер мог бы
     * отправить magic-link на чужой адрес и получить ссылку на свой кабинет.
     * <p>
     * Ошибки: 400 no_email, 429 rate_limited (с retryAfterSeconds), 404 user_not_found.
     */
    @PostMapping("/me/email/send-link")
    public ResponseEntity<?> sendMagicLink(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {

        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        EmailSettingsService.Outcome outcome = emailSettingsService.sendLoginLink(telegramId);
        if (!outcome.ok()) {
            Map<String, Object> body = errorBody(outcome);
            if ("rate_limited".equals(outcome.error())) {
                body.put("reason", outcome.rateLimitReason());
                body.put("retryAfterSeconds", outcome.retryAfterSeconds());
            }
            return ResponseEntity.status(emailErrorStatus(outcome.error())).body(body);
        }
        return ResponseEntity.ok(Map.of(
                "message", outcome.message(),
                "email", outcome.email()));
    }

    /**
     * Строит URL подписки с встроенным split-routing'ом из обычной AccessProvider-подписки.
     * Извлекает токен из {@code https://<host>:8443/sub/<token>} и подставляет в
     * {@code <publicBaseUrl>/sub/<token>}. С 2026-05-25 для новых юзеров это
     * на текущем домене; у давних подписчиков в базе лежит полный URL со старого домена.
     *
     * @return пустую строку если входной link null/пустой/без /sub/ — фронт должен скрывать
     *         кнопку «обход РФ-сервисов» в этом случае.
     */
    private String buildAccessLink(String providerLink) {
        return SubscriptionLinks.accessLink(providerLink, appConfig.getTelegram().getPublicBaseUrl());
    }

    /**
     * Витринный прайс Mini App: планы + ступени устройств из application.yml
     * ({@code app.plans} / {@code app.device-tiers}) — единственный источник цен для фронта
     * (хардкод в script.js — только фоллбэк на случай недоступного API). Ответ чисто
     * витринный: цену при оплате сервер всегда пересчитывает сам в /api/payments/*.
     * Заменил прежний GET /tariffs (массив планов) — фронт не вызывал его с 2026-04.
     */
    @GetMapping("/pricing")
    public ResponseEntity<?> pricing(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {
        try {
            validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        List<Map<String, Object>> plans = appConfig.getPlans().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "title", p.getName(),
                        "days", p.getDays(),
                        "priceKopeks", p.getPriceKopeks(),
                        "popular", p.isPopular()
                ))
                .toList();

        List<Map<String, Object>> tiers = appConfig.deviceTiersEnabled()
                ? appConfig.getDeviceTiers().stream()
                        .map(t -> Map.<String, Object>of(
                                "id", t.getId(),
                                "name", t.getName(),
                                "devices", t.getDevices(),
                                "multiplier", t.getPriceMultiplier(),
                                "popular", t.isPopular(),
                                "family", t.isFamily()
                        ))
                        .toList()
                : List.of();

        return ResponseEntity.ok(Map.of("plans", plans, "deviceTiers", tiers));
    }

    // =====================================================================
    //  Мои устройства (HWID-учёт с /sub/<token>)
    // =====================================================================

    /** Список устройств юзера для экрана «Мои устройства» + лимит/занятые слоты. */
    @GetMapping("/devices")
    public ResponseEntity<?> devices(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {
        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        Customer owner = userRepository.findByTelegramId(telegramId).orElse(null);
        // Экран показывает устройства ТЕКУЩЕЙ подписки, а не все устройства человека:
        // устройства принадлежат подписке с этапа 5.3, и у человека с двумя подписками
        // общий список смешал бы чужие слоты с занятыми — «занято 5 из 2» на подписке,
        // где никто ничего не занимал. Пока подписка одна, оба среза совпадают.
        com.orchestra.subscriptions.service.SubscriptionView deviceScope = subscriptionLookup.currentFor(telegramId)
                .orElseGet(() -> owner != null
                        ? com.orchestra.subscriptions.service.SubscriptionView.ofUser(owner)
                        : new com.orchestra.subscriptions.service.SubscriptionView(telegramId, null, null, null,
                                null, null, Customer.Status.DISABLED, null, null, null, false));

        // Свежие сверху; кап 50 — защита от раздутого ответа (хранение и так ограничено
        // потолком DeviceTrackingService.MAX_TRACKED_DEVICES, кап здесь — второй пояс).
        List<UserDevice> deviceRows = deviceTrackingService.listDevices(deviceScope).stream()
                .sorted(java.util.Comparator.comparing(UserDevice::getLastSeenAt).reversed())
                .limit(50)
                .toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserDevice d : deviceRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("label", DeviceNames.label(d.getDeviceModel(), d.getDeviceOs(), d.getOsVersion()));
            // Пользовательское имя («телефон мамы»): фронт показывает его заголовком,
            // железное label — подписью. Пусто = не задано.
            item.put("customName", orEmpty(d.getCustomName()));
            item.put("os", orEmpty(d.getDeviceOs()));
            item.put("status", d.getStatus().toString());
            item.put("firstSeenEpoch", d.getFirstSeenAt().toEpochSecond(ZoneOffset.UTC));
            item.put("lastSeenEpoch", d.getLastSeenAt().toEpochSecond(ZoneOffset.UTC));
            items.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("devices", items);
        body.put("deviceLimit", deviceScope.deviceLimit() != null ? deviceScope.deviceLimit() : 0);
        body.put("devicesUsed", deviceTrackingService.usedSlots(deviceScope));

        // Докупка «+1 устройство»: фронт показывает кнопку с ценой, если доступна.
        Map<String, Object> addon = new LinkedHashMap<>();
        // Цена слота считается pro-rata из остатка ТЕКУЩЕЙ подписки — поэтому квоту
        // по-прежнему спрашиваем по человеку: докупка целится только в текущую.
        var quote = owner != null
                ? deviceTrackingService.addonQuote(owner) : java.util.Optional.<DeviceTrackingService.AddonQuote>empty();
        addon.put("available", quote.isPresent());
        quote.ifPresent(q -> {
            addon.put("priceKopeks", q.priceKopeks());
            addon.put("priceStars", q.priceStars());
            addon.put("remainingDays", q.remainingDays());
        });
        body.put("addon", addon);
        return ResponseEntity.ok(body);
    }

    /**
     * Покупка «+1 устройство» из Mini App. Body: {@code {"provider": "yookassa|cryptobot|stars"}}.
     * Возвращает {@code {provider, paymentId, url}} — фронт открывает url способом провайдера
     * (openLink / openTelegramLink / openInvoice). Webhook инкрементит device_limit —
     * см. {@code PaymentProcessingService.completeDeviceAddon}.
     */
    @PostMapping("/devices/addon")
    public ResponseEntity<?> buyDeviceAddon(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestBody(required = false) Map<String, Object> body) {

        TelegramInitDataValidator.TelegramUser tgUser;
        try {
            tgUser = validator.validateAndExtractUser(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        String provider = body != null && body.get("provider") != null
                ? body.get("provider").toString() : "yookassa";

        Customer user = userRepository.findByTelegramId(tgUser.id()).orElse(null);
        var quoteOpt = user != null
                ? deviceTrackingService.addonQuote(user) : java.util.Optional.<DeviceTrackingService.AddonQuote>empty();
        if (quoteOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "addon_unavailable",
                    "message", "Докупка устройства недоступна."));
        }
        var quote = quoteOpt.get();
        // Stars-гвард ДО создания PENDING — не плодим orphan-строки.
        if ("stars".equals(provider) && quote.priceStars() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "stars disabled"));
        }

        // Цель докупки всегда текущая подписка: котировка выше посчитана из её остатка.
        Payment pending = paymentFactory.createAddon(PaymentDraft.builder()
                .telegramId(tgUser.id())
                .telegramUsername(tgUser.username())
                .planId(Payment.DEVICE_ADDON_PLAN_ID)
                .amountKopeks(quote.priceKopeks())
                .provider(switch (provider) {
                    case "cryptobot" -> Payment.Provider.CRYPTOBOT;
                    case "stars" -> Payment.Provider.STARS;
                    default -> Payment.Provider.YOOKASSA;
                })
                .build());

        String addonDescription = "Orchestra — дополнительное устройство";
        try {
            switch (provider) {
                case "cryptobot" -> {
                    CryptoBotClient.InvoiceResponse resp = cryptoBotClient.createInvoice(
                            quote.priceKopeks(), addonDescription, pending.getId().toString(),
                            appConfig.getCryptobot().getAcceptedAssets(),
                            appConfig.getCryptobot().getPaidBtnUrl());
                    pending.setCryptoInvoiceId(String.valueOf(resp.getInvoiceId()));
                    paymentRepository.save(pending);
                    String url = resp.getMiniAppInvoiceUrl();
                    if (url == null || url.isBlank()) url = resp.getWebAppInvoiceUrl();
                    if (url == null || url.isBlank()) url = resp.getBotInvoiceUrl();
                    if (url == null || url.isBlank()) url = resp.getPayUrl();
                    return ResponseEntity.ok(Map.of(
                            "provider", "cryptobot",
                            "paymentId", pending.getId().toString(),
                            "url", orEmpty(url)));
                }
                case "stars" -> {
                    CreateInvoiceLink method = CreateInvoiceLink.builder()
                            .title("Дополнительное устройство")
                            .description(addonDescription + " (до конца подписки)")
                            .payload(pending.getId().toString())
                            .currency("XTR")
                            .prices(List.of(LabeledPrice.builder()
                                    .label("Дополнительное устройство")
                                    .amount(Math.toIntExact(quote.priceStars()))
                                    .build()))
                            .build();
                    String invoiceUrl = subscriptionBot.execute(method);
                    return ResponseEntity.ok(Map.of(
                            "provider", "stars",
                            "paymentId", pending.getId().toString(),
                            "url", orEmpty(invoiceUrl)));
                }
                default -> {
                    Map<String, String> metadata = new LinkedHashMap<>();
                    metadata.put("telegram_id", String.valueOf(tgUser.id()));
                    metadata.put("plan_id", Payment.DEVICE_ADDON_PLAN_ID);
                    metadata.put("payment_row_id", pending.getId().toString());
                    YooKassaClient.PaymentResponse resp = yooKassaClient.createPayment(
                            quote.priceKopeks(), addonDescription,
                            appConfig.getYookassa().getReturnUrl(),
                            metadata, UUID.randomUUID().toString(), false);
                    pending.setYookassaPaymentId(resp.getId());
                    paymentRepository.save(pending);
                    return ResponseEntity.ok(Map.of(
                            "provider", "yookassa",
                            "paymentId", pending.getId().toString(),
                            "url", orEmpty(resp.getConfirmation().getConfirmationUrl())));
                }
            }
        } catch (Exception e) {
            log.error("Mini App: device addon payment failed for telegramId={} provider={}",
                    tgUser.id(), provider, e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            return ResponseEntity.status(502).body(Map.of("error", "payment_provider_error"));
        }
    }

    /**
     * «Отключить» устройство: перестаёт получать серверы при следующем обновлении подписки,
     * слот освобождается. Принадлежность устройства юзеру проверяется в сервисе.
     */
    @PostMapping("/devices/{id}/revoke")
    public ResponseEntity<?> revokeDevice(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @org.springframework.web.bind.annotation.PathVariable("id") long id) {
        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        boolean ok = deviceTrackingService.revoke(telegramId, id);
        if (!ok) {
            return ResponseEntity.status(404).body(Map.of("error", "device not found"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** «Вернуть» отключённое устройство. {@code activated=false} — свободных слотов нет (BLOCKED). */
    @PostMapping("/devices/{id}/restore")
    public ResponseEntity<?> restoreDevice(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @org.springframework.web.bind.annotation.PathVariable("id") long id) {
        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        boolean activated = deviceTrackingService.restore(telegramId, id);
        return ResponseEntity.ok(Map.of("ok", true, "activated", activated));
    }

    /**
     * Переименовать устройство («телефон мамы»). Body: {@code {"name": "..."}};
     * пустое имя очищает — обратно к железному названию.
     */
    @PostMapping("/devices/{id}/name")
    public ResponseEntity<?> renameDevice(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @org.springframework.web.bind.annotation.PathVariable("id") long id,
            @RequestBody(required = false) Map<String, Object> body) {
        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        String name = body != null && body.get("name") != null ? body.get("name").toString() : null;
        boolean ok = deviceTrackingService.rename(telegramId, id, name);
        if (!ok) {
            return ResponseEntity.status(404).body(Map.of("error", "device not found"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * «Выйти со всех устройств»: перевыпуск подписочного токена в AccessProvider. Старая ссылка
     * сразу мертва, список устройств очищается. Возвращает новые ссылки — фронт обновляет
     * профиль и показывает alert. Rate-limit 3/час: операция дёргает AccessProvider API и
     * инвалидирует доступ — щёлкать её как тумблер не надо.
     */
    @PostMapping("/devices/rotate-link")
    public ResponseEntity<?> rotateSubscriptionLink(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {
        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        if (!requestRateLimiter.tryAcquire("rotate-link:" + telegramId, 3, Duration.ofHours(1))) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "rate_limited",
                    "message", "Слишком частые запросы. Повторите попытку через час."));
        }
        try {
            Customer user = accessService.rotateSubscriptionLink(telegramId);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "subscriptionLink", orEmpty(user.getSubscriptionLink()),
                    "accessLink", buildAccessLink(user.getSubscriptionLink())));
        } catch (Exception e) {
            log.error("Mini App: rotate-link failed for telegramId={}", telegramId, e);
            return ResponseEntity.status(502).body(Map.of(
                    "error", "rotate_failed",
                    "message", "Сменить ссылку не удалось. Повторите попытку позже."));
        }
    }

    /**
     * Список серверов сервиса с их доступностью — для экрана «Статус серверов» в Mini App.
     * Доступность считает {@link ServerStatusService} (TCP-коннект к ноде, результат кешируется).
     */
    @GetMapping("/servers")
    public ResponseEntity<?> servers(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {
        try {
            validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        return ResponseEntity.ok(serverStatusService.getStatuses());
    }

    /**
     * Создаёт новый YooKassa-платёж для текущего юзера. Повторяет логику
     * {@code SubscriptionBot.handleYooKassaPayment} — создаём PENDING Payment, зовём YooKassa API,
     * сохраняем yookassa_payment_id, отдаём confirmation URL фронту.
     * <p>
     * После оплаты YooKassa отправит webhook → существующий {@code PaymentProcessingService}
     * активирует подписку и пришлёт юзеру сообщение со ссылкой подписки (как при обычной оплате).
     */
    @PostMapping("/payments/yookassa")
    public ResponseEntity<?> createYooKassaPayment(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestBody CreatePaymentRequest request) {

        TelegramInitDataValidator.TelegramUser user;
        try {
            user = validator.validateAndExtractUser(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            log.warn("Mini App /api/payments/yookassa: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        if (request == null || request.planId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing planId"));
        }

        AppConfig.Plan plan;
        try {
            plan = appConfig.findPlan(request.planId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown plan"));
        }

        boolean autorenew = Boolean.TRUE.equals(request.autorenew());
        if (autorenew && !appConfig.getYookassa().isRecurrentEnabled()) {
            // Автопродление выключено флагом — не даём создать payment с save_payment_method
            return ResponseEntity.badRequest().body(Map.of("error", "autorenew disabled"));
        }

        // Ступень устройств: цена план×ступень, ступень сохраняется на Payment (webhook по ней
        // выставит device_limit). Невалидный/отсутствующий id → legacy-цена плана.
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(request.deviceTierId()).orElse(null);
        long baseKopeks = appConfig.tierPriceKopeks(plan, tier);

        // 1. PENDING Payment с ценой план×ступень. Цель написана на строке платежа с рождения.
        TargetSpec spec;
        try {
            spec = TargetSpec.ofWire(request.target());
        } catch (IllegalArgumentException e) {
            return badTargetResponse(request.target(), user.id());
        }

        Payment pending;
        try {
            pending = paymentFactory.create(PaymentDraft.builder()
                    .telegramId(user.id())
                    .telegramUsername(user.username())
                    .planId(plan.getId())
                    .deviceTierId(tier != null ? tier.getId() : null)
                    .amountKopeks(baseKopeks)
                    .provider(Payment.Provider.YOOKASSA)
                    .build(), spec);
        } catch (CheckoutRejectedException e) {
            return checkoutRejectedResponse(e);
        }

        // 2. Промокод (если задан) — атомарно резервируем слот и пересчитываем amount.
        // applyPromoOrError либо возвращает финальную сумму (со скидкой или без), либо
        // помечает payment CANCELED — тогда возвращаем 400 с человекочитаемой причиной отказа.
        PromoApply promo = applyPromoOrError(request.promoCode(), pending, baseKopeks, user.id());
        if (!promo.ok()) {
            return PromoResponses.invalid(request.promoCode(), promo.reason());
        }

        // 3. YooKassa API
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("telegram_id", String.valueOf(user.id()));
        metadata.put("plan_id", plan.getId());
        metadata.put("payment_row_id", pending.getId().toString());

        YooKassaClient.PaymentResponse resp;
        try {
            resp = yooKassaClient.createPayment(
                    promo.chargeKopeks(),
                    describeWithTier(plan, tier),
                    appConfig.getYookassa().getReturnUrl(),
                    metadata,
                    UUID.randomUUID().toString(),
                    autorenew
            );
        } catch (Exception e) {
            log.error("Mini App: YooKassa createPayment failed for telegramId={}", user.id(), e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            // Счёта не будет — резерв промокода снимаем сразу, иначе человек, у которого
            // сорвался платёж, сутки не смог бы применить собственный код.
            releasePromoReservation(pending);
            return ResponseEntity.status(502).body(Map.of("error", "payment_provider_error"));
        }

        // 4. Сохраняем id от YooKassa для последующей сверки в webhook
        pending.setYookassaPaymentId(resp.getId());
        paymentRepository.save(pending);

        return ResponseEntity.ok(Map.of(
                "paymentId", resp.getId(),
                "confirmationUrl", resp.getConfirmation().getConfirmationUrl()
        ));
    }

    /**
     * DTO для тела запроса POST /api/payments/{yookassa,cryptobot,stars}.
     * {@code promoCode} опционален — если задан и валиден, применяется скидка к amount.
     * {@code autorenew} применяется только к YooKassa. {@code deviceTierId} — ступень
     * устройств ({@code app.device-tiers}); null → legacy-цена плана без лимита.
     * <p>
     * {@code target} — в какую подписку зачислить: {@code null} (старый закешированный фронт),
     * {@code "current"}, {@code "new"} или id подписки строкой. {@code null} означает текущую,
     * поэтому фронт прошлой версии продолжает работать и продолжает попадать деньгами ровно
     * туда, что он человеку и показывает.
     */
    public record CreatePaymentRequest(String planId, Boolean autorenew, String promoCode,
                                       String deviceTierId, String target) {}

    /**
     * Фронт прислал цель, которую мы не понимаем. Отвечаем отказом, а не «зачислю в текущую»:
     * молчаливая подмена цели — это деньги не в той подписке при правильной надписи на экране.
     */
    private ResponseEntity<?> badTargetResponse(String raw, long telegramId) {
        log.warn("Mini App: непонятная цель платежа '{}' от telegramId={}", raw, telegramId);
        return ResponseEntity.badRequest().body(Map.of(
                "error", "bad_target",
                "message", "Не удалось определить подписку для зачисления. Обновите приложение."));
    }

    /**
     * Отдельную подписку завести нельзя. Отказ приходит ДО обращения к провайдеру, поэтому
     * деньги не движутся; человеку нужна причина, а не «ошибка платежа» — из трёх причин
     * две решаются им самим.
     */
    private ResponseEntity<?> checkoutRejectedResponse(CheckoutRejectedException e) {
        String message = switch (e.reason()) {
            case CheckoutRejectedException.LIMIT ->
                    "У Вас уже максимум подписок. Пополните любую из существующих.";
            case CheckoutRejectedException.SAME_TIER_EXISTS ->
                    "Подписка с таким числом устройств у Вас уже есть — вторая такая же "
                            + "не даст ничего нового. Выберите другое число устройств "
                            + "или пополните существующую.";
            default ->
                    "Сейчас можно оплатить только текущую подписку.";
        };
        return ResponseEntity.badRequest().body(Map.of(
                "error", "checkout_rejected",
                "reason", e.reason(),
                "message", message));
    }

    // =====================================================================
    //  История, реферал, автопродление
    // =====================================================================

    /** История платежей текущего юзера, от новых к старым. */
    @GetMapping("/payments")
    public ResponseEntity<?> payments(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {
        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        List<Payment> payments = paymentRepository.findByTelegramIdOrderByCreatedAtDesc(telegramId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Payment p : payments) {
            // Имя тарифа — из AppConfig, не из БД (на Payment хранится только planId).
            // План мог быть удалён из конфига — тогда показываем «—». Ступень устройств
            // дописываем к имени («3 месяца · 2 устройства»), если была выбрана.
            // Докупка слота — не план, у неё своё имя.
            String planName = p.isDeviceAddon()
                    ? "Доп. устройство"
                    : appConfig.findPlanOptional(p.getPlanId())
                            .map(AppConfig.Plan::getName)
                            .orElse("—");
            String tierName = appConfig.findDeviceTierOptional(p.getDeviceTierId())
                    .map(AppConfig.DeviceTier::getName)
                    .orElse(null);
            if (tierName != null && !"—".equals(planName)) {
                planName = planName + " · " + tierName;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("createdAtEpoch", p.getCreatedAt().toEpochSecond(ZoneOffset.UTC));
            item.put("paidAtEpoch", p.getPaidAt() == null ? 0 : p.getPaidAt().toEpochSecond(ZoneOffset.UTC));
            item.put("amountKopeks", p.getAmountKopeks());
            item.put("refundAmountKopeks", p.getRefundAmountKopeks() == null ? 0 : p.getRefundAmountKopeks());
            item.put("status", p.getStatus().toString());
            item.put("provider", p.getProvider() == null ? "" : p.getProvider().toString());
            item.put("planName", planName);
            items.add(item);
        }
        return ResponseEntity.ok(items);
    }

    /** Информация о реферальной программе — ссылка + статистика. */
    @GetMapping("/referral")
    public ResponseEntity<?> referral(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData) {
        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        ReferralService.ReferralStats stats = referralService.getStats(telegramId);
        String botUsername = orEmpty(appConfig.getTelegram().getBotUsername());
        // Реферальная deep-link конструируется как ?start=ref_<telegramId> — так
        // инициализируется Referral в базе когда приглашённый впервые запустит бота.
        String link = botUsername.isEmpty() ? "" : "https://t.me/" + botUsername + "?start=ref_" + telegramId;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("link", link);
        body.put("invited", stats.invited());
        body.put("rewarded", stats.rewarded());
        body.put("totalBonusDays", stats.totalBonusDays());
        body.put("capRemaining", stats.capRemaining());
        return ResponseEntity.ok(body);
    }

    /**
     * Создаёт CryptoBot-инвойс. Повторяет {@code SubscriptionBot.handleCryptoPayment}:
     * PENDING Payment → createInvoice → сохраняем cryptoInvoiceId → отдаём mini-app URL.
     */
    @PostMapping("/payments/cryptobot")
    public ResponseEntity<?> createCryptoBotPayment(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestBody CreatePaymentRequest request) {

        TelegramInitDataValidator.TelegramUser tgUser;
        try {
            tgUser = validator.validateAndExtractUser(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        if (request == null || request.planId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing planId"));
        }
        AppConfig.Plan plan;
        try { plan = appConfig.findPlan(request.planId()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", "unknown plan")); }

        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(request.deviceTierId()).orElse(null);
        long baseKopeks = appConfig.tierPriceKopeks(plan, tier);

        TargetSpec spec;
        try {
            spec = TargetSpec.ofWire(request.target());
        } catch (IllegalArgumentException e) {
            return badTargetResponse(request.target(), tgUser.id());
        }

        Payment pending;
        try {
            pending = paymentFactory.create(PaymentDraft.builder()
                    .telegramId(tgUser.id())
                    .telegramUsername(tgUser.username())
                    .planId(plan.getId())
                    .deviceTierId(tier != null ? tier.getId() : null)
                    .amountKopeks(baseKopeks)
                    .provider(Payment.Provider.CRYPTOBOT)
                    .build(), spec);
        } catch (CheckoutRejectedException e) {
            return checkoutRejectedResponse(e);
        }

        PromoApply promo = applyPromoOrError(request.promoCode(), pending, baseKopeks, tgUser.id());
        if (!promo.ok()) {
            return PromoResponses.invalid(request.promoCode(), promo.reason());
        }

        CryptoBotClient.InvoiceResponse resp;
        try {
            resp = cryptoBotClient.createInvoice(
                    promo.chargeKopeks(),
                    describeWithTier(plan, tier),
                    pending.getId().toString(),
                    appConfig.getCryptobot().getAcceptedAssets(),
                    appConfig.getCryptobot().getPaidBtnUrl()
            );
        } catch (Exception e) {
            log.error("Mini App: CryptoBot createInvoice failed for telegramId={}", tgUser.id(), e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            releasePromoReservation(pending);
            return ResponseEntity.status(502).body(Map.of("error", "payment_provider_error"));
        }

        pending.setCryptoInvoiceId(String.valueOf(resp.getInvoiceId()));
        paymentRepository.save(pending);

        // Mini-app URL приоритетнее — tg.openLink() открывает прямо в Telegram;
        // fallback-chain на случай если API CryptoBot когда-нибудь перестанет его отдавать.
        String url = resp.getMiniAppInvoiceUrl();
        if (url == null || url.isBlank()) url = resp.getWebAppInvoiceUrl();
        if (url == null || url.isBlank()) url = resp.getBotInvoiceUrl();
        if (url == null || url.isBlank()) url = resp.getPayUrl();

        return ResponseEntity.ok(Map.of(
                "paymentId", String.valueOf(resp.getInvoiceId()),
                "invoiceUrl", orEmpty(url)
        ));
    }

    /**
     * Создаёт Telegram Stars invoice link через Bot API {@code createInvoiceLink}.
     * Возвращает URL вида https://t.me/$... — фронт открывает его через
     * {@code tg.openInvoice(url)} и получает нативное Stars-UI внутри Telegram.
     * <p>
     * Payload — наш payment.id, он же будет в {@code successful_payment} webhook-е,
     * когда Telegram пришлёт его боту. Обработка в {@link com.orchestra.subscriptions.bot.SubscriptionBot} уже готова.
     */
    @PostMapping("/payments/stars")
    public ResponseEntity<?> createStarsPayment(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestBody CreatePaymentRequest request) {

        TelegramInitDataValidator.TelegramUser tgUser;
        try {
            tgUser = validator.validateAndExtractUser(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        if (request == null || request.planId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing planId"));
        }
        AppConfig.Plan plan;
        try { plan = appConfig.findPlan(request.planId()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", "unknown plan")); }

        if (plan.getPriceStars() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "stars disabled for this plan"));
        }

        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(request.deviceTierId()).orElse(null);
        long baseKopeks = appConfig.tierPriceKopeks(plan, tier);

        TargetSpec spec;
        try {
            spec = TargetSpec.ofWire(request.target());
        } catch (IllegalArgumentException e) {
            return badTargetResponse(request.target(), tgUser.id());
        }

        Payment pending;
        try {
            pending = paymentFactory.create(PaymentDraft.builder()
                    .telegramId(tgUser.id())
                    .telegramUsername(tgUser.username())
                    .planId(plan.getId())
                    .deviceTierId(tier != null ? tier.getId() : null)
                    .amountKopeks(baseKopeks)
                    .provider(Payment.Provider.STARS)
                    .build(), spec);
        } catch (CheckoutRejectedException e) {
            return checkoutRejectedResponse(e);
        }

        // Промо для Stars: скидка задана в копейках/процентах от рублёвого price.
        // Применяем ту же долю к priceStars через {@link StarsAmount#compute} — он же используется
        // в SubscriptionBot.handlePreCheckout для re-validation, чтобы разный счёт не сваливал платёж.
        PromoApply promo = applyPromoOrError(request.promoCode(), pending, baseKopeks, tgUser.id());
        if (!promo.ok()) {
            return PromoResponses.invalid(request.promoCode(), promo.reason());
        }
        long starsToCharge = StarsAmount.compute(
                plan.getPriceStars(), plan.getPriceKopeks(), promo.chargeKopeks());

        // CreateInvoiceLink для Stars (XTR). В 9.5 поддержка Stars нативная: providerToken
        // необязателен и validate() его не требует, поэтому прежний костыль с анонимным subclass
        // больше не нужен — собираем билдером. payload = наш payment.id (вернётся в successful_payment).
        String tariffTitle = tier != null ? plan.getName() + " · " + tier.getName() : plan.getName();
        CreateInvoiceLink method = CreateInvoiceLink.builder()
                .title(tariffTitle)
                .description(describeWithTier(plan, tier))
                .payload(pending.getId().toString())
                .currency("XTR")
                .prices(List.of(LabeledPrice.builder()
                        .label(tariffTitle)
                        .amount(Math.toIntExact(starsToCharge))
                        .build()))
                .build();

        String invoiceUrl;
        try {
            invoiceUrl = subscriptionBot.execute(method);
        } catch (TelegramApiException e) {
            log.error("Mini App: Stars createInvoiceLink failed for telegramId={}", tgUser.id(), e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            releasePromoReservation(pending);
            return ResponseEntity.status(502).body(Map.of("error", "payment_provider_error"));
        }

        return ResponseEntity.ok(Map.of(
                "paymentId", pending.getId().toString(),
                "invoiceUrl", orEmpty(invoiceUrl)
        ));
    }

    /**
     * Включает/выключает автопродление. Body: {"enabled": true|false}.
     * Возвращает актуальное состояние после применения; 400 если нельзя включить (нет привязанной карты).
     */
    @PostMapping("/autorenew")
    public ResponseEntity<?> toggleAutorenew(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestBody(required = false) Map<String, Object> body) {

        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }

        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        if (enabled) {
            boolean ok = autoRenewService.enableAutoRenew(telegramId);
            if (!ok) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "no_card_bound",
                        "message", "Карта не привязана. Для автопродления оплатите подписку с опцией «С автопродлением»."));
            }
        } else {
            autoRenewService.disableAutoRenew(telegramId);
        }

        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);
        return ResponseEntity.ok(Map.of(
                "autorenewEnabled", user != null && user.isAutorenewEnabled(),
                "autorenewAvailable", user != null && user.getAutorenewMethodId() != null));
    }

    // =====================================================================
    //  Промокоды
    // =====================================================================

    /**
     * Предварительная валидация промокода — возвращает скидку и новую цену без записи в БД.
     * Фронт дёргает этот эндпоинт по кнопке «Применить» в payment drawer, чтобы показать
     * пересчитанную цену перед выбором способа оплаты.
     * <p>
     * Body: {@code {"code": "...", "planId": "month_3"}}.
     * Ответ при успехе: {@code {"valid": true, "discountKopeks": ..., "finalAmountKopeks": ...,
     * "discountType": "PERCENT|FIXED", "discountValue": ..., "code": "WELCOME20"}}.
     * Ответ при отказе: {@code {"valid": false, "reason": "expired|max_uses_reached|already_used|...",
     * "message": "Срок действия истёк"}} (200 OK — фронту удобнее показать причину).
     */
    @PostMapping("/promos/validate")
    public ResponseEntity<?> validatePromo(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestBody Map<String, Object> body) {

        TelegramInitDataValidator.TelegramUser tgUser;
        try {
            tgUser = validator.validateAndExtractUser(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        if (!requestRateLimiter.tryAcquire(PromoResponses.VALIDATE_LIMIT_KEY + tgUser.id(),
                PromoResponses.VALIDATE_MAX_PER_HOUR, Duration.ofHours(1))) {
            return PromoResponses.rateLimited();
        }

        if (body == null || body.get("code") == null || body.get("planId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing code or planId"));
        }
        String rawCode = body.get("code").toString();
        String planId = body.get("planId").toString();

        AppConfig.Plan plan;
        try { plan = appConfig.findPlan(planId); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", "unknown plan")); }

        // База скидки — цена план×ступень (deviceTierId опционален, null → цена плана).
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(
                body.get("deviceTierId") == null ? null : body.get("deviceTierId").toString()).orElse(null);
        long baseKopeks = appConfig.tierPriceKopeks(plan, tier);

        PromoCodeService.PromoResult result = promoCodeService.preview(rawCode, baseKopeks, tgUser.id());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("valid", result.valid());
        if (result.valid()) {
            resp.put("code", result.code());
            resp.put("discountType", result.discountType().name());
            resp.put("discountValue", result.discountValue());
            resp.put("discountKopeks", result.discountKopeks());
            resp.put("finalAmountKopeks", result.finalAmountKopeks());
            resp.put("originalAmountKopeks", baseKopeks);
        } else {
            resp.put("reason", result.reason());
            resp.put("message", PromoMessages.reasonMessage(result.reason()));
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * Резервирует промокод под уже созданный PENDING-платёж и при успехе сохраняет код на нём.
     * Возвращает результат: {@code ok=true} → финальная сумма в {@link PromoApply#chargeKopeks},
     * продолжаем с провайдером. {@code ok=false} → причина отказа в {@link PromoApply#reason}.
     * <p>
     * <b>Слот кода занимается ЗДЕСЬ, а не на webhook'е SUCCEEDED.</b> Пока проверка была
     * read-only-превью, N чекаутов, открытых параллельно, проходили её все и все получали
     * скидку. Теперь строка в {@code promo_usages} появляется вместе со счётом, и второй
     * параллельный чекаут того же человека отклоняет уникальный индекс — с обычным
     * {@code already_used}, а не 500 (см. {@code PromoCodeService.reserveForCheckout}).
     * <p>
     * Side effect при успехе: обновляет {@code payment.amountKopeks} и
     * {@code payment.promoCodeUsed}, сохраняет. При неудаче маркирует payment как CANCELED.
     * Если счёт создать не удастся, вызывающий обязан снять резерв
     * ({@code promoCodeService.releaseReservation}) — иначе код будет занят у человека сутки.
     */
    private PromoApply applyPromoOrError(String rawPromoCode, Payment payment,
                                         long baseKopeks, long telegramId) {
        if (rawPromoCode == null || rawPromoCode.trim().isEmpty()) {
            return PromoApply.ok(baseKopeks);
        }
        PromoCodeService.PromoResult result = promoCodeService.reserveForCheckout(
                rawPromoCode, baseKopeks, telegramId, payment.getId());
        if (!result.valid()) {
            // Промо невалиден — отменяем PENDING-payment, чтобы не оставался висеть.
            payment.setStatus(Payment.Status.CANCELED);
            paymentRepository.save(payment);
            log.info("Promo rejected: code={} reason={} telegramId={}", rawPromoCode, result.reason(), telegramId);
            return PromoApply.fail(result.reason());
        }
        // Сохраняем нормализованный код на Payment — по нему возврат находит применение,
        // а старые счета (выставленные до резервирования) дозаписывает recordUsageOnSucceeded.
        payment.setPromoCodeUsed(result.code());
        if (result.discountKopeks() > 0) {
            payment.setAmountKopeks(result.finalAmountKopeks());
        }
        paymentRepository.save(payment);
        return PromoApply.ok(result.finalAmountKopeks());
    }

    /**
     * Снимает резерв промокода со счёта, который так и не был выставлен. Ошибку глотаем: ответ
     * человеку про несостоявшийся платёж важнее, а невзятый резерв всё равно протухнет сам
     * (см. {@code PromoCodeService.RESERVATION_TTL_HOURS}).
     */
    private void releasePromoReservation(Payment payment) {
        if (payment.getPromoCodeUsed() == null) {
            return;
        }
        try {
            promoCodeService.releaseReservation(payment.getId());
        } catch (Exception e) {
            log.warn("Не удалось снять резерв промокода по платежу id={}", payment.getId(), e);
        }
    }

    /** Описание для провайдера/чека: описание плана + ступень устройств, если выбрана. */
    private static String describeWithTier(AppConfig.Plan plan, AppConfig.DeviceTier tier) {
        return tier == null ? plan.getDescription() : plan.getDescription() + " · " + tier.getName();
    }

    /** Внутренний результат {@link #applyPromoOrError}. */
    private record PromoApply(boolean ok, long chargeKopeks, String reason) {
        static PromoApply ok(long kopeks) { return new PromoApply(true, kopeks, null); }
        static PromoApply fail(String reason) { return new PromoApply(false, 0, reason); }
    }


}
