package com.orchestra.subscriptions.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Хелперы форматирования времени для пользовательских сообщений (бот, уведомления, Mini App
 * exports).
 *
 * <p><b>Зачем:</b> в Postgres мы храним {@code LocalDateTime} (timezone-naïve). Сервер
 * запущен в UTC (Docker дефолт), поэтому {@code LocalDateTime.now()} пишет UTC. При показе
 * пользователю надо конвертировать в МСК — иначе время отстаёт на 3 часа от ожидаемого. Этот
 * класс — единая точка конверсии.
 *
 * <p>Все методы безопасно обрабатывают {@code null}, возвращая «—» (для UI) — место вызова
 * не должно само вписывать null-checks. {@link #toMsk} приватный — выходим только через
 * форматтеры, чтобы случайно не утечь голый ZonedDateTime в логику.
 */
public final class TimeFormat {

    /** Целевая зона отображения для русскоязычной аудитории сервиса. */
    public static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    /** «26 апреля 2026 г.» — длинная локализованная дата для UI бота и уведомлений. */
    public static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'", Locale.forLanguageTag("ru"));

    /** «2026-04-26 22:35» — компактная ISO-подобная для timestamp'ов в чате. */
    public static final DateTimeFormatter RU_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.forLanguageTag("ru"));

    private TimeFormat() {}

    /**
     * Форматирует UTC-{@code LocalDateTime} как дату+время в МСК.
     * Пример: {@code 2026-04-26T19:35} (UTC) → {@code "2026-04-26 22:35"}.
     */
    public static String mskDateTime(LocalDateTime utcLocal) {
        if (utcLocal == null) return "—";
        return toMsk(utcLocal).format(RU_DATETIME);
    }

    /**
     * Форматирует UTC-{@code LocalDateTime} как дату (без времени) в МСК.
     * Учитывает смещение даты на границе суток: {@code 2026-04-26T22:30} (UTC) → 27 апреля.
     */
    public static String mskDate(LocalDateTime utcLocal) {
        if (utcLocal == null) return "—";
        return toMsk(utcLocal).format(RU_DATE);
    }

    private static ZonedDateTime toMsk(LocalDateTime utcLocal) {
        return utcLocal.atZone(ZoneOffset.UTC).withZoneSameInstant(MSK);
    }
}
