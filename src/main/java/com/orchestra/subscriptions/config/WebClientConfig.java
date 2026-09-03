package com.orchestra.subscriptions.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Базовый reactor-netty {@link HttpClient} с таймаутами для всех исходящих HTTP-клиентов
 * (AccessProvider, YooKassa, CryptoBot, Uptime Kuma).
 * <p>
 * Без явных таймаутов у {@code WebClient} их НЕТ: зависший провайдер блокировал бы
 * поток-вызыватель бесконечно (Tomcat-воркер вебхука, поток @Scheduled, поток апдейтов бота)
 * и держал бы DB-коннект Hikari, если вызов идёт внутри транзакции — вплоть до истощения пула.
 */
@Configuration
public class WebClientConfig {

    /** Таймаут установки TCP-соединения. */
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    /** Потолок ожидания ПОЛНОГО ответа (запрос → последний байт тела). */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(8);
    /** Idle read/write таймауты на сокете — страховка от «висящего» соединения без данных. */
    private static final int READ_TIMEOUT_SEC = 10;
    private static final int WRITE_TIMEOUT_SEC = 10;

    @Bean
    public HttpClient timeoutHttpClient() {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SEC))
                        .addHandlerLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SEC)));
    }
}
