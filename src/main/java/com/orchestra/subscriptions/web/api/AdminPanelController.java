package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.AdminLoginTokenService;
import com.orchestra.subscriptions.service.AdminPanelService;
import com.orchestra.subscriptions.service.AdminPanelService.PageView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Веб-админка владельца: вход по ссылке из бота и read-only срезы базы.
 * <p>
 * <b>Почему пути живут под {@code /account} и {@code /api/account}.</b> Клиентский вход из
 * России идёт через Caddy на эстонской ноде, и тот проксирует на main закрытый список
 * префиксов ({@code /sub/*, /app/*, /api/*, /auth*, /account*} — DEPLOYMENT.md §14.9).
 * Собственный префикс {@code /admin/} на фронте отдавал бы 404, то есть панель открывалась бы
 * только с IP main — а его из РФ режут. Заодно на эти пути уже настроены заголовки в
 * {@link SecurityHeadersFilter}.
 * <p>
 * <b>Доступ.</b> Cookie {@link AdminSessionService#COOKIE_NAME} + сверка, что это владелец
 * ({@code app.telegram.owner-id}) и что его {@code session_version} не менялся с момента
 * выдачи. Owner-id не задан — панель недоступна никому, как и админ-команды бота.
 * <p>
 * <b>Только чтение.</b> Единственные не-GET методы здесь — вход и выход.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminPanelController {

    /** Максимум строк в CSV-выгрузке: защита от «выгрузить журнал целиком» на 1 ГБ RAM. */
    private static final int EXPORT_LIMIT = 20_000;
    private static final int EXPORT_CHUNK = 200;

    private final AppConfig appConfig;
    private final AdminSessionService sessionService;
    private final AdminLoginTokenService loginTokenService;
    private final AdminPanelService adminPanelService;
    private final CustomerRepository userRepository;
    private final RequestRateLimiter rateLimiter;

    // =====================================================================================
    // Вход и выход
    // =====================================================================================

    /**
     * Переход по ссылке из бота: меняет одноразовый токен на сессионный cookie и уводит в панель.
     * Любая неудача — редирект на страницу входа с объяснением, а не 401 в пустое окно.
     */
    @GetMapping("/account/admin/enter")
    public ResponseEntity<Void> enter(@RequestParam(value = "token", required = false) String token,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        String ip = ClientIpResolver.resolve(request, appConfig.getTrustedProxyIps());
        if (!rateLimiter.tryAcquire("admin-enter:" + ip, 30, Duration.ofMinutes(10))) {
            log.warn("Admin panel enter rate-limited ip={}", ip);
            return redirect("/account/admin/login.html?e=rate");
        }
        Long ownerId = ownerId();
        Optional<Long> resolved = loginTokenService.resolve(token);
        if (ownerId == null || resolved.isEmpty() || !ownerId.equals(resolved.get())) {
            log.warn("Admin panel enter rejected ip={} (token invalid or not owner)", ip);
            return redirect("/account/admin/login.html?e=token");
        }

        int sv = ownerSessionVersion(ownerId);
        ResponseCookie cookie = ResponseCookie.from(AdminSessionService.COOKIE_NAME,
                        sessionService.issue(ownerId, sv))
                .path("/")
                .maxAge(AdminSessionService.TTL)
                .httpOnly(true)
                .secure(true)
                // Strict, а не Lax: в панель незачем попадать переходом с чужого сайта, а
                // Lax отдал бы cookie на любой сторонний GET-переход в неё.
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        log.info("Admin panel session issued for owner telegramId={} ip={}", ownerId, ip);
        return redirect("/account/admin/");
    }

    /** Гасит cookie панели в этом браузере. Сессии в других браузерах не трогает. */
    @PostMapping("/api/account/admin/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AdminSessionService.COOKIE_NAME, "")
                .path("/").maxAge(0).httpOnly(true).secure(true).sameSite("Strict").build().toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // =====================================================================================
    // Срезы
    // =====================================================================================

    @GetMapping("/api/account/admin/overview")
    public ResponseEntity<?> overview(@CookieValue(value = AdminSessionService.COOKIE_NAME,
            required = false) String session) {
        return guarded(session, adminPanelService::overview);
    }

    /**
     * Состояние флота: панель AccessProvider + прямая проба {@code :443} + мониторинг. Тот же
     * источник, что у команды {@code /nodes} в боте.
     * <p>
     * Отдельный лимит на частоту: ответ стоит одного HTTP к панели и по TCP-пробе на ноду,
     * поэтому зажатая кнопка «Обновить» не должна превращаться в обстрел собственных нод.
     * 30 запросов за 5 минут — это чаще, чем раз в 10 секунд, руками столько не нужно.
     */
    @GetMapping("/api/account/admin/nodes")
    public ResponseEntity<?> nodes(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            HttpServletRequest request) {
        if (!authorized(session)) {
            return unauthorized();
        }
        String ip = ClientIpResolver.resolve(request, appConfig.getTrustedProxyIps());
        if (!rateLimiter.tryAcquire("admin-nodes:" + ip, 30, Duration.ofMinutes(5))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        return ResponseEntity.ok(adminPanelService.nodes());
    }

    @GetMapping("/api/account/admin/users")
    public ResponseEntity<?> users(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String dir,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.users(q, mode, sort, dir, page, size));
    }

    @GetMapping("/api/account/admin/users/{telegramId}")
    public ResponseEntity<?> userCard(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @PathVariable long telegramId) {
        if (!authorized(session)) {
            return unauthorized();
        }
        return adminPanelService.userCard(telegramId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "not_found")));
    }

    /**
     * Дополнение к карточке: то, что о человеке знает панель AccessProvider ({@code online_at},
     * трафик, статус аккаунта). Отдельным запросом, потому что это поход наружу — карточка
     * не должна ждать панель и не должна из-за неё не открыться. Второй такой запрос в
     * админке — {@code /nodes}; остальные срезы ходят только в свою базу.
     */
    @GetMapping("/api/account/admin/users/{telegramId}/panel")
    public ResponseEntity<?> userPanelView(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @PathVariable long telegramId) {
        return guarded(session, () -> adminPanelService.panelViewFor(telegramId));
    }

    @GetMapping("/api/account/admin/payments")
    public ResponseEntity<?> payments(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "provider", required = false) List<String> provider,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String dir,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.payments(q, status, provider, sort, dir, page, size));
    }

    @GetMapping("/api/account/admin/subscriptions")
    public ResponseEntity<?> subscriptions(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "telegramId", required = false) Long telegramId,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String dir,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.subscriptions(telegramId, state, sort, dir, page, size));
    }

    @GetMapping("/api/account/admin/devices")
    public ResponseEntity<?> devices(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "telegramId", required = false) Long telegramId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String dir,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.devices(telegramId, status, sort, dir, page, size));
    }

    @GetMapping("/api/account/admin/ledger")
    public ResponseEntity<?> ledger(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "telegramId", required = false) Long telegramId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.ledger(telegramId, page, size));
    }

    @GetMapping("/api/account/admin/referrals")
    public ResponseEntity<?> referrals(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.referrals(page, size));
    }

    @GetMapping("/api/account/admin/promocodes")
    public ResponseEntity<?> promoCodes(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session) {
        return guarded(session, adminPanelService::promoCodes);
    }

    @GetMapping("/api/account/admin/promousages")
    public ResponseEntity<?> promoUsages(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.promoUsages(page, size));
    }

    @GetMapping("/api/account/admin/grants")
    public ResponseEntity<?> grants(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.grants(page, size));
    }

    @GetMapping("/api/account/admin/magiclinks")
    public ResponseEntity<?> magicLinks(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.magicLinks(page, size));
    }

    @GetMapping("/api/account/admin/offers")
    public ResponseEntity<?> offers(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return guarded(session, () -> adminPanelService.offerAcceptances(page, size));
    }

    // =====================================================================================
    // Выгрузка
    // =====================================================================================

    /**
     * Тот же срез, что на экране, но CSV-файлом — чтобы считать в таблице то, чего в панели нет.
     * Заголовки колонок берутся из компонентов record'а, поэтому файл не расходится с API.
     */
    @GetMapping("/api/account/admin/export/{table}")
    public ResponseEntity<?> export(
            @CookieValue(value = AdminSessionService.COOKIE_NAME, required = false) String session,
            @PathVariable String table,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "provider", required = false) List<String> provider,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "telegramId", required = false) Long telegramId) {
        if (!authorized(session)) {
            return unauthorized();
        }
        // Фильтры прокидываются те же, что на экране: файл обязан совпадать с тем, что человек
        // видел, когда нажимал «CSV» — иначе выгрузка тихо шире таблицы.
        List<?> rows = switch (table) {
            case "users" -> collectAll(p -> adminPanelService.users(q, mode, null, null, p, EXPORT_CHUNK));
            case "payments" -> collectAll(p -> adminPanelService.payments(q, status, provider, null, null, p, EXPORT_CHUNK));
            case "subscriptions" -> collectAll(p -> adminPanelService.subscriptions(telegramId, state, null, null, p, EXPORT_CHUNK));
            case "devices" -> collectAll(p -> adminPanelService.devices(telegramId, firstOrNull(status), null, null, p, EXPORT_CHUNK));
            case "ledger" -> collectAll(p -> adminPanelService.ledger(telegramId, p, EXPORT_CHUNK));
            case "referrals" -> collectAll(p -> adminPanelService.referrals(p, EXPORT_CHUNK));
            case "promousages" -> collectAll(p -> adminPanelService.promoUsages(p, EXPORT_CHUNK));
            case "promocodes" -> adminPanelService.promoCodes();
            case "grants" -> collectAll(p -> adminPanelService.grants(p, EXPORT_CHUNK));
            case "magiclinks" -> collectAll(p -> adminPanelService.magicLinks(p, EXPORT_CHUNK));
            case "offers" -> collectAll(p -> adminPanelService.offerAcceptances(p, EXPORT_CHUNK));
            default -> null;
        };
        if (rows == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown_table"));
        }
        byte[] body = csv(rows).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"orchestra-" + table + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /** У устройств фильтр статуса одиночный, а в URL приезжает списком — берём первый. */
    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private <T> List<T> collectAll(IntFunction<PageView<T>> fetcher) {
        List<T> all = new ArrayList<>();
        int page = 0;
        while (all.size() < EXPORT_LIMIT) {
            PageView<T> chunk = fetcher.apply(page);
            all.addAll(chunk.items());
            if (chunk.items().isEmpty() || page + 1 >= chunk.totalPages()) {
                break;
            }
            page++;
        }
        return all.size() > EXPORT_LIMIT ? all.subList(0, EXPORT_LIMIT) : all;
    }

    /**
     * CSV с разделителем «;» и BOM: Excel в русской локали открывает такой файл двойным щелчком
     * без диалога импорта, а с запятой и без BOM — сваливает всю строку в первую ячейку.
     */
    static String csv(List<?> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        if (rows.isEmpty()) {
            return sb.toString();
        }
        RecordComponent[] components = rows.get(0).getClass().getRecordComponents();
        if (components == null) {
            return sb.toString();
        }
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(csvCell(components[i].getName()));
        }
        sb.append('\n');
        for (Object row : rows) {
            for (int i = 0; i < components.length; i++) {
                if (i > 0) sb.append(';');
                Object value;
                try {
                    value = components[i].getAccessor().invoke(row);
                } catch (ReflectiveOperationException e) {
                    value = null;
                }
                sb.append(csvCell(value == null ? "" : String.valueOf(value)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String csvCell(String raw) {
        String escaped = raw.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    // =====================================================================================
    // Доступ
    // =====================================================================================

    /** Общая обёртка: не владелец — 401 и ничего не считаем. */
    private ResponseEntity<?> guarded(String sessionCookie, Supplier<?> body) {
        if (!authorized(sessionCookie)) {
            return unauthorized();
        }
        return ResponseEntity.ok(body.get());
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    /**
     * Три условия сразу: подпись cookie цела и не протухла, внутри — id владельца из конфига,
     * и его {@code session_version} не менялся с момента выдачи (то есть «выйти на всех
     * устройствах» в кабинете закрывает и панель).
     */
    private boolean authorized(String sessionCookie) {
        Long ownerId = ownerId();
        if (ownerId == null) {
            return false;
        }
        AdminSessionService.Session session;
        try {
            session = sessionService.parse(sessionCookie);
        } catch (AccountSessionService.InvalidSessionException e) {
            return false;
        }
        if (session.telegramId() != ownerId) {
            log.warn("Admin panel: valid cookie for non-owner telegramId={}", session.telegramId());
            return false;
        }
        return session.sessionVersion() == ownerSessionVersion(ownerId);
    }

    private int ownerSessionVersion(long ownerId) {
        return userRepository.findByTelegramId(ownerId)
                .map(Customer::getSessionVersion)
                .map(v -> v == null ? 0 : v)
                // Владельца может не быть в customers (не открывал бот) — тогда отзывать нечего
                // и версия всегда 0. Выдача и проверка используют одно и то же значение.
                .orElse(0);
    }

    private Long ownerId() {
        return appConfig.getTelegram() == null ? null : appConfig.getTelegram().getOwnerId();
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
