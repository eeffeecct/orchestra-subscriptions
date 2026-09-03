package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * «Горячий» конфиг: правки нод / подписочных строк / split-routing без пересборки docker-образа.
 * <p>
 * Волатильная часть {@code app.*} ({@link AppConfig#getServers()}) дублируется во внешнем
 * YAML-файле, примонтированном на сервере ({@link AppConfig#getDynamicConfigPath()}, напр.
 * {@code /opt/subscription-service/config/app-dynamic.yml}).
 * Файл имеет ту же структуру, что и {@code application.yml} (корень {@code app:}). Owner-команда
 * {@code /reloadconfig} перечитывает файл и <b>копирует</b> значения прямо в живой синглтон
 * {@link AppConfig} через тот же Spring {@link Binder}, что и {@code @ConfigurationProperties} —
 * поэтому все потребители ({@code ServerStatusService}, {@code AccessDeliveryController}), читающие
 * геттеры {@code AppConfig} на каждый запрос, сразу видят новые значения. Цены/тарифы
 * ({@code app.plans}) намеренно НЕ входят в горячий набор — они остаются за деплоем/ревью.
 * <p>
 * Fallback: файла нет / битый → остаёмся на запечённых в образ значениях (dev/local работает как
 * раньше). Копируются только НЕ-null поля файла — частичный файл не затирает неуказанные ключи;
 * чтобы очистить список, задай в файле пустой {@code []}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicConfigService {

    private final AppConfig appConfig;

    /**
     * На старте применяем внешний файл, если он есть (тихо, в лог). Обёрнуто в try/catch —
     * битый/отсутствующий файл не должен ронять запуск: остаёмся на baked-дефолтах.
     */
    @PostConstruct
    void init() {
        String path = appConfig.getDynamicConfigPath();
        if (path == null || path.isBlank() || !Files.isReadable(Path.of(path))) {
            log.info("Dynamic config: внешний файл не задан/не найден ({}) — использую baked-значения", path);
            return;
        }
        try {
            AppConfig loaded = loadFromFile(path);
            applyVolatileFields(loaded);
            log.info("Dynamic config: применён внешний файл {} на старте", path);
        } catch (Exception e) {
            log.error("Dynamic config: не смог применить {} на старте — остаюсь на baked-значениях", path, e);
        }
    }

    /** Превью для {@code /reloadconfig}: перечитать файл, посчитать diff vs live — БЕЗ применения. */
    public ReloadResult preview() {
        return run(false);
    }

    /** Применить для {@code /reloadconfig apply}: скопировать volatile-поля в живой AppConfig + reload routing. */
    public ReloadResult apply() {
        return run(true);
    }

    private ReloadResult run(boolean apply) {
        String path = appConfig.getDynamicConfigPath();
        if (path == null || path.isBlank()) {
            return ReloadResult.error("app.dynamic-config-path не задан (фича выключена)");
        }
        if (!Files.isReadable(Path.of(path))) {
            return ReloadResult.error("Файл не читается: " + path);
        }
        AppConfig loaded;
        try {
            loaded = loadFromFile(path);
        } catch (Exception e) {
            return ReloadResult.error("Не смог распарсить " + path + ": " + e.getMessage());
        }
        List<String> diff = diff(loaded);
        if (apply) {
            applyVolatileFields(loaded);
            log.info("Dynamic config: applied via /reloadconfig from {} — {}", path, String.join("; ", diff));
        }
        return ReloadResult.ok(path, diff, apply);
    }

    /** Биндит внешний YAML (корень {@code app:}) в свежий {@link AppConfig} тем же биндером, что и Spring. */
    private AppConfig loadFromFile(String path) throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("dynamic-config", new FileSystemResource(path));
        if (sources.isEmpty()) {
            throw new IllegalStateException("пустой YAML");
        }
        MutablePropertySources mps = new MutablePropertySources();
        for (PropertySource<?> s : sources) {
            mps.addLast(s);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(mps));
        return binder.bind("app", Bindable.of(AppConfig.class)).orElseGet(AppConfig::new);
    }

    /** Копирует НЕ-null volatile-поля из загруженного конфига в живой синглтон {@link AppConfig}. */
    private void applyVolatileFields(AppConfig loaded) {
        if (loaded.getServers() != null) {
            appConfig.setServers(loaded.getServers());
        }
    }

    /** Человекочитаемый diff: что изменится/изменилось по каждому горячему ключу. */
    private List<String> diff(AppConfig loaded) {
        List<String> out = new ArrayList<>();
        out.add(listLine("ноды (servers)", appConfig.getServers(), loaded.getServers()));
        return out;
    }

    private static String listLine(String name, List<?> cur, List<?> next) {
        if (next == null) {
            return name + ": не задан в файле (оставлю текущее: " + size(cur) + ")";
        }
        String mark = Objects.equals(cur, next) ? " (=)" : " ⟵ изменится";
        return name + ": " + size(cur) + " → " + next.size() + mark;
    }

    private static String scalarLine(String name, Object cur, Object next) {
        if (next == null) {
            return name + ": не задан в файле (оставлю текущее: " + cur + ")";
        }
        String mark = Objects.equals(cur, next) ? " (=)" : " ⟵ изменится";
        return name + ": " + cur + " → " + next + mark;
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    /**
     * Результат {@code /reloadconfig}: либо ошибка (файл не найден/битый), либо успех со списком
     * diff-строк. {@code applied=false} — это превью (ничего не менялось), {@code true} — применено.
     */
    public record ReloadResult(boolean success, String message, String path, List<String> lines, boolean applied) {
        static ReloadResult error(String message) {
            return new ReloadResult(false, message, null, List.of(), false);
        }

        static ReloadResult ok(String path, List<String> lines, boolean applied) {
            return new ReloadResult(true, null, path, lines, applied);
        }
    }
}
