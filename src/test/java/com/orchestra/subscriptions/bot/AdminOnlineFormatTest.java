package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.service.OnlineTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Рендер {@code /online} — единственного места, где видно живых пользователей: AccessProvider
 * их не показывает, потому что часть узлов работает мимо провайдера.
 */
class AdminOnlineFormatTest {

    private static final Instant NOW = Instant.parse("2026-08-11T15:00:00Z");
    private static final Map<String, String> HY_NAMES = Map.of(
            "203.0.113.20", "Регион A",
            "203.0.113.21", "Регион B");
    // Порты каскадных инбаундов AccessProvider (Cascade-LV/EE/PL). Раньше в фикстурах стояли
    // 2087-2089 — порты контейнера relay-entry, погашенного 14.08.2026.
    private static final Map<String, String> RELAY_NAMES = Map.of(
            "2090", "Релей A", "2091", "Релей B", "2092", "Релей C");

    private static Map<String, Integer> counts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("2090", 6);
        m.put("2091", 2);
        m.put("2092", 0);
        return m;
    }

    @Test
    @DisplayName("Пусто со всех сторон — честно говорим, что никого нет и отчётов не приходило")
    void emptyState() {
        String out = AdminCommandHandler.formatOnline(List.of(), null, HY_NAMES, RELAY_NAMES, NOW);
        assertThat(out).contains("никого").contains("нет данных");
    }

    @Test
    @DisplayName("Сессии сгруппированы по узлам с человеческими именами")
    void groupsSessionsByNode() {
        List<OnlineTracker.NodeSession> sessions = List.of(
                new OnlineTracker.NodeSession("user_a", "203.0.113.20", NOW),
                new OnlineTracker.NodeSession("user_b", "203.0.113.20", NOW),
                new OnlineTracker.NodeSession("user_c", "203.0.113.21", NOW));

        String out = AdminCommandHandler.formatOnline(sessions, null, HY_NAMES, RELAY_NAMES, NOW);

        assertThat(out).contains("Регион A — 2: user_a, user_b");
        assertThat(out).contains("Регион B — 1: user_c");
        assertThat(out).contains("3 подкл.");
    }

    @Test
    @DisplayName("Каскад: счётчики по портам под именами нод + возраст отчёта")
    void rendersRelayCounts() {
        OnlineTracker.RelaySnapshot snap =
                new OnlineTracker.RelaySnapshot(counts(), 4, NOW.minusSeconds(35));

        String out = AdminCommandHandler.formatOnline(List.of(), snap, HY_NAMES, RELAY_NAMES, NOW);

        assertThat(out).contains("4 клиент(ов)").contains("35 с назад");
        assertThat(out).contains("Релей A — 6 соед.");
        assertThat(out).contains("Релей C — 0 соед.");
        assertThat(out).doesNotContain("устарели");
    }

    @Test
    @DisplayName("Отчёт старше трёх минут помечается — иначе можно принять мёртвый крон за пустоту")
    void marksStaleReport() {
        OnlineTracker.RelaySnapshot snap = new OnlineTracker.RelaySnapshot(
                counts(), 0, NOW.minus(Duration.ofMinutes(10)));

        String out = AdminCommandHandler.formatOnline(List.of(), snap, HY_NAMES, RELAY_NAMES, NOW);

        assertThat(out).contains("данные устарели");
    }

    @Test
    @DisplayName("Неизвестный порт и неизвестный IP не ломают рендер")
    void survivesUnknownIdentifiers() {
        // 2093 — порт, которого нет в relay-inbound-names: так выглядит только что заведённая
        // страна каскада, имя которой ещё не прописали в конфиг. Рендер обязан пережить.
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("2093", 3);
        String out = AdminCommandHandler.formatOnline(
                List.of(new OnlineTracker.NodeSession("user_x", "1.2.3.4", NOW)),
                new OnlineTracker.RelaySnapshot(m, 1, NOW), HY_NAMES, RELAY_NAMES, NOW);

        assertThat(out).contains("1.2.3.4 — 1: user_x");
        assertThat(out).contains("порт 2093 — 3 соед.");
    }
}
