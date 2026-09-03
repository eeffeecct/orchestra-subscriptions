package com.orchestra.subscriptions.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Читает ПУБЛИЧНУЮ статус-страницу Uptime Kuma — единственный источник правды о том, что живо.
 * <p>
 * До этого клиента доступность считал сам бот TCP-коннектом с main, и у двух узлов на UDP
 * (UDP) TCP-пробе взяться неоткуда — им проставили {@code always-online: true}, то есть экран
 * рисовал «Активен» вообще без проверки. Финская нода 14.08.2026 лежала полчаса, а бот
 * показывал её работающей.
 * <p>
 * Берём именно публичную статус-страницу, а не авторизованный API: она отдаёт готовый снимок
 * без токенов, ключ хранить негде и утечь ему неоткуда. Два запроса — список мониторов и
 * пульс; Kuma не отдаёт их вместе.
 * <p>
 * ⚠️ Показываем ровно то, что измеряет Kuma. У hy2-нод её монитор — ICMP-пинг, то есть
 * «машина отвечает», а не «узел принимает подключения». Это честнее прежнего «всегда зелёный», но
 * не доказательство работоспособности протокола.
 */
@Slf4j
@Component
public class UptimeKumaClient {

    /** Сколько ждать Kuma. Она на том же хосте, поэтому секунды тут — уже аномалия. */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final WebClient webClient;
    private final String slug;
    private final boolean enabled;

    public UptimeKumaClient(HttpClient timeoutHttpClient,
                            @Value("${app.status-page.base-url:}") String baseUrl,
                            @Value("${app.status-page.slug:}") String slug,
                            @Value("${app.status-page.enabled:false}") boolean enabled) {
        this.slug = slug;
        this.enabled = enabled && baseUrl != null && !baseUrl.isBlank()
                && slug != null && !slug.isBlank();
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        timeoutHttpClient))
                .baseUrl(this.enabled ? baseUrl : "http://localhost")
                .build();
    }

    /** Настроен ли источник вообще: без него потребители остаются на старом поведении. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Один монитор статус-страницы.
     *
     * @param group    группа на статус-странице («Узлы сервиса», «Сервис»)
     * @param flag     ведущий эмодзи-флаг из названия, если он там есть
     * @param name     название без флага
     * @param up       последний удар пульса успешен; {@code null} — ударов нет вовсе
     * @param uptime24 доля успешных за сутки (0..1); {@code null} — Kuma не посчитала
     * @param pingMs   задержка последнего удара; {@code null} — монитор её не измеряет
     */
    public record Monitor(String group, String flag, String name,
                          Boolean up, Double uptime24, Double pingMs) {}

    /**
     * Снимок статус-страницы. {@code null} — Kuma недоступна или ответила мусором; вызывающий
     * обязан отличать это от «всё лежит»: показать зелёное по неответившему мониторингу так же
     * плохо, как и красное.
     */
    public List<Monitor> fetch() {
        if (!enabled) {
            return null;
        }
        try {
            StatusPage page = get("/api/status-page/" + slug, StatusPage.class);
            Heartbeats beats = get("/api/status-page/heartbeat/" + slug, Heartbeats.class);
            if (page == null || page.getPublicGroupList() == null) {
                return null;
            }
            return merge(page, beats);
        } catch (Exception e) {
            log.warn("Uptime Kuma не ответила ({}): {}", slug, e.getMessage());
            return null;
        }
    }

    private <T> T get(String uri, Class<T> type) {
        return webClient.get().uri(uri).retrieve().bodyToMono(type).block(TIMEOUT);
    }

    /** Package-private ради теста на РЕАЛЬНОМ ответе Kuma: форма чужого API важнее догадок о ней. */
    static List<Monitor> merge(StatusPage page, Heartbeats beats) {
        Map<String, List<Beat>> pulse = beats == null || beats.getHeartbeatList() == null
                ? Map.of() : beats.getHeartbeatList();
        Map<String, Double> uptime = beats == null || beats.getUptimeList() == null
                ? Map.of() : beats.getUptimeList();

        List<Monitor> out = new ArrayList<>();
        for (Group g : page.getPublicGroupList()) {
            if (g.getMonitorList() == null) {
                continue;
            }
            for (MonitorDto m : g.getMonitorList()) {
                String id = String.valueOf(m.getId());
                List<Beat> list = pulse.get(id);
                Beat last = list == null || list.isEmpty() ? null : list.get(list.size() - 1);
                String[] parts = splitFlag(m.getName());
                out.add(new Monitor(g.getName(), parts[0], parts[1],
                        last == null ? null : last.getStatus() == 1,
                        uptime.get(id + "_24"),
                        last == null ? null : last.getPing()));
            }
        }
        return out;
    }

    /**
     * Отделяет ведущий флаг от названия монитора: владелец называет их «🇪🇺 Регион A —
     * Регион B», и флаг на экране показывается отдельной колонкой.
     * <p>
     * Флаг — пара regional indicator symbols (по два code point), плюс поверх бывают
     * обычные эмодзи вроде 🖥 у сервисных мониторов. Берём всё, что не буква и не цифра,
     * до первого пробела.
     */
    public static String[] splitFlag(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        int space = name.indexOf(' ');
        if (space <= 0) {
            return new String[] {"", name};
        }
        String head = name.substring(0, space);
        boolean isSymbol = head.codePoints().noneMatch(Character::isLetterOrDigit);
        return isSymbol
                ? new String[] {head, name.substring(space + 1).trim()}
                : new String[] {"", name};
    }

    // ===== DTO статус-страницы =====

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StatusPage {
        private List<Group> publicGroupList;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Group {
        private String name;
        private List<MonitorDto> monitorList;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MonitorDto {
        private long id;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Heartbeats {
        private Map<String, List<Beat>> heartbeatList = new LinkedHashMap<>();
        private Map<String, Double> uptimeList = new LinkedHashMap<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Beat {
        /** 1 — up, 0 — down, 2 — pending, 3 — maintenance. */
        private int status;
        private String time;
        private Double ping;
    }
}
