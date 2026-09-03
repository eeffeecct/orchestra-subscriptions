package com.orchestra.subscriptions.web;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.NodeAuthService;
import com.orchestra.subscriptions.service.SubscriptionLookupService;
import com.orchestra.subscriptions.service.SubscriptionView;
import com.orchestra.subscriptions.util.TokenMasker;
import com.orchestra.subscriptions.web.api.ClientIpResolver;
import com.orchestra.subscriptions.web.api.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Выдача параметров доступа клиентскому приложению.
 *
 * <p>Единственный публичный эндпоинт, который клиент подписчика опрашивает сам:
 * <pre>
 *   GET /sub/&lt;token&gt;  →  text/plain, base64(строки доступа, по одной на узел)
 * </pre>
 * Токен непрозрачный и выдаётся {@link com.orchestra.subscriptions.access.AccessProvider} —
 * он же и является аутентификацией: своего пароля у этой ссылки нет, поэтому её перевыпуск
 * («выйти со всех устройств») мгновенно обесценивает утёкшую копию.
 *
 * <p><b>Почему ответ не просто «список серверов».</b> Через этот же запрос проходят три
 * решения, каждое из которых иначе всплыло бы у человека как «сервис сломался»:
 * <ul>
 *   <li><b>Учёт устройств.</b> Клиент шлёт {@code x-hwid} на каждом обновлении — здесь
 *       регистрируется новое устройство и отсекается лишнее сверх лимита тарифа.</li>
 *   <li><b>Истёкшая подписка.</b> Вместо голого 404 (который клиент показывает как ошибку
 *       сети и переспрашивает годами) отдаём строку-уведомление: человек читает её прямо
 *       в списке серверов и понимает, что надо продлить.</li>
 *   <li><b>Замороженная подписка.</b> Формулировка отдельная и это принципиально: время не
 *       вышло, оно лежит в остатке, и слово «истекла» было бы ложью про деньги человека.</li>
 * </ul>
 *
 * <p><b>Защита.</b> Потолок одновременных запросов и лимит на IP: клиенты легитимно
 * опрашивают подписку по расписанию, но флуд по этому маршруту не должен выесть весь пул
 * Tomcat — он публичный и его адрес известен любому подписчику.
 */
@Slf4j
@RestController
@RequestMapping("/sub")
@RequiredArgsConstructor
public class AccessDeliveryController {

    /** Потолок одновременных запросов: флуд не должен выесть весь Tomcat-пул. */
    private static final int MAX_CONCURRENT = 32;
    /** Лимит запросов на IP в минуту. Щедрый — клиенты легитимно опрашивают подписку. */
    private static final int MAX_REQ_PER_MIN = 60;
    /** Имя, которое клиентское приложение показывает вместо хоста подписки. */
    private static final String PROFILE_TITLE = "Orchestra";

    private final AppConfig appConfig;
    private final RequestRateLimiter rateLimiter;
    private final DeviceTrackingService deviceTrackingService;
    private final NodeAuthService nodeAuthService;
    private final SubscriptionLookupService subscriptionLookup;

    private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT);

    @GetMapping("/{token}")
    public ResponseEntity<String> accessBundle(
            @PathVariable String token,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestHeader(value = "x-hwid", required = false) String hwid,
            @RequestHeader(value = "x-device-model", required = false) String deviceModel,
            @RequestHeader(value = "x-device-os", required = false) String deviceOs,
            @RequestHeader(value = "x-ver-os", required = false) String osVersion,
            HttpServletRequest request) {

        String clientIp = ClientIpResolver.resolve(request, appConfig.getTrustedProxyIps());
        if (!rateLimiter.tryAcquire("access:" + clientIp, MAX_REQ_PER_MIN, Duration.ofMinutes(1))) {
            return ResponseEntity.status(429).build();
        }
        if (!concurrency.tryAcquire()) {
            return ResponseEntity.status(503).build();
        }
        try {
            return serve(token, userAgent, hwid, deviceModel, deviceOs, osVersion);
        } finally {
            concurrency.release();
        }
    }

    private ResponseEntity<String> serve(String token, String userAgent, String hwid,
                                         String deviceModel, String deviceOs, String osVersion) {
        SubscriptionView sub = subscriptionLookup.byToken(token).orElse(null);
        if (sub == null) {
            // Токен неизвестен — отвечаем как отсутствующим маршрутом: перебирать ссылки
            // по коду ответа не должно быть возможно.
            log.debug("Access bundle requested for unknown token {}", TokenMasker.mask(token));
            return ResponseEntity.notFound().build();
        }

        // Учёт устройств идёт ДО проверки срока: даже у истёкшей подписки полезно знать, с
        // каких устройств её ещё опрашивают. Внутри fail-open — сбой учёта отдаёт доступ.
        DeviceTrackingService.Verdict verdict;
        try {
            verdict = deviceTrackingService.onSubscriptionFetch(token,
                    new DeviceTrackingService.DeviceHeaders(hwid, deviceModel, deviceOs, osVersion, userAgent));
        } catch (Exception e) {
            log.warn("Device tracking failed for {} — serving access anyway", TokenMasker.mask(token), e);
            verdict = DeviceTrackingService.Verdict.ALLOW;
        }

        LocalDateTime now = LocalDateTime.now();
        if (verdict == DeviceTrackingService.Verdict.BLOCK) {
            return bundle(sub, List.of(
                    notice("⛔️ Достигнут лимит устройств тарифа"),
                    notice("➡️ Отключите лишнее устройство в боте")), true);
        }
        if (sub.parked()) {
            return bundle(sub, List.of(
                    notice("⏸ Подписка не включена"),
                    notice("➡️ Включите её в боте — остаток дней сохранён")), false);
        }
        if (sub.expiresAt() == null || !sub.expiresAt().isAfter(now)) {
            return bundle(sub, List.of(
                    notice("❌ Подписка истекла"),
                    notice("➡️ Продлите её в боте")), false);
        }

        return bundle(sub, accessLines(sub), false);
    }

    /**
     * Строки доступа для действующей подписки — по одной на узел из {@code app.servers}.
     *
     * <p>Учётные данные персональные и выводятся из подписочного токена
     * ({@link NodeAuthService}), поэтому перевыпуск ссылки отзывает доступ на всех узлах
     * разом, без обхода их по одному. Если авторизация на узлах выключена, отдаём адреса
     * без учётных данных — узлы в этом режиме проверяют подписчиков сами.
     */
    private List<String> accessLines(SubscriptionView sub) {
        String credential = nodeAuthService.authFor(sub).orElse(null);
        List<String> lines = new ArrayList<>();
        for (AppConfig.ServerConfig server : appConfig.getServers() == null
                ? List.<AppConfig.ServerConfig>of() : appConfig.getServers()) {
            String label = (server.getFlag() == null ? "" : server.getFlag() + " ")
                    + (server.getName() == null ? "" : server.getName());
            lines.add("access://"
                    + (credential == null ? "" : credential + "@")
                    + server.getHost() + ":" + server.getPort()
                    + "#" + label.trim());
        }
        return lines;
    }

    /** Строка-заглушка: клиент показывает её в списке серверов как обычный пункт. */
    private static String notice(String text) {
        return "access://0.0.0.0:0#" + text;
    }

    /**
     * Готовый ответ: base64 от строк, как того ждёт клиент, плюс заголовок
     * {@code subscription-userinfo} — по нему приложение рисует срок и остаток, не
     * разбирая тело.
     */
    private ResponseEntity<String> bundle(SubscriptionView sub, List<String> lines, boolean deviceLimitHit) {
        String body = Base64.getEncoder().encodeToString(
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

        long expireEpoch = sub.expiresAt() == null ? 0
                : sub.expiresAt().atZone(ZoneId.systemDefault()).toEpochSecond();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.add("subscription-userinfo", "upload=0; download=0; total=0; expire=" + expireEpoch);
        headers.add("profile-title", PROFILE_TITLE);
        headers.add("profile-update-interval", "12");
        if (deviceLimitHit) {
            headers.add("x-max-devices-reached", "true");
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
