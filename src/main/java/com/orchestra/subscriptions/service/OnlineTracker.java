package com.orchestra.subscriptions.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто сейчас пользуется сервисом — то, чего НЕ показывает провайдер доступа.
 *
 * <p>Провайдер знает только про узлы, которыми управляет сам; узлы, живущие вне его контура,
 * он не видит и показывает по ним «онлайн 0» при работающих людях. Трекер собирает картину
 * из двух источников, которые у нас есть:
 *
 * <ul>
 *   <li><b>Пер-пользовательский</b> — узел на КАЖДОЕ подключение спрашивает разрешение у
 *       {@code /node/auth/…}, то есть имя подписчика и время мы и так знаем, просто раньше
 *       не сохраняли. Точность до человека.</li>
 *   <li><b>Агрегатный</b> — узел-релей раз в минуту присылает число живых соединений по
 *       каждому порту. До человека не разложить: там нет учётных записей, поэтому только
 *       счётчики.</li>
 * </ul>
 *
 * <p>Хранение — в памяти: это оперативный снимок «кто онлайн сейчас», а не аналитика.
 * Рестарт и деплой обнуляют его, и это нормально — через минуту всё наберётся заново.
 * В базу намеренно не пишем: авторизация дёргается на каждом коннекте, и это был бы поток
 * мелких вставок ради данных, живущих десять минут.
 */
@Slf4j
@Service
public class OnlineTracker {

    /** Окно, за которое подключение считается «живым» в отчёте. */
    public static final Duration SESSION_WINDOW = Duration.ofMinutes(10);
    /** Сколько держим записи, прежде чем выкинуть (чуть больше окна — на случай долгих пауз). */
    private static final Duration RETENTION = Duration.ofHours(1);
    /** Потолок на всякий случай: трекер не должен расти без предела при флуде. */
    private static final int MAX_ENTRIES = 2000;

    /** Одна сессия: кто, к какому узлу, когда. */
    public record NodeSession(String username, String nodeIp, Instant at) {}

    /** Снимок узла-релея: сколько соединений на каждом порту и когда это было измерено. */
    public record RelaySnapshot(Map<String, Integer> connectionsByPort, int uniqueClients, Instant at) {}

    /** Ключ — юзер+нода: одному человеку с двух устройств на одной ноде хватает одной строки. */
    private final Map<String, NodeSession> nodeSessions = new ConcurrentHashMap<>();
    private volatile RelaySnapshot relay;

    /** Вызывается из {@code /node/auth} на КАЖДОМ успешном подключении. */
    public void recordNodeSession(String username, String nodeIp) {
        if (username == null || username.isBlank()) return;
        Instant now = Instant.now();
        nodeSessions.put(username + "@" + (nodeIp == null ? "?" : nodeIp),
                new NodeSession(username, nodeIp, now));
        prune(now);
    }

    /** Вызывается кроном с российского входа (см. {@code scripts/relay-stats-report.sh}). */
    public void recordRelay(Map<String, Integer> connectionsByPort, int uniqueClients) {
        relay = new RelaySnapshot(
                connectionsByPort == null ? Map.of() : new LinkedHashMap<>(connectionsByPort),
                uniqueClients, Instant.now());
    }

    /** Сессии за последние {@link #SESSION_WINDOW}, свежие сверху. */
    public List<NodeSession> recentNodeSessions() {
        Instant cutoff = Instant.now().minus(SESSION_WINDOW);
        return nodeSessions.values().stream()
                .filter(s -> s.at().isAfter(cutoff))
                .sorted(Comparator.comparing(NodeSession::at).reversed())
                .toList();
    }

    /** Последний отчёт каскадного входа. {@code null} — крон ещё ни разу не присылал. */
    public RelaySnapshot relaySnapshot() {
        return relay;
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(RETENTION);
        nodeSessions.values().removeIf(s -> s.at().isBefore(cutoff));
        if (nodeSessions.size() > MAX_ENTRIES) {
            // Аварийная ветка: выкидываем самые старые, чтобы память не росла.
            nodeSessions.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().at()))
                    .limit(nodeSessions.size() - MAX_ENTRIES)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(nodeSessions::remove);
        }
    }
}
