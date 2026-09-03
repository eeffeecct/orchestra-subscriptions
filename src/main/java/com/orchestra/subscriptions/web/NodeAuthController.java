package com.orchestra.subscriptions.web;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.service.NodeAuthService;
import com.orchestra.subscriptions.service.OnlineTracker;
import com.orchestra.subscriptions.web.api.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Эндпоинт авторизации для узлов раздачи доступа.
 *
 * <p>Узел на каждое подключение шлёт сюда {@code {"auth": "<учётные данные подписчика>"}} и
 * ждёт {@code {"ok": true, "id": "<кто это>"}} либо {@code {"ok": false}}. Как выводятся сами
 * учётные данные — см. {@link NodeAuthService}.
 *
 * <p><b>Аутентификация вызывающего.</b> Подтверждается неугадываемым сегментом пути
 * ({@code app.node-auth.auth-path-secret}) — узел может не уметь слать кастомные заголовки.
 * Не совпал — 404, как будто маршрута нет: сканеры не должны понимать, что тут что-то есть.
 *
 * <p><b>Доступность.</b> Пока приложение недоступно (рестарт при деплое), НОВЫЕ подключения
 * к узлам не проходят; уже установленные сессии не рвутся — узел спрашивает только на
 * коннекте. Это осознанный размен: «fail-closed» здесь безопаснее, чем пускать без проверки.
 */
@Slf4j
@RestController
@RequestMapping("/node")
@RequiredArgsConstructor
public class NodeAuthController {

    private final NodeAuthService nodeAuthService;
    private final OnlineTracker onlineTracker;
    private final AppConfig appConfig;

    @PostMapping("/auth/{pathSecret}")
    public ResponseEntity<Map<String, Object>> auth(@PathVariable String pathSecret,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        if (!nodeAuthService.pathSecretMatches(pathSecret)) {
            return ResponseEntity.notFound().build();
        }
        Object rawAuth = body == null ? null : body.get("auth");
        NodeAuthService.AuthResult result =
                nodeAuthService.verify(rawAuth == null ? null : rawAuth.toString());

        // Каждое подключение проходит здесь — единственное место, где мы вообще видим, что
        // человек сейчас в сети (провайдер о таких узлах не знает, см. OnlineTracker).
        // Адрес запроса = адрес самого узла: по нему в /online видно, кто где сидит.
        if (result.ok()) {
            try {
                onlineTracker.recordNodeSession(
                        result.id(), ClientIpResolver.resolve(request, appConfig.getTrustedProxyIps()));
            } catch (Exception e) {
                // Учёт онлайна не должен мешать людям подключаться.
                log.debug("Online tracking failed for node auth", e);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", result.ok());
        response.put("id", result.id());
        return ResponseEntity.ok(response);
    }
}
