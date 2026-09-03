package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.service.OnlineTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Приёмник отчётов о живых соединениях от узлов-релеев.
 *
 * <p><b>Зачем.</b> Часть узлов работает мимо провайдера доступа, поэтому он про них ничего не
 * знает и показывает «онлайн 0». Считать соединения умеет только сам узел — он раз в минуту
 * присылает сюда счётчики, а админка и команда {@code /online} их показывают.
 *
 * <p><b>Почему только счётчики, а не имена.</b> На таких узлах соединения не разложены по
 * подписчикам: узел видит порты и адреса, но не учётные записи. Отдаём число соединений на
 * порт и количество уникальных клиентов — большего там просто нет.
 *
 * <p><b>Аутентификация.</b> Заголовок {@code X-Node-Token} сравнивается с
 * {@code app.node-stats-token} побайтово в постоянное время. Токен не задан — 404, как будто
 * маршрута нет (та же логика, что у {@code /node/auth}).
 */
@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class NodeStatsController {

    private final OnlineTracker onlineTracker;
    private final AppConfig appConfig;

    @PostMapping("/node-stats")
    public ResponseEntity<Map<String, Object>> nodeStats(
            @RequestHeader(value = "X-Node-Token", required = false) String token,
            @RequestBody(required = false) Map<String, Object> body) {

        String expected = appConfig.getNodeStatsToken();
        if (expected == null || expected.isBlank() || !constantTimeEquals(expected, token)) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Object raw = body == null ? null : body.get("counts");
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v instanceof Number n) {
                    counts.put(k.toString(), n.intValue());
                }
            });
        }
        int unique = body != null && body.get("unique") instanceof Number n ? n.intValue() : 0;
        onlineTracker.recordRelay(counts, unique);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
