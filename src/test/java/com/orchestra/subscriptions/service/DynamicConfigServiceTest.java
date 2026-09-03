package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест «горячего» конфига без Spring и Docker: временный внешний YAML биндится и
 * копируется в живой {@link AppConfig}, превью не мутирует, ошибки — понятные.
 */
class DynamicConfigServiceTest {

    @TempDir
    Path tempDir;

    private static final String TWO_SERVERS = """
            app:
              servers:
                - name: "Регион A"
                  flag: "🇪🇺"
                  host: "203.0.113.10"
                  port: 443
                - name: "Регион B"
                  flag: "🇪🇺"
                  host: "203.0.113.11"
                  port: 443
            """;

    private AppConfig bakedAppConfig() {
        AppConfig cfg = new AppConfig();
        AppConfig.ServerConfig s = new AppConfig.ServerConfig();
        s.setName("Baked");
        s.setFlag("🏳");
        s.setHost("203.0.113.1");
        s.setPort(443);
        cfg.setServers(new ArrayList<>(List.of(s)));
        return cfg;
    }

    private Path writeExternal(String yaml) throws Exception {
        Path f = tempDir.resolve("app-dynamic.yml");
        Files.writeString(f, yaml);
        return f;
    }

    @Test
    void apply_copiesExternalServersIntoLiveAppConfig() throws Exception {
        AppConfig appConfig = bakedAppConfig();
        appConfig.setDynamicConfigPath(writeExternal(TWO_SERVERS).toString());
        DynamicConfigService svc = new DynamicConfigService(appConfig);

        DynamicConfigService.ReloadResult r = svc.apply();

        assertThat(r.success()).isTrue();
        assertThat(r.applied()).isTrue();
        assertThat(appConfig.getServers()).hasSize(2);
        assertThat(appConfig.getServers().get(0).getName()).isEqualTo("Регион A");
    }

    @Test
    void preview_doesNotMutateLiveConfig() throws Exception {
        AppConfig appConfig = bakedAppConfig();
        appConfig.setDynamicConfigPath(writeExternal(TWO_SERVERS).toString());
        DynamicConfigService svc = new DynamicConfigService(appConfig);

        DynamicConfigService.ReloadResult r = svc.preview();

        assertThat(r.success()).isTrue();
        assertThat(r.applied()).isFalse();
        assertThat(appConfig.getServers()).hasSize(1); // не изменилось
        assertThat(r.lines()).anyMatch(l -> l.contains("ноды") && l.contains("1 → 2"));
    }

    /**
     * Ключ, отсутствующий во внешнем файле, обязан оставить запечённое значение. Иначе
     * урезанный файл молча обнулял бы список узлов — то есть выдачу доступа.
     */
    @Test
    void apply_fileWithoutServersKeepsBakedOnes() throws Exception {
        AppConfig appConfig = bakedAppConfig();
        appConfig.setDynamicConfigPath(writeExternal("""
                app:
                  node-stats-token: "irrelevant"
                """).toString());
        DynamicConfigService svc = new DynamicConfigService(appConfig);

        svc.apply();

        assertThat(appConfig.getServers()).hasSize(1);
        assertThat(appConfig.getServers().get(0).getName()).isEqualTo("Baked");
    }

    @Test
    void run_missingFile_returnsError() {
        AppConfig appConfig = bakedAppConfig();
        appConfig.setDynamicConfigPath(tempDir.resolve("nope.yml").toString());
        DynamicConfigService svc = new DynamicConfigService(appConfig);

        DynamicConfigService.ReloadResult r = svc.apply();

        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("не читается");
    }

    @Test
    void run_pathNotSet_returnsError() {
        AppConfig appConfig = bakedAppConfig(); // dynamicConfigPath = null
        DynamicConfigService svc = new DynamicConfigService(appConfig);

        DynamicConfigService.ReloadResult r = svc.preview();

        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("не задан");
    }
}
