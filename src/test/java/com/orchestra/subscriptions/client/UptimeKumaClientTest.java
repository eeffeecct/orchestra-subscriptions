package com.orchestra.subscriptions.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор ответа Uptime Kuma — на НАСТОЯЩЕМ ответе прод-страницы, снятом 14.08.2026.
 * <p>
 * Форма чужого API важнее любых предположений о ней: Kuma отдаёт мониторы и пульс двумя
 * разными запросами, ключи пульса — строковые id, аптайм лежит под {@code "<id>_24"}, а у
 * push- и ping-мониторов {@code ping} отсутствует. Всё это ломается молча — экран просто
 * покажет «нет данных» по всем нодам, и понять почему будет неоткуда.
 * <p>
 * Фикстуры обрезаны до трёх ударов на монитор, чтобы файл читался глазами.
 */
class UptimeKumaClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Реальный ответ прода разбирается: мониторы, группы, аптайм, задержка")
    void parsesRealProductionResponse() throws Exception {
        List<UptimeKumaClient.Monitor> monitors = UptimeKumaClient.merge(
                read("/kuma/status-page.json", UptimeKumaClient.StatusPage.class),
                read("/kuma/heartbeats.json", UptimeKumaClient.Heartbeats.class));

        assertThat(monitors).isNotEmpty();
        assertThat(monitors).extracting(UptimeKumaClient.Monitor::group)
                .contains("Узлы сервиса", "Сервис");

        UptimeKumaClient.Monitor regionB = monitors.stream()
                .filter(m -> m.name().startsWith("Регион B"))
                .findFirst().orElseThrow();
        assertThat(regionB.flag()).isEqualTo("🇪🇺");
        assertThat(regionB.up())
                .as("на момент снятия фикстуры нода лежала — ровно с этого и началась правка")
                .isFalse();
        assertThat(regionB.uptime24()).isNotNull().isLessThan(1.0);

        UptimeKumaClient.Monitor regionE = monitors.stream()
                .filter(m -> m.name().startsWith("Регион E"))
                .findFirst().orElseThrow();
        assertThat(regionE.up()).isTrue();
        assertThat(regionE.pingMs()).isNotNull().isPositive();
        assertThat(regionE.uptime24()).isNotNull();
    }

    /** У push-монитора («Бэкап») задержки нет вовсе — это не повод считать его сломанным. */
    @Test
    @DisplayName("Монитор без задержки разбирается без падения")
    void monitorWithoutPingIsFine() throws Exception {
        List<UptimeKumaClient.Monitor> monitors = UptimeKumaClient.merge(
                read("/kuma/status-page.json", UptimeKumaClient.StatusPage.class),
                read("/kuma/heartbeats.json", UptimeKumaClient.Heartbeats.class));

        assertThat(monitors).anyMatch(m -> m.up() != null && m.pingMs() == null);
    }

    private static <T> T read(String resource, Class<T> type) throws Exception {
        try (InputStream in = UptimeKumaClientTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("фикстура %s", resource).isNotNull();
            return MAPPER.readValue(in, type);
        }
    }
}
