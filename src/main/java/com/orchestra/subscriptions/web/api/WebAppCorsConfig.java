package com.orchestra.subscriptions.web.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC-конфиг для Mini App: CORS + чистый URL {@code /app/} без {@code index.html}.
 * <p>
 * <b>CORS:</b> браузер запрещает JS со страницы на origin A делать fetch на origin B,
 * если B явно не разрешил A в заголовке {@code Access-Control-Allow-Origin}. В prod-е
 * Mini App и API живут на одном origin-е — CORS фактически no-op. Эти разрешения
 * остаются для dev-стенда (python http.server на :8000) и будущих внешних хостингов.
 * <p>
 * <b>View controller:</b> Spring Boot auto-serves welcome-page только для корня ({@code /}).
 * Для подкаталога {@code /app/} нужно руками сказать "отдай index.html". Иначе
 * {@code GET /app/} вернёт 404 (директория не файл, welcome-маппинг к ней не применяется).
 * <p>
 * CORS — защита ДЛЯ БРАУЗЕРА, не для сервера. Настоящая защита API — в HMAC-валидации
 * ({@link TelegramInitDataValidator}).
 */
@Configuration
public class WebAppCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        // === Домены сервиса ===
                        // ⚠️ Перечисляем ЯВНО, без wildcard вида `https://*.example.com`:
                        // маска выдаёт shadow-доступ любому будущему поддомену, включая тот,
                        // который заведут не для этого API.
                        "https://example.com",
                        "https://example.com:8443",
                        // ⚠️ Узел-фронт нужен в списке, даже если Mini App ходит на свой же
                        // origin: фронт проксирует на приложение с подменой Host, поэтому Spring
                        // видит Origin фронта при Host приложения и считает запрос cross-origin.
                        // Без этой строки POST'ы Mini App (оплата, устройства, промокод)
                        // отваливаются на preflight.
                        "https://front.example.com",
                        "https://front.example.com:8443",
                        // === Dev + внешние ===
                        "http://localhost:8000",            // dev: python http.server
                        "http://localhost:8080",            // dev: Spring Boot напрямую
                        "https://web.telegram.org"          // Telegram Web
                )
                // DELETE нужен для отзыва email (152-ФЗ: DELETE /api/me/email).
                // Без него браузер на cross-origin (например web.telegram.org → наш домен)
                // получает на preflight 403 и фронт показывает «не получилось 403».
                // PUT/PATCH добавлены про запас — будут нужны как только появятся idempotent updates.
                .allowedMethods("GET", "POST", "DELETE", "PUT", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // /app (без слеша) → редирект на /app/ (чтобы относительные ссылки на
        // style.css / script.js резолвились как /app/style.css, а не /style.css).
        registry.addViewController("/app").setViewName("redirect:/app/");

        // /app/ → раздать index.html, не уходя со слешевого URL (forward, не redirect).
        registry.addViewController("/app/").setViewName("forward:/app/index.html");

        // Web-личный-кабинет — отдельный мини-фронт под /account/. Та же логика,
        // что и для /app/: clean URL без index.html в строке. /account без слеша
        // редиректит, чтобы относительные пути в HTML резолвились правильно.
        registry.addViewController("/account").setViewName("redirect:/account/");
        registry.addViewController("/account/").setViewName("forward:/account/index.html");
        registry.addViewController("/account/expired").setViewName("forward:/account/expired.html");
        registry.addViewController("/account/login").setViewName("forward:/account/login.html");

        // Админ-панель владельца. Живёт под /account/ не из родства с кабинетом, а потому что
        // узел-фронт проксирует на приложение закрытый список префиксов — собственный /admin/
        // отдавал бы там 404. Доступ ограничивает не путь, а cookie владельца
        // (см. AdminPanelController).
        registry.addViewController("/account/admin").setViewName("redirect:/account/admin/");
        registry.addViewController("/account/admin/").setViewName("forward:/account/admin/index.html");
    }
}
