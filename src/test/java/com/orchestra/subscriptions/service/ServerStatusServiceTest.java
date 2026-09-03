package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.UptimeKumaClient;
import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Статус нод берётся из Uptime Kuma и только из неё.
 * <p>
 * Проверяется главное свойство экрана: он не выдумывает состояние. До 14.08.2026 у
 * части узлов статус подменялся флагом {@code always-online} — узел лежал полчаса,
 * а бот показывал «Активен».
 */
class ServerStatusServiceTest {

    private AppConfig appConfig;
    private UptimeKumaClient kuma;
    private ServerStatusService service;

    @BeforeEach
    void setUp() {
        appConfig = new AppConfig();
        AppConfig.StatusPageConfig page = new AppConfig.StatusPageConfig();
        page.setNodeGroup("Узлы сервиса");
        appConfig.setStatusPage(page);
        kuma = mock(UptimeKumaClient.class);
        when(kuma.isEnabled()).thenReturn(true);
        service = new ServerStatusService(appConfig, kuma);
    }

    /**
     * ⛔ Та самая нода. Kuma видит её мёртвой — экран обязан показать то же, независимо от
     * того, что о ней думает конфиг и умеет ли TCP-проба проверять UDP-протокол.
     */
    @Test
    @DisplayName("Мёртвая hy2-нода показывается недоступной, а не «всегда активна»")
    void deadNodeIsShownDown() {
        when(kuma.fetch()).thenReturn(List.of(
                monitor("Узлы сервиса", "🇪🇺", "Регион B — :443", false, 0.8888, null),
                monitor("Узлы сервиса", "🌍", "Регион E — :443", true, 1.0, 31.0)));

        List<ServerStatusService.ServerStatus> out = service.getStatuses();

        assertThat(out).hasSize(2);
        assertThat(out.get(0).name()).isEqualTo("Регион B — :443");
        assertThat(out.get(0).online()).isFalse();
        assertThat(out.get(0).known()).isTrue();
        assertThat(out.get(0).uptime24()).isCloseTo(88.88, org.assertj.core.data.Offset.offset(0.01));
        assertThat(out.get(1).online()).isTrue();
        assertThat(out.get(1).pingMs()).isEqualTo(31.0);
    }

    /** Сервисные мониторы (панель, прокси, бэкап) человеку на экране выбора ноды не нужны. */
    @Test
    @DisplayName("Показывается только группа узлов сервиса")
    void onlyNodeGroupIsShown() {
        when(kuma.fetch()).thenReturn(List.of(
                monitor("Узлы сервиса", "🌍", "Регион E — :443", true, 1.0, 31.0),
                monitor("Сервис", "🎛", "Панель AccessProvider — :8443", true, 1.0, 19.0)));

        assertThat(service.getStatuses()).extracting(ServerStatusService.ServerStatus::name)
                .containsExactly("Регион E — :443");
    }

    /**
     * Мониторинг не ответил, показать нечего: «нет данных». Зелёное здесь было бы обещанием
     * за неответившую систему, красное — выдуманной аварией на всех нодах сразу.
     */
    @Test
    @DisplayName("Kuma не ответила и снимка нет — статус «неизвестно», а не «работает»")
    void unknownWhenMonitoringIsSilent() {
        appConfig.setServers(List.of(server("Регион E", "🌍")));
        when(kuma.fetch()).thenReturn(null);

        List<ServerStatusService.ServerStatus> out = service.getStatuses();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).known()).isFalse();
        assertThat(out.get(0).online())
                .as("«неизвестно» не должно притворяться «работает»")
                .isFalse();
    }

    /**
     * Kuma отвалилась на минуту — отдаём прошлый снимок. Он устарел на секунды, а вот
     * мгновенное «нет данных» по всем нодам выглядит как авария сервиса.
     */
    @Test
    @DisplayName("Разовый сбой мониторинга не стирает уже известный статус")
    void keepsLastSnapshotWhenMonitoringBlinks() {
        when(kuma.fetch()).thenReturn(List.of(
                monitor("Узлы сервиса", "🌍", "Регион E — :443", true, 1.0, 31.0)));
        service.getStatuses();
        // Сбрасываем кеш по времени, чтобы следующий вызов пошёл в источник.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cachedAtMs", 0L);
        when(kuma.fetch()).thenReturn(null);

        List<ServerStatusService.ServerStatus> out = service.getStatuses();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).online()).isTrue();
        assertThat(out.get(0).known()).isTrue();
    }

    /** Флаг из имени монитора отделяется — на экране он отдельной колонкой. */
    @Test
    @DisplayName("Флаг отделяется от названия монитора")
    void flagIsSplitFromName() {
        assertThat(UptimeKumaClient.splitFlag("🇪🇺 Регион B — :443"))
                .containsExactly("🇪🇺", "Регион B — :443");
        assertThat(UptimeKumaClient.splitFlag("Main — HTTPS :443"))
                .containsExactly("", "Main — HTTPS :443");
    }

    /**
     * TCP-проба осталась в живом коде: {@code /nodes} у владельца отвечает ею на другой
     * вопрос — «панель видит ноду connected, а порт снаружи открыт?» (сигнал per-IP DPI).
     * Со статусом нод для людей она больше не связана.
     */
    @Test
    @DisplayName("isPortOpen: открытый порт — да, закрытый — нет")
    void portProbeStillWorksForOwnerCommands() throws java.io.IOException {
        try (java.net.ServerSocket open = new java.net.ServerSocket(0)) {
            assertThat(service.isPortOpen("127.0.0.1", open.getLocalPort())).isTrue();
        }
        int closed;
        try (java.net.ServerSocket tmp = new java.net.ServerSocket(0)) {
            closed = tmp.getLocalPort();
        }
        assertThat(service.isPortOpen("127.0.0.1", closed)).isFalse();
    }

    /** Кеш: повторное открытие экрана в пределах TTL не дёргает мониторинг заново. */
    @Test
    @DisplayName("Второй вызов в пределах TTL отдаёт тот же снимок")
    void secondCallWithinTtlIsCached() {
        when(kuma.fetch()).thenReturn(List.of(
                monitor("Узлы сервиса", "🌍", "Регион E — :443", true, 1.0, 31.0)));

        List<ServerStatusService.ServerStatus> first = service.getStatuses();

        assertThat(service.getStatuses()).isSameAs(first);
        org.mockito.Mockito.verify(kuma, org.mockito.Mockito.times(1)).fetch();
    }

    /** Ни мониторинга, ни списка нод — пустой список, а не NPE. */
    @Test
    @DisplayName("Пустой конфиг и молчащий мониторинг дают пустой список")
    void emptyConfigWithSilentMonitoringReturnsEmptyList() {
        when(kuma.fetch()).thenReturn(null);

        assertThat(service.getStatuses()).isEmpty();
        assertThat(new ServerStatusService(new AppConfig(), kuma).getStatuses()).isEmpty();
    }

    private static UptimeKumaClient.Monitor monitor(String group, String flag, String name,
                                                    Boolean up, Double uptime, Double ping) {
        return new UptimeKumaClient.Monitor(group, flag, name, up, uptime, ping);
    }

    private static AppConfig.ServerConfig server(String name, String flag) {
        AppConfig.ServerConfig s = new AppConfig.ServerConfig();
        s.setName(name);
        s.setFlag(flag);
        return s;
    }
}
