package com.orchestra.subscriptions.web.api;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collection;
import java.util.List;

/**
 * Извлекает «настоящий» IP клиента, учитывая что у нас Caddy reverse-proxy перед
 * Spring. Без учёта {@code X-Forwarded-For} {@link HttpServletRequest#getRemoteAddr()}
 * всегда вернёт IP Caddy (127.0.0.1 / docker-bridge), и rate-limit по IP станет
 * бесполезным — все запросы будут с одного «адреса».
 * <p>
 * Логика: каждый прокси дописывает реальный peer-IP СПРАВА, поэтому идём по списку
 * справа налево и берём первый адрес, который не входит в
 * {@code app.trusted-proxy-ips}. Всё что левее найденного клиент может подделать
 * произвольно — доверять этому для rate-limit нельзя. Нет заголовка / в списке одни
 * доверенные прокси — фолбэк на {@code remoteAddr}.
 * <p>
 * <b>Зачем список доверенных.</b> С 2026-08-11 перед main стоит второй прокси — Caddy
 * на отдельном узле-фронте, потому что адрес главного сервера может быть недоступен
 * из российских сетей (DEPLOYMENT.md §14.9). Цепочка становится
 * {@code клиент → Caddy(нода) → Caddy(main) → Spring}, и в {@code X-Forwarded-For}
 * последним элементом оказывается IP ноды. Без этого списка ВСЕ пользователи фронта
 * схлопнулись бы в один адрес и упёрлись в общий лимит 60 запросов/мин.
 * <p>
 * <b>Безопасность:</b> подделать заголовок так, чтобы спрятаться, нельзя — реальный peer
 * всегда дописывается справа от того, что прислал клиент. Даже если клиент подставит IP
 * ноды в конец своего XFF, справа от него окажется его собственный адрес. На проде Spring
 * слушает 127.0.0.1:8080, напрямую до него не достучаться.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    /** Вариант без доверенных прокси (dev / вызовы, где конфиг недоступен). */
    public static String resolve(HttpServletRequest request) {
        return resolve(request, List.of());
    }

    /**
     * @param trustedProxies IP собственных прокси перед Spring ({@code app.trusted-proxy-ips});
     *                       они пропускаются при поиске адреса клиента. Null/пусто — как раньше:
     *                       берётся самый правый элемент.
     */
    public static String resolve(HttpServletRequest request, Collection<String> trustedProxies) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (hop.isEmpty()) continue;
                if (trustedProxies != null && trustedProxies.contains(hop)) continue;
                return hop;
            }
        }
        return request.getRemoteAddr();
    }
}
