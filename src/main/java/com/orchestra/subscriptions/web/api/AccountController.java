package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.MagicLinkService;
import com.orchestra.subscriptions.service.PromoCodeService;
import com.orchestra.subscriptions.service.payment.PaymentDraft;
import com.orchestra.subscriptions.service.payment.PaymentFactory;
import com.orchestra.subscriptions.util.PromoMessages;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Endpoints web-личного-кабинета. В отличие от {@link WebAppController} (Telegram
 * Mini App, авторизация через initData), этот контроллер обслуживает запросы из
 * обычного браузера и авторизует юзера через cookie {@link AccountSessionService#COOKIE_NAME}.
 * <p>
 * Поток: юзер открывает /auth?token=... из письма → выдаём подписанный cookie и
 * редиректим на /account/. Дальше JS на странице ЛК дёргает /api/account/me с
 * этим cookie, рендерит данные. Logout — POST /auth/logout инкрементит
 * {@code session_version} (отзыв всех выданных cookie) и чистит cookie в браузере.
 *
 * <h2>Почему вход двухшаговый</h2>
 * Сессию по одному лишь GET-переходу из письма выдавать нельзя: атакующий заказывал
 * ссылку на СВОЙ адрес и заманивал жертву её открыть — жертва молча оказывалась в
 * ЧУЖОМ кабинете и могла оплатить чужую подписку через
 * {@link #accountCreateYooKassaPayment}. Поэтому:
 * <ol>
 *   <li>{@code POST /auth/request} помечает браузер, который просит ссылку,
 *       nonce-cookie {@link #NONCE_COOKIE} и отдаёт этот nonce в
 *       {@link MagicLinkService#create(Customer, String)} — токен оказывается привязан
 *       не только к аккаунту, но и к браузеру;</li>
 *   <li>{@code GET /auth} с совпавшим nonce = ссылку открыл тот же браузер: пускаем
 *       сразу, как раньше;</li>
 *   <li>{@code GET /auth} без совпадения — <b>штатный случай</b> (письмо открыли на
 *       другом устройстве, в почтовом клиенте, после рестарта приложения): показываем
 *       страницу подтверждения с замаскированным адресом получателя и кнопкой, которая
 *       делает same-site {@code POST /auth/confirm}. Межсайтовая атака этот POST не
 *       проходит (см. {@link #confirmAuth}), а человек проходит одним кликом.</li>
 * </ol>
 * Сессионная cookie выдаётся ТОЛЬКО в шагах 2 и 3 — и ровно там же гасится токен.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final MagicLinkService magicLinkService;
    private final AccountSessionService sessionService;
    private final CustomerRepository userRepository;
    private final PaymentRepository paymentRepository;
    /** Строку платежа заводит только фабрика — она же пишет цель (в какую подписку зачислять). */
    private final PaymentFactory paymentFactory;
    private final MagicLinkRateLimiter magicLinkRateLimiter;
    /** Общий лимитер (не magic-link'овый): им прикрыта валидация промокода. */
    private final RequestRateLimiter requestRateLimiter;
    private final AccountMagicLinkAsyncSender asyncSender;
    private final YooKassaClient yooKassaClient;
    private final PromoCodeService promoCodeService;
    private final AppConfig appConfig;
    /** Факты подписки читаем через фасад: источник за флагом переезда. */
    private final com.orchestra.subscriptions.service.SubscriptionLookupService subscriptionLookup;
    /** Список подписок — общий рендер с Mini App, чтобы два экрана не разошлись в словах. */
    private final SubscriptionListView subscriptionListView;

    /** Тот же regex, что в {@link com.orchestra.subscriptions.service.EmailSettingsService}, — оба входа
     *  (ЛК и Mini App) должны считать адрес валидным одинаково. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    /** Сколько символов User-Agent пишем в лог: дальше начинается шум, а строка бывает под килобайт. */
    private static final int UA_LOG_MAX = 120;

    /**
     * Cookie-отметка браузера, который запросил ссылку. Живёт столько же, сколько токен:
     * дольше она бессмысленна, короче — сломала бы вход по ссылке, открытой на 14-й минуте.
     */
    static final String NONCE_COOKIE = "account_auth_nonce";

    /**
     * Cookie страницы подтверждения. Её значение дублируется скрытым полем формы, и
     * {@code POST /auth/confirm} требует совпадения (double-submit). Межсайтовый POST
     * cookie с {@code SameSite=Lax} не получает, поэтому подделать пару не может.
     */
    static final String CONFIRM_COOKIE = "account_auth_confirm";

    /** Обе служебные cookie нужны только эндпоинтам /auth* — туда их и ограничиваем. */
    private static final String AUTH_COOKIE_PATH = "/auth";

    /** 32 байта → 256 бит: подобрать nonce или csrf перебором невозможно. */
    private static final int SECRET_BYTES = 32;

    /**
     * CSP страницы подтверждения. Копия {@code SecurityHeadersFilter.applyAccountCsp}:
     * фильтр вешает CSP на {@code /account/*}, но не на {@code /auth}, а здесь эта
     * политика важнее всего — {@code form-action 'self'} не даёт увести POST-подтверждение
     * на чужой хост, если на страницу когда-нибудь попадёт инъекция.
     */
    private static final String CONFIRM_PAGE_CSP = String.join("; ",
            "default-src 'self'",
            // Шрифты свои — внешних хостов в политике больше нет. 'unsafe-inline' для
            // style остаётся: страница подтверждения собирается со style-атрибутами.
            "style-src 'self' 'unsafe-inline'",
            "font-src 'self'",
            "img-src 'self' data:",
            "script-src 'self'",
            "connect-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'");

    /** Генератор nonce/csrf. Инициализированное final-поле Lombok в конструктор не тянет. */
    private final SecureRandom secureRandom = new SecureRandom();

    /** Стандартное сообщение для публичной формы — одинаковое для known/unknown email. */
    private static final String GENERIC_REQUEST_MESSAGE =
            "Если этот email привязан к аккаунту, в течение минуты вы получите письмо со ссылкой для входа.";

    /**
     * Клик по magic-link из письма. Три исхода:
     * <ul>
     *   <li>токен негоден (нет/истёк/погашен/сменился email) — redirect на
     *       {@code /account/expired}, одна страница на все случаи (не раскрываем, какой
     *       именно);</li>
     *   <li>токен годен И его открыл тот браузер, который ссылку просил (совпал
     *       {@link #NONCE_COOKIE}) — сразу выдаём сессию и редиректим в кабинет;</li>
     *   <li>токен годен, но браузер другой — 200 со страницей подтверждения. Отказать
     *       тут было бы ошибкой: письма постоянно открывают с телефона, из почтового
     *       клиента, из другого профиля браузера.</li>
     * </ul>
     * <p>
     * <b>Токен здесь НЕ гасится.</b> Проверка идёт через {@link MagicLinkService#preview},
     * который ничего не мутирует, а {@code consume} вызывается только в момент реальной
     * выдачи сессии. Иначе почтовые антивирусы и предзагрузка ссылок в браузере, которые
     * открывают URL раньше человека, сжигали бы одноразовый токен до первого клика.
     * По той же причине предзагрузку ({@link #isLinkPreload}) не пускаем и в быструю
     * ветку: там сессия выдаётся молча, то есть токен сгорел бы на предзагрузке.
     * <p>
     * <b>Источник входа в логе.</b> Все ветки пишут IP и обрезанный User-Agent: строка
     * «сессия выдана» без источника не отвечает на единственный вопрос, который задают
     * при разборе «кто-то вошёл в мой кабинет». Отказ — WARN, а не INFO: письмо со
     * ссылкой уходит одному человеку, поэтому серия отказов означает перебор токенов, а
     * не бытовую ошибку. Сам token в лог не попадает никогда — по нему входят, а лог
     * читают больше людей, чем письмо.
     */
    @GetMapping("/auth")
    public ResponseEntity<?> authViaToken(
            @RequestParam(value = "token", required = false) String token,
            @CookieValue(value = NONCE_COOKIE, required = false) String browserNonce,
            HttpServletRequest request,
            HttpServletResponse response) {

        String ip = ClientIpResolver.resolve(request, appConfig.getTrustedProxyIps());
        String ua = shortUserAgent(request.getHeader("User-Agent"));

        MagicLinkService.Preview preview;
        try {
            preview = magicLinkService.preview(token);
        } catch (MagicLinkService.InvalidMagicLinkException e) {
            log.warn("Magic-link auth rejected: {} ip={} ua={}", e.getMessage(), ip, ua);
            return redirect("/account/expired");
        }

        boolean sameBrowser = magicLinkService.matchesBrowser(token, browserNonce);
        boolean preload = isLinkPreload(request);
        if (sameBrowser && !preload) {
            return completeLogin(token, ip, ua, response, HttpStatus.FOUND);
        }

        log.info("Magic-link needs confirmation: telegramId={} sameBrowser={} preload={} ip={} ua={}",
                preview.telegramId(), sameBrowser, preload, ip, ua);
        return confirmPage(token, preview.maskedEmail(), null, response);
    }

    /**
     * Завершение входа с этой страницы: same-site POST, который человек делает кнопкой.
     * Именно этот шаг закрывает подставу «атакующий заказал ссылку на свой адрес и дал
     * жертве по ней перейти» — межсайтовый переход даёт максимум показ страницы, а
     * сессию выдаёт только POST, который межсайтово не собрать:
     * <ul>
     *   <li>{@link #CONFIRM_COOKIE} выдана с {@code SameSite=Lax}, поэтому в межсайтовом
     *       POST браузер её не пришлёт — сверять с полем формы будет нечего;</li>
     *   <li>значение поля формы атакующему неизвестно: cookie {@code HttpOnly} и своя
     *       у каждого показа страницы;</li>
     *   <li>{@code Sec-Fetch-Site: cross-site} (браузер проставляет сам, от нашей
     *       конфигурации доменов не зависит) отбрасываем явно.</li>
     * </ul>
     * Неудачное подтверждение НЕ гасит токен и НЕ отправляет человека на «ссылка не
     * работает»: cookie мог не сохраниться или страница провисеть слишком долго —
     * показываем ту же страницу с пояснением и свежей cookie.
     */
    @PostMapping(value = "/auth/confirm", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> confirmAuth(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "csrf", required = false) String csrf,
            @CookieValue(value = CONFIRM_COOKIE, required = false) String confirmCookie,
            HttpServletRequest request,
            HttpServletResponse response) {

        String ip = ClientIpResolver.resolve(request, appConfig.getTrustedProxyIps());
        String ua = shortUserAgent(request.getHeader("User-Agent"));

        MagicLinkService.Preview preview;
        try {
            preview = magicLinkService.preview(token);
        } catch (MagicLinkService.InvalidMagicLinkException e) {
            log.warn("Magic-link confirm rejected: {} ip={} ua={}", e.getMessage(), ip, ua);
            return redirect("/account/expired", HttpStatus.SEE_OTHER);
        }

        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
            log.warn("Magic-link confirm came cross-site: telegramId={} ip={} ua={}",
                    preview.telegramId(), ip, ua);
            return confirmPage(token, preview.maskedEmail(), CONFIRM_RETRY_NOTICE, response);
        }
        if (!secretsEqual(confirmCookie, csrf)) {
            log.warn("Magic-link confirm without matching cookie: telegramId={} ip={} ua={}",
                    preview.telegramId(), ip, ua);
            return confirmPage(token, preview.maskedEmail(), CONFIRM_RETRY_NOTICE, response);
        }

        return completeLogin(token, ip, ua, response, HttpStatus.SEE_OTHER);
    }

    /**
     * Гасит токен и выдаёт сессионную cookie. Единственное место, где сессия появляется
     * у magic-link-входа: и быстрая ветка {@code GET /auth}, и подтверждение приходят
     * сюда, чтобы правила выдачи не разъехались между ними.
     * <p>
     * Cookie: HttpOnly (защита от XSS), Secure (только HTTPS), SameSite=Lax
     * (защита от CSRF, но позволяет переход с письма).
     *
     * @param status 302 для перехода по ссылке, 303 после POST — чтобы браузер пошёл
     *               в кабинет методом GET, а не повторил POST при обновлении страницы
     */
    private ResponseEntity<Void> completeLogin(String token, String ip, String ua,
                                               HttpServletResponse response, HttpStatus status) {
        Customer user;
        try {
            user = magicLinkService.consume(token);
        } catch (MagicLinkService.InvalidMagicLinkException e) {
            // Между preview и consume токен мог погаснуть (второе окно, гонка) — тот же
            // ответ, что и на изначально негодный токен.
            log.warn("Magic-link auth rejected: {} ip={} ua={}", e.getMessage(), ip, ua);
            return redirect("/account/expired", status);
        }

        // sessionVersion берём snapshot'ом из user — кладём в payload, далее authenticate()
        // на следующих запросах сравнит с актуальным значением. Если юзер сменит/удалит email,
        // sv в БД станет +1, выданный сейчас cookie перестанет валидироваться → 401.
        int sv = user.getSessionVersion() != null ? user.getSessionVersion() : 0;
        String cookieValue = sessionService.issue(user.getTelegramId(), sv);
        // ResponseCookie builder валидирует value и safely сериализует Set-Cookie
        // header — защита от header-injection если когда-нибудь в cookieValue
        // попадут CR/LF (сейчас base64url + hex, безопасно, но контракт может меняться).
        ResponseCookie cookie = ResponseCookie.from(AccountSessionService.COOKIE_NAME, cookieValue)
                .path("/")
                .maxAge(AccountSessionService.TTL)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        // Служебные cookie входа отработали — гасим обе, чтобы в браузере не оставалось
        // ничего, чем можно воспользоваться повторно.
        response.addHeader(HttpHeaders.SET_COOKIE, clearedAuthCookie(NONCE_COOKIE));
        response.addHeader(HttpHeaders.SET_COOKIE, clearedAuthCookie(CONFIRM_COOKIE));

        log.info("Account session issued for telegramId={} via magic-link ip={} ua={}",
                user.getTelegramId(), ip, ua);
        return redirect("/account/", status);
    }

    /**
     * Данные для главной страницы ЛК. Требует валидную session-cookie; на
     * невалидной/протухшей — 401 (фронт редиректит на /account/login).
     * <p>
     * Отдаём минимум по scope: email, подписка, дата окончания, ссылки (подписочная +
     * клиентское приложение-routing). Истории платежей и кнопок «продлить» / «refund» в MVP нет.
     */
    @GetMapping("/api/account/me")
    public ResponseEntity<?> accountMe(
            @CookieValue(value = AccountSessionService.COOKIE_NAME, required = false) String sessionCookie) {

        Customer user = authenticate(sessionCookie).orElse(null);
        if (user == null) {
            // parse-fail / юзер удалён / sv-mismatch (отозванная сессия) — все три случая
            // возвращают одинаковую 401 без раскрытия деталей. Фронт покажет login.
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }

        // Факты подписки — через фасад (источник за флагом); почта и прочее остаётся
        // на человеке, потому что принадлежит человеку, а не подписке.
        final com.orchestra.subscriptions.model.Customer owner = user;
        com.orchestra.subscriptions.service.SubscriptionView sub = subscriptionLookup
                .currentFor(owner.getTelegramId())
                .orElseGet(() -> com.orchestra.subscriptions.service.SubscriptionView.ofUser(owner));

        long expiresAtEpoch = sub.expiresAt() == null ? 0
                : sub.expiresAt().toEpochSecond(ZoneOffset.UTC);
        long paidCount = paymentRepository.countByTelegramIdAndStatus(
                user.getTelegramId(), Payment.Status.SUCCEEDED);
        boolean isTrial = paidCount == 0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", orEmpty(user.getEmail()));
        body.put("emailVerified", user.getEmailVerifiedAt() != null);
        body.put("hasSubscription", sub.expiresAt() != null);
        body.put("status", sub.status() == null ? "" : sub.status().toString());
        body.put("isTrial", isTrial);
        body.put("expiresAtEpoch", expiresAtEpoch);
        // Веб-кабинет показывает ОДНУ ссылку — ту же, что Mini App и сообщения бота.
        // Фолбэк на ссылку провайдера, если свою построить не удалось.
        //
        // Источник — ПОДПИСКА (фасад), а не строка человека: в {@code customers} лежит зеркало
        // текущей подписки, и любое его отставание означает человека, который копирует со страницы
        // чужую или уже мёртвую ссылку. Остальные каналы читают оттуда же с этапа 3в.
        String publicBaseUrl = appConfig.getTelegram() != null
                ? appConfig.getTelegram().getPublicBaseUrl() : null;
        String accessSubLink = SubscriptionLinks.accessLink(sub.subscriptionLink(), publicBaseUrl);
        body.put("subscriptionLink",
                accessSubLink.isEmpty() ? orEmpty(sub.subscriptionLink()) : accessSubLink);
        // Флаг автопродления — фронт по нему решает показывать ли чекбокс
        // «С автопродлением» в форме оплаты.
        body.put("recurrentEnabled", appConfig.getYookassa().isRecurrentEnabled());
        // Подписки — ТОЛЬКО НА ЧТЕНИЕ. Переключаться из ЛК нельзя сознательно (это канал
        // для тех, кто не в Telegram, и второй путь переключения — это второй набор грабель ради
        // процента трафика), но ПОКАЗАТЬ их обязаны: человек, у которого на замороженной
        // подписке лежат его деньги, не должен видеть экран, где их нет.
        body.put("subscriptions", subscriptionListView.forUser(user));
        return ResponseEntity.ok(body);
    }

    // =====================================================================
    //  Покупка/продление подписки прямо из web-ЛК (только YooKassa).
    //  Stars/CryptoBot намеренно убраны — это «обычный сайт-вход», там
    //  такие способы оплаты не имеют смысла.
    // =====================================================================

    /**
     * Ступень устройств для покупки из web-ЛК, где выбора числа устройств нет:
     * <ul>
     *   <li>ступень уже есть — она же (продление того же тарифа);</li>
     *   <li>ступени нет, но лимит задан (ТРИАЛ, {@code device_limit=1}) — БАЗОВАЯ ступень.
     *       В ЛК с него списывается базовая цена плана, то есть цена базовой ступени, —
     *       значит и устройств он должен получить столько же. До 2026-08-12 тут был
     *       {@code null}: триал платил 179 ₽ за «2 устройства», навсегда оставался с
     *       лимитом 1 и не мог даже докупить слот (докупка требует ступени);</li>
     *   <li>ни ступени, ни лимита (LEGACY, до фичи) — {@code null}: базовая цена и
     *       НИКАКОГО лимита, старое обещание «до 5 устройств» не ломаем.</li>
     * </ul>
     */
    private AppConfig.DeviceTier lkDeviceTier(Customer user) {
        AppConfig.DeviceTier own =
                appConfig.findDeviceTierOptional(user.getDeviceTierId()).orElse(null);
        if (own != null) {
            return own;
        }
        return user.getDeviceLimit() != null ? appConfig.baseDeviceTier().orElse(null) : null;
    }

    /**
     * Список тарифов для ЛК за cookie-auth. В Mini App аналога-близнеца нет: там тарифы
     * приезжают внутри {@code /api/me} (прежний GET /tariffs убран в 2026-04), поэтому
     * ЛК ходит за ними отдельным запросом и не зависит от Mini-App-only маршрутов.
     */
    @GetMapping("/api/account/tariffs")
    public ResponseEntity<?> accountTariffs(
            @CookieValue(value = AccountSessionService.COOKIE_NAME, required = false) String sessionCookie) {
        Customer user = authenticate(sessionCookie).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }

        // ЛК — «запасной» кабинет: выбора числа устройств тут нет, покупка = продление
        // ТЕКУЩЕЙ ступени юзера (device_tier_id из последней покупки; legacy → цена плана).
        // Поэтому priceKopeks сразу тарифицированы под юзера — фронт ЛК менять не нужно.
        AppConfig.DeviceTier tier = lkDeviceTier(user);
        List<Map<String, Object>> tariffs = appConfig.getPlans().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "title", p.getName(),
                        "days", p.getDays(),
                        "priceKopeks", appConfig.tierPriceKopeks(p, tier)
                ))
                .toList();
        return ResponseEntity.ok(tariffs);
    }

    /**
     * Предварительная валидация промокода без записи. Аналог {@code /api/promos/validate},
     * но за cookie. Используется фронтом при тапе «Применить» в форме оплаты.
     */
    @PostMapping("/api/account/promos/validate")
    public ResponseEntity<?> accountValidatePromo(
            @CookieValue(value = AccountSessionService.COOKIE_NAME, required = false) String sessionCookie,
            @RequestBody Map<String, Object> body) {
        Customer user = authenticate(sessionCookie).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        long telegramId = user.getTelegramId();
        if (!requestRateLimiter.tryAcquire(PromoResponses.VALIDATE_LIMIT_KEY + telegramId,
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

        // База скидки — цена план×текущая-ступень юзера (как в accountTariffs).
        AppConfig.DeviceTier tier = lkDeviceTier(user);
        long baseKopeks = appConfig.tierPriceKopeks(plan, tier);

        PromoCodeService.PromoResult result = promoCodeService.preview(rawCode, baseKopeks, telegramId);
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

    /** DTO для создания YK-платежа из ЛК. Структурно повторяет {@code WebAppController.CreatePaymentRequest}. */
    public record AccountYookassaRequest(String planId, Boolean autorenew, String promoCode) {}

    /**
     * Создаёт YooKassa-платёж из web-ЛК. Логика идентична
     * {@code WebAppController.createYooKassaPayment}, отличается только источником auth:
     * cookie вместо initData, и returnUrl — на {@code /account/?return=yk&paymentId=<id>},
     * чтобы юзер после оплаты попал обратно в ЛК, а не в Telegram-бот.
     */
    @PostMapping("/api/account/payments/yookassa")
    public ResponseEntity<?> accountCreateYooKassaPayment(
            @CookieValue(value = AccountSessionService.COOKIE_NAME, required = false) String sessionCookie,
            @RequestBody AccountYookassaRequest request) {

        Customer user = authenticate(sessionCookie).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        long telegramId = user.getTelegramId();

        if (request == null || request.planId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing planId"));
        }
        AppConfig.Plan plan;
        try { plan = appConfig.findPlan(request.planId()); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown plan"));
        }

        boolean autorenew = Boolean.TRUE.equals(request.autorenew());
        if (autorenew && !appConfig.getYookassa().isRecurrentEnabled()) {
            return ResponseEntity.badRequest().body(Map.of("error", "autorenew disabled"));
        }

        // Продление из ЛК идёт с ТЕКУЩЕЙ ступенью устройств юзера (см. accountTariffs).
        AppConfig.DeviceTier tier = lkDeviceTier(user);
        long baseKopeks = appConfig.tierPriceKopeks(plan, tier);

        // 1. PENDING Payment с ценой план×ступень. Оплата из ЛК ВСЕГДА продлевает работающую
        //    сейчас подписку: выбора цели в этом канале нет и не будет — он для тех, кто не в
        //    Telegram, и показывает ровно одну (текущую) подписку. createIntoCurrent, а не
        //    общий create: канал, который показывает одну подписку, не должен уметь завести
        //    вторую — человек не увидел бы, куда ушли деньги.
        Payment pending = paymentFactory.createIntoCurrent(PaymentDraft.builder()
                .telegramId(telegramId)
                .telegramUsername(user.getTelegramUsername())
                .planId(plan.getId())
                .deviceTierId(tier != null ? tier.getId() : null)
                .amountKopeks(baseKopeks)
                .provider(Payment.Provider.YOOKASSA)
                .build());

        // 2. Промокод — атомарно резервируем слот и пересчитываем amount.
        AccountPromoApply promo = accountApplyPromoOrError(request.promoCode(), pending, baseKopeks, telegramId);
        if (!promo.ok()) {
            return PromoResponses.invalid(request.promoCode(), promo.reason());
        }

        // 3. YooKassa API. returnUrl несёт реальный paymentId — фронт после возврата с YK
        // сделает GET /api/account/payments/<id>/status и покажет правдивый баннер.
        // НЕ ставим тут "?paid=1": YooKassa редиректит на returnUrl и при успехе, и при
        // отмене, и при клике «Назад» — слепое доверие к маркеру привело бы к «Оплата
        // принята» там, где ничего не оплачивалось.
        String publicBase = SubscriptionLinks.stripTrailingSlash(appConfig.getTelegram().getPublicBaseUrl());
        String returnUrl = (publicBase != null ? publicBase : "")
                + "/account/?return=yk&paymentId=" + pending.getId();

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("telegram_id", String.valueOf(telegramId));
        metadata.put("plan_id", plan.getId());
        metadata.put("payment_row_id", pending.getId().toString());
        metadata.put("source", "web_account");

        YooKassaClient.PaymentResponse resp;
        try {
            resp = yooKassaClient.createPayment(
                    promo.chargeKopeks(),
                    tier == null ? plan.getDescription() : plan.getDescription() + " · " + tier.getName(),
                    returnUrl,
                    metadata,
                    UUID.randomUUID().toString(),
                    autorenew
            );
        } catch (Exception e) {
            log.error("Account: YooKassa createPayment failed for telegramId={}", telegramId, e);
            pending.setStatus(Payment.Status.FAILED);
            paymentRepository.save(pending);
            // Счёта не будет — резерв промокода снимаем сразу, иначе человек, у которого
            // сорвался платёж, сутки не смог бы применить собственный код. Ошибку глотаем:
            // ответ про несостоявшийся платёж важнее, а резерв всё равно протухнет сам.
            if (pending.getPromoCodeUsed() != null) {
                try {
                    promoCodeService.releaseReservation(pending.getId());
                } catch (Exception releaseError) {
                    log.warn("Account: не удалось снять резерв промокода по платежу id={}",
                            pending.getId(), releaseError);
                }
            }
            return ResponseEntity.status(502).body(Map.of("error", "payment_provider_error"));
        }

        pending.setYookassaPaymentId(resp.getId());
        paymentRepository.save(pending);

        return ResponseEntity.ok(Map.of(
                "paymentId", resp.getId(),
                "confirmationUrl", resp.getConfirmation().getConfirmationUrl()
        ));
    }

    /**
     * Реальный статус Payment по нашему внутреннему id — для honest-баннера в
     * ЛК после возврата с YooKassa. YK редиректит на returnUrl независимо от
     * исхода (success/cancel/back) — без этого endpoint'а фронт не мог бы
     * отличить «оплатил» от «передумал и нажал назад».
     * <p>
     * Авторизация: cookie session + проверка что payment.telegramId == sessionTelegramId.
     * Без второй проверки был бы IDOR (любой залогиненный юзер видел бы статус
     * чужих платежей по угаданному id).
     * <p>
     * Возвращает: {@code {status, planName, expiresAtEpoch, amountKopeks}}.
     * Если payment не существует или принадлежит другому — 404 (одинаковый
     * ответ, не сливаем «есть такой id»).
     */
    @GetMapping("/api/account/payments/{id}/status")
    public ResponseEntity<?> accountPaymentStatus(
            @CookieValue(value = AccountSessionService.COOKIE_NAME, required = false) String sessionCookie,
            @PathVariable("id") Long paymentId) {

        Customer sessionUser = authenticate(sessionCookie).orElse(null);
        if (sessionUser == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        long telegramId = sessionUser.getTelegramId();

        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || !payment.getTelegramId().equals(telegramId)) {
            return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        }

        // План мог быть удалён из конфига — не критично для статуса, показываем «—».
        String planName = appConfig.findPlanOptional(payment.getPlanId())
                .map(AppConfig.Plan::getName)
                .orElse("—");

        // Срок берём из ПОДПИСКИ, а не из строки человека: этот экран показывается СРАЗУ
        // после оплаты и говорит «подписка активна до X» — то есть читает самое свежее состояние,
        // какое только что изменил вебхук. Зеркало в {@code customers} пишется ПОСЛЕ коммита
        // активации, и именно здесь его отставание встретит человека старой датой после успешного
        // платежа — самый тревожный для него момент.
        final Customer payer = sessionUser;
        com.orchestra.subscriptions.service.SubscriptionView paidSub = subscriptionLookup
                .currentFor(payer.getTelegramId())
                .orElseGet(() -> com.orchestra.subscriptions.service.SubscriptionView.ofUser(payer));
        long expiresAtEpoch = paidSub.expiresAt() == null ? 0
                : paidSub.expiresAt().toEpochSecond(ZoneOffset.UTC);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", payment.getStatus().toString());
        body.put("planName", planName);
        body.put("amountKopeks", payment.getAmountKopeks());
        body.put("expiresAtEpoch", expiresAtEpoch);
        body.put("paidAtEpoch", payment.getPaidAt() == null ? 0
                : payment.getPaidAt().toEpochSecond(ZoneOffset.UTC));
        return ResponseEntity.ok(body);
    }

    // --- Promo helpers (копия WebAppController, локализованная для cookie-auth) ---

    /**
     * Резервирует промокод под уже созданный PENDING-платёж и сохраняет его на строке платежа.
     * Слот кода занимается ЗДЕСЬ, вместе со счётом, а не на webhook'е SUCCEEDED: read-only-проверку
     * N параллельных чекаутов проходили все и все получали скидку (см.
     * {@code PromoCodeService.reserveForCheckout}). Если счёт создать не удалось — резерв снимаем.
     */
    private AccountPromoApply accountApplyPromoOrError(String rawPromoCode, Payment payment,
                                                       long baseKopeks, long telegramId) {
        if (rawPromoCode == null || rawPromoCode.trim().isEmpty()) {
            return AccountPromoApply.ok(baseKopeks);
        }
        PromoCodeService.PromoResult result = promoCodeService.reserveForCheckout(
                rawPromoCode, baseKopeks, telegramId, payment.getId());
        if (!result.valid()) {
            payment.setStatus(Payment.Status.CANCELED);
            paymentRepository.save(payment);
            log.info("Promo rejected (account): code={} reason={} telegramId={}",
                    rawPromoCode, result.reason(), telegramId);
            return AccountPromoApply.fail(result.reason());
        }
        payment.setPromoCodeUsed(result.code());
        if (result.discountKopeks() > 0) {
            payment.setAmountKopeks(result.finalAmountKopeks());
        }
        paymentRepository.save(payment);
        return AccountPromoApply.ok(result.finalAmountKopeks());
    }

    private record AccountPromoApply(boolean ok, long chargeKopeks, String reason) {
        static AccountPromoApply ok(long kopeks) { return new AccountPromoApply(true, kopeks, null); }
        static AccountPromoApply fail(String reason) { return new AccountPromoApply(false, 0, reason); }
    }



    /**
     * Публичный endpoint запроса magic-link по email. Главный сценарий восстановления:
     * юзер потерял Telegram, открывает /account/login, вводит email — получает письмо.
     * <p>
     * <b>Защита от user enumeration:</b> возвращаем одинаковый 200 «если адрес привязан…»
     * и для known, и для unknown email. Иначе атакующий через перебор смог бы выяснить
     * какие именно email привязаны к аккаунтам. Логируем разницу только в WARN на сервере.
     * <p>
     * <b>Защита от спама:</b> rate-limit по IP (1/мин, 5/час) — атакующий из одного
     * источника не переберёт большой список email'ов. Второй лимит — на сам адрес, но
     * он живёт в {@link AccountMagicLinkAsyncSender}: лимит по IP не мешает пулу адресов
     * бомбить один ящик, а это жалобы на спам и репутация отправителя. Здесь, в
     * синхронной части, проверять адрес нельзя — она выполняется только для known email
     * и разница по времени выдала бы, что адрес привязан к аккаунту.
     * <p>
     * <b>Только JSON.</b> {@code consumes} здесь несёт защитную нагрузку, а не косметику:
     * обычной HTML-формой с чужого сайта такой запрос не отправить (формы умеют только
     * urlencoded/multipart/text-plain), а fetch с {@code application/json} браузер сначала
     * спросит preflight'ом, которого для {@code /auth*} нет в CORS-конфиге. Без этого
     * замка чужой сайт мог бы втихую заказать ссылку на СВОЙ адрес из браузера жертвы,
     * подсадить туда nonce-cookie — и следующий шаг ({@code GET /auth}) пустил бы жертву
     * в чужой кабинет без подтверждения.
     */
    /** DTO для {@code POST /auth/request}. Один типобезопасный record вместо {@code Map<String, Object>}. */
    public record AuthRequestBody(String email) {}

    @PostMapping(value = "/auth/request", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> requestMagicLink(
            @RequestBody(required = false) AuthRequestBody body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String raw = body == null || body.email() == null ? "" : body.email();
        if (raw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_format",
                    "message", "Введите email"));
        }
        String email = raw.trim().toLowerCase();
        if (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_format",
                    "message", "Похоже, в адресе ошибка. Проверьте написание."));
        }

        // Rate-limit по IP. Лимит срабатывает ДО lookup'а в БД, чтобы атакующий не
        // мог ни через timing вычислить known, ни DDoS-ить через перебор.
        String ip = ClientIpResolver.resolve(request, appConfig.getTrustedProxyIps());
        MagicLinkRateLimiter.Decision decision = magicLinkRateLimiter.tryAcquire("ip:" + ip);
        if (!decision.allowed()) {
            log.info("Rate-limit hit on /auth/request for ip={} reason={}", ip, decision.reason());
            // Не раскрываем cooldown vs max_per_hour детально — generic «слишком много».
            return ResponseEntity.status(429).body(Map.of(
                    "error", "rate_limited",
                    "retryAfterSeconds", decision.retryAfterSeconds(),
                    "message", "Слишком много запросов. Попробуйте позже."));
        }

        // Nonce-cookie: помечаем БРАУЗЕР, который просит ссылку, чтобы GET /auth мог отличить
        // «открыл тот же браузер» от «открыли где-то ещё». Ставится ДО поиска юзера и
        // одинаково для known и unknown адреса: наличие Set-Cookie в ответе иначе само стало
        // бы ответом на вопрос «а есть ли такой аккаунт». Срок — как у токена: раньше него
        // cookie протухать не должна, дольше — не нужна.
        String browserNonce = newSecret();
        ResponseCookie nonceCookie = ResponseCookie.from(NONCE_COOKIE, browserNonce)
                .path(AUTH_COOKIE_PATH)
                .maxAge(MagicLinkService.TOKEN_TTL)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, nonceCookie.toString());

        Customer user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // Не сливаем «такого email нет» — отвечаем тем же, что и в success-case.
            log.warn("/auth/request for unknown email ip={}", ip);
            return ResponseEntity.ok(Map.of("message", GENERIC_REQUEST_MESSAGE));
        }

        // Async-отправка: контроллер отдаёт 200 моментально и для known, и для
        // unknown email — атакующий не может по latency понять что email привязан.
        // Sync send: known ~200-800мс vs unknown ~5-20мс → tell-tale через timing.
        // Nonce уезжает вместе с юзером: токен создаётся уже в фоне, и привязать его к
        // браузеру может только тот, кто его создаёт.
        asyncSender.sendMagicLink(user, browserNonce);
        log.info("Magic-link send-async scheduled for telegramId={} ip={}",
                user.getTelegramId(), ip);
        return ResponseEntity.ok(Map.of("message", GENERIC_REQUEST_MESSAGE));
    }

    /**
     * Выход из ЛК: инкремент {@code session_version} + сброс session-cookie.
     * <p>
     * Одной очистки cookie мало. Cookie stateless: снятая копия остаётся валидной
     * подписью все 7 дней TTL, и «выход» на чужом ноутбуке не отзывал бы ровно то,
     * ради чего кнопку и нажимают. Инкремент версии — единственный механизм отзыва
     * у stateless-сессии, тот же, что при смене/удалении email
     * (см. {@code EmailSettingsService.updateEmail}): {@link #authenticate} сверяет
     * версию из cookie с версией в БД и после инкремента отдаёт 401.
     * <p>
     * <b>Следствие: выход глобальный.</b> Версия одна на человека, поэтому выход
     * закрывает ЛК во всех браузерах, где он входил по magic-link, — намеренно:
     * частичный выход не решает задачу «у кого-то осталась моя сессия».
     * <p>
     * Безопасно вызывать без cookie/с протухшим — отзывать нечего, cookie всё
     * равно чистим (Max-Age=0), ответ тот же. Повторный вызов идемпотентен по
     * смыслу: лишний инкремент никого не разлогинивает второй раз.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(
            @CookieValue(value = AccountSessionService.COOKIE_NAME, required = false) String sessionCookie,
            HttpServletResponse response) {

        Customer user = authenticate(sessionCookie).orElse(null);
        if (user != null) {
            Integer prevSv = user.getSessionVersion();
            user.setSessionVersion((prevSv != null ? prevSv : 0) + 1);
            userRepository.save(user);
            log.info("Account sessions revoked on logout for telegramId={} newSv={}",
                    user.getTelegramId(), user.getSessionVersion());
        }

        ResponseCookie clearCookie = ResponseCookie.from(AccountSessionService.COOKIE_NAME, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    /**
     * User-Agent для лога: обрезаем до {@link #UA_LOG_MAX} и вычищаем управляющие символы.
     * Заголовок приходит от клиента, то есть это чужой текст в нашей строке лога — без
     * чистки в файл можно было бы подмешать перевод строки и нарисовать поддельную запись.
     */
    private static String shortUserAgent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > UA_LOG_MAX ? cleaned.substring(0, UA_LOG_MAX) + "…" : cleaned;
    }

    private static ResponseEntity<Void> redirect(String path) {
        return redirect(path, HttpStatus.FOUND);
    }

    private static ResponseEntity<Void> redirect(String path, HttpStatus status) {
        return ResponseEntity.status(status).location(URI.create(path)).build();
    }

    // =====================================================================
    //  Страница подтверждения входа (интерстишиал перед выдачей сессии)
    // =====================================================================

    /** Текст на повторный показ страницы: подтверждение не сложилось, но токен цел. */
    private static final String CONFIRM_RETRY_NOTICE =
            "Подтверждение не прошло: страница открыта слишком давно или браузер не сохранил "
            + "служебную cookie. Нажмите кнопку ещё раз.";

    /**
     * Отдаёт страницу подтверждения и кладёт в браузер одноразовое значение
     * {@link #CONFIRM_COOKIE}, копия которого уезжает скрытым полем формы.
     * <p>
     * Страница собирается здесь, а не лежит в {@code static/account/}, по двум причинам:
     * в неё нужно подставить адрес получателя и два секрета (шаблонизатора в проекте
     * нет), и форма должна работать без JavaScript — письма открывают во встроенных
     * браузерах почтовых клиентов, где скрипты живут не всегда. Стили и фон берутся из
     * той же статики ЛК, поэтому страница выглядит как остальной кабинет.
     */
    private ResponseEntity<String> confirmPage(String token, String maskedEmail, String notice,
                                               HttpServletResponse response) {
        String csrf = newSecret();
        ResponseCookie confirmCookie = ResponseCookie.from(CONFIRM_COOKIE, csrf)
                .path(AUTH_COOKIE_PATH)
                .maxAge(MagicLinkService.TOKEN_TTL)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, confirmCookie.toString());

        return ResponseEntity.ok()
                // charset обязателен: без него String-конвертер Spring'а пишет тело в
                // ISO-8859-1 и русский текст страницы превращается в мусор.
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .header("Content-Security-Policy", CONFIRM_PAGE_CSP)
                .body(confirmHtml(token, maskedEmail, notice, csrf));
    }

    /**
     * Разметка страницы подтверждения. Всё, что подставляется, проходит через
     * {@link #htmlEscape}: адрес приходит из БД, а токен и csrf — из запроса, и ни одному
     * из них нельзя дать закрыть атрибут и дописать свой тег.
     */
    private static String confirmHtml(String token, String maskedEmail, String notice, String csrf) {
        String noticeBlock = notice == null || notice.isBlank() ? ""
                : "<p class=\"card-sub login-result login-result--err\">" + htmlEscape(notice) + "</p>";
        return """
                <!DOCTYPE html>
                <html lang="ru">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Подтверждение входа — Orchestra</title>
                    <meta name="robots" content="noindex,nofollow">
                    <link rel="stylesheet" href="/app/fonts/fonts.css">
                    <link rel="stylesheet" href="/account/style.css?v=12">
                </head>
                <body>
                    <!-- Matrix-rain бэкграунд (см. account/index.html) -->
                    <div class="bg-dvd" id="bg-dvd" aria-hidden="true"></div>

                    <main class="account">
                        <header class="account-header">
                            <div class="brand"><b>Orchestra</b></div>
                        </header>

                        <section class="content">
                            <div class="card">
                                <div class="card-value-big">Подтвердите вход</div>
                                <p class="card-sub">
                                    Ссылка для входа отправлена на адрес <b>%s</b>.
                                </p>
                                <p class="card-sub">
                                    Она открыта в другом браузере или на другом устройстве — не в том,
                                    откуда её запрашивали. Поэтому вход завершается вручную.
                                </p>
                                %s
                                <form method="post" action="/auth/confirm">
                                    <input type="hidden" name="token" value="%s">
                                    <input type="hidden" name="csrf" value="%s">
                                    <button type="submit" class="btn-primary">Войти в кабинет</button>
                                </form>
                                <p class="card-sub hint">
                                    Ссылка остаётся действительной: пока кнопка не нажата, использованной
                                    она не считается.
                                </p>
                                <p class="card-sub hint">
                                    Адрес выше Вам не принадлежит — закройте страницу. Без нажатия кнопки
                                    вход не выполняется.
                                </p>
                            </div>
                        </section>
                    </main>

                    <script src="/account/bg.js?v=1"></script>
                </body>
                </html>
                """.formatted(htmlEscape(maskedEmail), noticeBlock, htmlEscape(token), htmlEscape(csrf));
    }

    /** Экранирование под HTML-текст и под значение атрибута в кавычках. */
    private static String htmlEscape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** 32 случайных байта в base64url — значение nonce- и confirm-cookie. */
    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Сброс служебной cookie входа: то же имя и path, пустое значение, Max-Age=0. */
    private static String clearedAuthCookie(String name) {
        return ResponseCookie.from(name, "")
                .path(AUTH_COOKIE_PATH)
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build()
                .toString();
    }

    /** Сравнение секретов за постоянное время — по времени ответа подбирать нечего. */
    private static boolean secretsEqual(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Похоже ли, что URL открывает не человек, а предзагрузка/предпросмотр ссылки.
     * Заголовки шлют сами клиенты: {@code Sec-Purpose} — Chrome и другие Chromium,
     * {@code Purpose}/{@code X-Purpose} — Safari и старые сборки, {@code X-Moz} — Firefox.
     * <p>
     * Такому запросу сессию не выдаём и токен не гасим: иначе предзагрузка ссылки из
     * письма съедала бы вход раньше, чем человек по ней кликнет. Заголовка нет — считаем,
     * что открыл человек: угадывать по User-Agent мы не будем, а ошибка в эту сторону
     * стоит одного лишнего подтверждения.
     */
    private static boolean isLinkPreload(HttpServletRequest request) {
        String secPurpose = request.getHeader("Sec-Purpose");
        if (secPurpose != null && secPurpose.toLowerCase(Locale.ROOT).contains("prefetch")) {
            return true;
        }
        return isPrefetchMarker(request.getHeader("Purpose"))
                || isPrefetchMarker(request.getHeader("X-Purpose"))
                || isPrefetchMarker(request.getHeader("X-Moz"));
    }

    private static boolean isPrefetchMarker(String headerValue) {
        if (headerValue == null) {
            return false;
        }
        String value = headerValue.trim().toLowerCase(Locale.ROOT);
        return value.equals("prefetch") || value.equals("preview") || value.equals("prerender");
    }

    /**
     * Парсит signed cookie, грузит юзера из БД, сверяет {@code session_version} с тем
     * что закодирован в cookie. Возвращает юзера если всё совпало, иначе пусто (caller
     * отдаёт 401 без раскрытия причины — не сливаем «нет такого юзера» vs «сессия
     * отозвана» vs «cookie протух» vs «подделка»).
     * <p>
     * Session-version mismatch = юзер сменил/удалил email после issue'а cookie, что
     * инкрементит {@code session_version} в БД (см. {@code WebAppController.updateEmail}).
     * Старый cookie с предыдущим sv валидится подписью, но не проходит этот гард —
     * stateless server-side revocation.
     * <p>
     * Доп. лог при sv-mismatch — info-level, ops-видимость без шумного WARN'а (это
     * нормальный сценарий «юзер сменил email из Mini App пока был залогинен в ЛК»).
     */
    private Optional<Customer> authenticate(String sessionCookie) {
        AccountSessionService.Session session;
        try {
            session = sessionService.parse(sessionCookie);
        } catch (AccountSessionService.InvalidSessionException e) {
            return Optional.empty();
        }
        Customer user = userRepository.findByTelegramId(session.telegramId()).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        Integer currentSv = user.getSessionVersion();
        int cookieSv = session.sessionVersion();
        // currentSv не должен быть null после миграции 020 (NOT NULL DEFAULT 0), но
        // defensively — если в результате какой-то ошибки получилось null, считаем 0.
        int actualSv = currentSv != null ? currentSv : 0;
        if (actualSv != cookieSv) {
            log.info("Session revoked by version mismatch: telegramId={} cookieSv={} actualSv={}",
                    session.telegramId(), cookieSv, actualSv);
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
