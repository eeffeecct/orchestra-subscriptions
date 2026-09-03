package com.orchestra.subscriptions.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Предупреждение на старте, если JVM работает не в UTC.
 * <p>
 * <b>Почему это важно.</b> Сроки подписки хранятся как {@code TIMESTAMP WITHOUT TIME ZONE}
 * и переводятся в момент для AccessProvider через {@code ZoneId.systemDefault()}
 * ({@code AccessService.toInstant}). Пока зона одна и та же при записи и при чтении, всё
 * сходится — но если зону контейнера когда-нибудь поменяют на ходу, уже сохранённые сроки
 * молча «поедут» на величину смещения. У месячной подписки это часы чужого оплаченного
 * времени, и заметить это можно только по жалобам.
 * <p>
 * <b>Почему предупреждение, а не падение на старте.</b> Прод работает в UTC (проверено:
 * в логах контейнера метки со {@code Z}), а вот машина владельца и локальный прогон тестов —
 * нет. Жёсткая проверка сделала бы невозможным запустить тесты локально, то есть чинила бы
 * гипотетическую проблему ценой реальной. Здесь достаточно, чтобы расхождение было ВИДНО в
 * логе старта.
 */
@Slf4j
@Component
public class TimeZoneCheck {

    @PostConstruct
    void warnIfNotUtc() {
        ZoneId zone = ZoneId.systemDefault();
        // Сравнивать ИДЕНТИФИКАТОР зоны нельзя: контейнер прода отдаёт «Etc/UTC», и проверка
        // на строку "UTC" ругалась на совершенно правильную зону. normalized() сводит любую
        // зону с фиксированным нулевым смещением (UTC, Etc/UTC, GMT, Z) к ZoneOffset.UTC.
        if (ZoneOffset.UTC.equals(zone.normalized())) {
            log.info("Timezone: {} (UTC) — сроки подписок считаются в UTC, как в проде", zone.getId());
            return;
        }
        log.warn("Timezone: {} (не UTC). Прод работает в UTC; сроки подписок хранятся без зоны "
                        + "и конвертируются по зоне JVM, поэтому смена зоны на живой базе сдвинет "
                        + "уже сохранённые сроки. Для локального запуска это нормально.",
                zone.getId());
    }
}
