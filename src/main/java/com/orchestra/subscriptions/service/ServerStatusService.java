package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Статус узлов сервиса для экрана «Статус серверов» в боте и Mini App ({@code GET /api/servers}).
 * <p>
 * <b>Источник — Uptime Kuma</b> (решение владельца 14.08.2026): экран показывает то же, что
 * мониторинг, и другого мнения о живости нод у сервиса больше нет.
 * <p>
 * Прежний способ — TCP-коннект с main к {@code host:port} — врал структурно. У двух
 * части узлов трафик идёт по UDP, TCP-пробе взяться неоткуда, и им проставляли
 * {@code always-online: true}: экран рисовал «Активен» вообще без проверки. 14.08.2026 финская
 * нода лежала полчаса (не отвечала ни на ICMP, ни на TCP, ни на SSH), а бот показывал её
 * работающей. Kuma про неё в тот же момент знала: DOWN, аптайм за сутки 88.88%.
 * <p>
 * TCP-проба осталась только как {@link #isPortOpen} для owner-команды {@code /nodes} — там она
 * отвечает на другой вопрос: «панель видит ноду connected, а порт снаружи открыт?» (сигнал
 * per-IP DPI).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerStatusService {

    /** Таймаут TCP-коннекта. Не открылся за это время — сервер считаем недоступным. */
    private static final int CONNECT_TIMEOUT_MS = 2000;

    /** TTL кеша. Повторные открытия экрана в этом окне отдают прошлый снимок без новых проб. */
    private static final long CACHE_TTL_MS = 30_000;

    private final AppConfig appConfig;
    private final com.orchestra.subscriptions.client.UptimeKumaClient kuma;

    private volatile List<ServerStatus> cached;
    private volatile long cachedAtMs;

    /**
     * Статус одного сервера.
     *
     * @param online  {@code true} — работает. Оставлено плоским полем: Mini App кешируется у
     *                людей на устройствах, и старый фронт читает именно его
     * @param known   мониторинг ответил. {@code false} значит «неизвестно», и это НЕ то же
     *                самое, что «недоступен»: показать красное по неответившей Kuma так же
     *                неправильно, как и зелёное
     * @param uptime24 доля успешных проверок за сутки в процентах; {@code null} — нет данных
     * @param pingMs  задержка последней проверки; {@code null} — монитор её не измеряет
     */
    public record ServerStatus(String name, String flag, boolean online,
                               boolean known, Double uptime24, Double pingMs) {

        /** Совместимость со старыми вызовами: статус без метрик мониторинга. */
        public ServerStatus(String name, String flag, boolean online) {
            this(name, flag, online, true, null, null);
        }
    }

    /**
     * Статусы нод. Результат кешируется на {@value #CACHE_TTL_MS} мс. {@code synchronized} —
     * чтобы параллельные открытия экрана не дёргали источник дважды.
     */
    public synchronized List<ServerStatus> getStatuses() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAtMs < CACHE_TTL_MS) {
            return cached;
        }
        List<ServerStatus> fresh = fromKuma();
        if (fresh == null) {
            // Мониторинг не ответил. Отдаём прошлый снимок, если он есть: он устарел на
            // секунды, а выдуманный «недоступен» по всем нодам сразу выглядит как авария.
            if (cached != null) {
                log.warn("Uptime Kuma не ответила — отдаю прошлый снимок статуса нод");
                return cached;
            }
            fresh = unknownFromConfig();
        }
        cached = fresh;
        cachedAtMs = now;
        return cached;
    }

    /**
     * Снимок из Kuma: только группа узлов сервиса — сервисные мониторы (панель, прокси чеков,
     * бэкап) человеку на экране «какой сервер выбрать» не нужны и только пугают.
     * {@code null} — источник выключен или не ответил.
     */
    private List<ServerStatus> fromKuma() {
        if (!kuma.isEnabled()) {
            return null;
        }
        List<com.orchestra.subscriptions.client.UptimeKumaClient.Monitor> monitors = kuma.fetch();
        if (monitors == null) {
            return null;
        }
        String group = appConfig.getStatusPage() == null ? null
                : appConfig.getStatusPage().getNodeGroup();
        List<ServerStatus> out = new ArrayList<>();
        for (var m : monitors) {
            if (group != null && !group.isBlank() && !group.equals(m.group())) {
                continue;
            }
            out.add(new ServerStatus(m.name(), m.flag(),
                    Boolean.TRUE.equals(m.up()), m.up() != null,
                    m.uptime24() == null ? null : m.uptime24() * 100, m.pingMs()));
        }
        return List.copyOf(out);
    }

    /**
     * Kuma не ответила и показать нечего: перечисляем ноды из конфига со статусом
     * «неизвестно». Врать зелёным по неотвечающему мониторингу нельзя — ровно из-за этого
     * и затевалась вся правка.
     */
    private List<ServerStatus> unknownFromConfig() {
        List<ServerStatus> out = new ArrayList<>();
        for (AppConfig.ServerConfig s : orEmpty(appConfig.getServers())) {
            out.add(new ServerStatus(s.getName(), s.getFlag(), false, false, null, null));
        }
        return List.copyOf(out);
    }

    /**
     * {@code true}, если TCP-сокет к {@code host:port} открылся за {@link #CONNECT_TIMEOUT_MS} мс.
     * Переиспользуемая проба (та же семантика, что и для экрана статуса): использует
     * {@link com.orchestra.subscriptions.service.AdminService#getNodeStatuses()} для owner-команды {@code /nodes},
     * чтобы отличить «панель видит ноду connected» от «а :443 снаружи закрыт» (сигнал per-IP DPI).
     */
    public boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            log.debug("TCP-проба {}:{} не прошла: {}", host, port, e.getMessage());
            return false;
        }
    }

    private static List<AppConfig.ServerConfig> orEmpty(List<AppConfig.ServerConfig> list) {
        return list == null ? List.of() : list;
    }
}
