package com.orchestra.subscriptions.web.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Вычисление IP клиента из X-Forwarded-For. Ключевой кейс — цепочка из двух наших прокси
 * (Caddy на ноде-фронте → Caddy на main): без списка доверенных все пользователи фронта
 * выглядели бы одним адресом и упирались в общий rate-limit.
 */
class ClientIpResolverTest {

    private static final String FRONT_NODE = "203.0.113.23";

    private static HttpServletRequest request(String xff, String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("Прямой клиент через один Caddy — самый правый элемент")
    void directClient() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7", "127.0.0.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Через ноду-фронт: IP ноды пропускается, остаётся реальный клиент")
    void throughTrustedFront() {
        assertThat(ClientIpResolver.resolve(
                request("203.0.113.7, " + FRONT_NODE, "127.0.0.1"), List.of(FRONT_NODE)))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Подделка XFF не помогает: реальный peer дописывается справа")
    void spoofedLeftPartIgnored() {
        // Клиент прислал «1.2.3.4», фронт дописал его настоящий адрес, main — адрес фронта.
        assertThat(ClientIpResolver.resolve(
                request("1.2.3.4, 203.0.113.7, " + FRONT_NODE, "127.0.0.1"), List.of(FRONT_NODE)))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Клиент выдаёт себя за фронт, но идёт напрямую — его же адрес и берём")
    void clientPretendingToBeFront() {
        assertThat(ClientIpResolver.resolve(
                request(FRONT_NODE + ", 203.0.113.7", "127.0.0.1"), List.of(FRONT_NODE)))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Без списка доверенных поведение прежнее — самый правый")
    void withoutTrustedListKeepsOldBehaviour() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7, " + FRONT_NODE, "127.0.0.1")))
                .isEqualTo(FRONT_NODE);
    }

    @Test
    @DisplayName("Нет заголовка / одни доверенные прокси — фолбэк на remoteAddr")
    void fallbackToRemoteAddr() {
        assertThat(ClientIpResolver.resolve(request(null, "127.0.0.1"), List.of(FRONT_NODE)))
                .isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve(request(FRONT_NODE, "127.0.0.1"), List.of(FRONT_NODE)))
                .isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Пробелы и пустые элементы в цепочке не ломают разбор")
    void handlesWhitespaceAndEmptyHops() {
        assertThat(ClientIpResolver.resolve(
                request("203.0.113.7 ,  , " + FRONT_NODE + " ", "127.0.0.1"), List.of(FRONT_NODE)))
                .isEqualTo("203.0.113.7");
    }
}
