package com.orchestra.subscriptions.web.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Выставляет защитные HTTP-заголовки для /auth*, /account, /account/*, Mini App (/app/*)
 * и JSON-API (/api/*).
 * <p>
 * Покрывает:
 * <ul>
 *   <li><b>Referrer-Policy</b> — критично для magic-link. Без него браузер может
 *       отправить полный URL (с {@code ?token=...}) в Referer при клике на
 *       любую исходящую ссылку. Для {@code /auth*} ставим {@code no-referrer},
 *       для {@code /account/*} (уже без токена в URL) — {@code strict-origin}.</li>
 *   <li><b>X-Frame-Options: DENY</b> — anti-clickjacking. Чужой сайт не сможет
 *       встроить наш кабинет в iframe и обмануть юзера. ⚠️ НЕ ставится для
 *       {@code /app/*}: Telegram Web открывает Mini App именно в iframe.</li>
 *   <li><b>X-Content-Type-Options: nosniff</b> — браузер не будет угадывать
 *       MIME, а строго следовать Content-Type. Защита от XSS через mime confusion.</li>
 *   <li><b>Content-Security-Policy</b> — минимальная политика: разрешаем только
 *       {@code self}. Внешних хостов в ней больше нет — шрифты размещены у нас
 *       ({@code /app/fonts/}), и браузер клиента наружу ни к кому не ходит.
 *       Inline-стили разрешены ({@code unsafe-inline} для style): часть страниц
 *       собирается со {@code style}-атрибутами.
 *       У Mini App политика своя, см. {@link #applyMiniAppCsp}.</li>
 *   <li><b>Strict-Transport-Security</b> — даже если Caddy уже шлёт, дублировать
 *       не вредно. 6 месяцев + preload — стандарт.</li>
 *   <li><b>Cache-Control: no-store</b> — для {@code /auth*} и {@code /api/account/*}:
 *       и токен входа, и JSON с ПДн не должны оседать ни в дисковом кэше, ни в
 *       bfcache (иначе «назад» после выхода перерисовывает прошлый экран ЛК).</li>
 * </ul>
 * <p>
 * Order: ставим первым в цепочке ({@link Ordered#HIGHEST_PRECEDENCE}), чтобы
 * заголовки применились даже на error-ответах и редиректах.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.startsWith("/account/admin")) {
            // Панель владельца. Отдельной веткой ДО общего /account/: в URL входа лежит
            // одноразовый токен (no-referrer, иначе он уедет в Referer при первом же переходе
            // наружу), а на странице — персональные данные всех клиентов сразу, поэтому
            // no-store: ни диска, ни bfcache после выхода.
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Cache-Control", "no-store");
            applyCommonHardening(response);
            applyAccountCsp(response);
        } else if (path.startsWith("/auth")) {
            // /auth?token=... — токен в URL. Любой Referer-leak = утечка токена.
            response.setHeader("Referrer-Policy", "no-referrer");
            // Ответ на magic-link выдаёт сессионную куку и редиректит в ЛК: ни в кэше,
            // ни в bfcache ему делать нечего.
            response.setHeader("Cache-Control", "no-store");
            applyCommonHardening(response);
        } else if (path.equals("/account") || path.startsWith("/account/")) {
            // ЛК — URL без токенов, но всё равно ПДн (email, ссылка подписки).
            // strict-origin = Referer передаётся только при same-origin переходах.
            response.setHeader("Referrer-Policy", "strict-origin");
            applyCommonHardening(response);
            applyAccountCsp(response);
        } else if (path.startsWith("/api/account/")) {
            // API-ответы ЛК — JSON, в браузере напрямую не открываются, но
            // защитные заголовки не помешают.
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Content-Type-Options", "nosniff");
            // В теле — email, ссылка подписки, история платежей. no-store запрещает
            // и дисковый кэш, и восстановление страницы из bfcache после выхода.
            response.setHeader("Cache-Control", "no-store");
        } else if (path.equals("/app") || path.startsWith("/app/")) {
            // Mini App. ⚠️ Telegram Web встраивает его в iframe — поэтому здесь НЕТ
            // ни X-Frame-Options, ни frame-ancestors в CSP: с ними приложение не
            // откроется. Остальная жёсткость (nosniff, CSP, HSTS) — как у ЛК.
            // Токенов в URL нет (initData приезжает во фрагменте #tgWebAppData, а
            // фрагмент в Referer не попадает), поэтому strict-origin достаточно.
            response.setHeader("Referrer-Policy", "strict-origin");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Strict-Transport-Security", "max-age=15552000; includeSubDomains");
            applyMiniAppCsp(response);
        } else if (path.startsWith("/api/")) {
            // Остальной JSON-API (Mini App: /api/me, /api/devices, /api/subscriptions/*).
            // CSP тут не нужна — это не документ, ресурсы из JSON не грузятся;
            // nosniff нужен, чтобы браузер не пытался прочитать ответ как HTML.
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Content-Type-Options", "nosniff");
        }

        chain.doFilter(request, response);
    }

    private static void applyCommonHardening(HttpServletResponse response) {
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // HSTS: 180 дней, applies to всему домену + preload-eligible.
        // Caddy на 8443 может уже слать — лишний раз не вредит.
        response.setHeader("Strict-Transport-Security", "max-age=15552000; includeSubDomains");
    }

    /**
     * CSP для статики ЛК. Разрешаем:
     *  - default-src 'self'        — только наш origin
     *  - style-src 'self' 'unsafe-inline'  — свои стили + inline-атрибуты
     *  - font-src   'self'                — шрифты размещены у нас
     *  - img-src    'self' data:   — для inline svg/png
     *  - script-src 'self'         — только наши .js, никаких inline JS
     *  - connect-src 'self'        — fetch только к нашему API
     *  - frame-ancestors 'none'    — дубль X-Frame-Options
     *  - base-uri 'self'           — защита от base-tag injection
     *  - form-action 'self'        — POST/GET форм только к нам
     */
    private static void applyAccountCsp(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", String.join("; ",
                "default-src 'self'",
                "style-src 'self' 'unsafe-inline'",
                "font-src 'self'",
                "img-src 'self' data:",
                "script-src 'self'",
                "connect-src 'self'",
                "frame-ancestors 'none'",
                "base-uri 'self'",
                "form-action 'self'"
        ));
    }

    /**
     * CSP для Mini App ({@code /app/}). Списана с того, что реально грузит
     * {@code static/app/index.html} + {@code script.js}:
     * <ul>
     *   <li>{@code default-src 'self'} — всё остальное только со своего origin-а;</li>
     *   <li>{@code script-src 'self' https://telegram.org} — единственный внешний скрипт
     *       это {@code telegram-web-app.js} (тег в {@code <head>}); inline-скриптов в
     *       странице нет, поэтому {@code unsafe-inline} не нужен;</li>
     *   <li>{@code style-src 'self' 'unsafe-inline'} — свои стили и inline-атрибуты
     *       {@code style};</li>
     *   <li>{@code font-src 'self'} — шрифты размещены у нас ({@code /app/fonts/}), внешних
     *       хостов в политике нет: браузер клиента больше никому наружу не представляется;</li>
     *   <li>{@code img-src 'self' data: https:} — иконки сервисов лежат локально
     *       ({@code /app/icons/*.svg}), но аватарка юзера приходит в {@code photo_url}
     *       из initData, и хост Telegram-CDN там не зафиксирован (t.me либо
     *       {@code cdn*}-домены, Telegram их меняет). Прибивать конкретный хост нельзя —
     *       аватарка молча пропадёт; картинка при этом — пассивный сток, скрипт из неё
     *       не выполнится;</li>
     *   <li>{@code connect-src 'self'} — fetch только к своему API ({@code API_BASE = ''});</li>
     *   <li>{@code base-uri}/{@code form-action} {@code 'self'} — как в ЛК.</li>
     * </ul>
     * ⛔ {@code frame-ancestors} здесь сознательно НЕТ: Telegram Web открывает Mini App
     * в iframe, любая директива framing-а его убьёт.
     * <p>
     * Inline-обработчиков в приложении нет — именно из-за {@code script-src} без
     * {@code 'unsafe-inline'}: прятать битую иконку сервиса помогает слушатель
     * {@code hideBrokenIcons()} в script.js, а не атрибут {@code onerror}.
     */
    private static void applyMiniAppCsp(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", String.join("; ",
                "default-src 'self'",
                "script-src 'self' https://telegram.org",
                "style-src 'self' 'unsafe-inline'",
                "font-src 'self'",
                "img-src 'self' data: https:",
                "connect-src 'self'",
                "base-uri 'self'",
                "form-action 'self'"
        ));
    }
}
