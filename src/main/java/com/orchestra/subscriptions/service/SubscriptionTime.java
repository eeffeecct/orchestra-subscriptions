package com.orchestra.subscriptions.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Арифметика срока подписки в СЕКУНДАХ — единственное место, где дни превращаются во время
 * и обратно.
 * <p>
 * <b>Почему секунды, а не дни.</b> Целые сутки уже дважды стоили нам оплаченного времени:
 * AccessProvider получал «N суток от сейчас» и обрезал дробную часть (фикс {@code f83f3e0} —
 * {@code enableUserUntil} с точным {@link java.time.Instant}), а докупка слота округляла
 * остаток вниз. Как только появятся подписки с замораживаемым остатком, каждое
 * переключение будет заново конвертировать «сколько осталось» — и на днях терялось бы до
 * суток за раз. В секундах конвертация точная.
 * <p>
 * <b>Правило округления: всегда В ПОЛЬЗУ ЮЗЕРА.</b> Остаток считаем вверх (ceil), к списанию
 * — вниз. Хуже потерять секунду нашей выручки, чем день чужой оплаты: первое незаметно,
 * второе — «подписка кончилась раньше, чем показывал бот».
 * <p>
 * Класс намеренно без Spring и без состояния — чистые функции, чтобы их можно было
 * проверить юнит-тестом без контекста и звать откуда угодно.
 */
public final class SubscriptionTime {

    /** Секунд в сутках. Именно 86400: переходов на летнее время в UTC нет (см. {@code TimeZoneCheck}). */
    public static final long SECONDS_PER_DAY = 86_400L;

    private SubscriptionTime() {}

    /** Дни тарифа → секунды. Отрицательные дни не бывают, но не падаем: клампим в 0. */
    public static long daysToSeconds(long days) {
        return Math.max(0L, days) * SECONDS_PER_DAY;
    }

    /**
     * Сколько секунд осталось от {@code now} до {@code expiresAt}, округляя ВВЕРХ.
     * Прошедший срок / {@code null} — 0 (никогда не отрицательное: отрицательный остаток
     * в леджере означал бы «юзер должен нам время»).
     */
    public static long remainingSeconds(LocalDateTime expiresAt, LocalDateTime now) {
        if (expiresAt == null || now == null || !expiresAt.isAfter(now)) {
            return 0L;
        }
        return ceilSeconds(Duration.between(now, expiresAt));
    }

    /**
     * Длительность → секунды с округлением ВВЕРХ. {@code Duration.toSeconds()} режет вниз,
     * из-за чего 29 суток 23:59:59.5 превращались бы в «29 суток 23:59:59» на каждом
     * пересчёте — а пересчётов будет по одному на переключение подписки.
     */
    public static long ceilSeconds(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return 0L;
        }
        long seconds = duration.getSeconds();
        return duration.getNano() > 0 ? seconds + 1 : seconds;
    }

    /** Секунды остатка → момент истечения от {@code now}. Обратная к {@link #remainingSeconds}. */
    public static LocalDateTime expiryFrom(LocalDateTime now, long remainingSeconds) {
        return now.plusSeconds(Math.max(0L, remainingSeconds));
    }

    /**
     * Сколько дней показать юзеру. Округление ВВЕРХ: пока остался хоть час, честно писать
     * «1 день», а не «0 дней» — иначе человек с 20 часами в запасе видит ноль и идёт
     * в поддержку. Ровно 0 секунд — 0 дней.
     */
    public static long displayDays(long remainingSeconds) {
        if (remainingSeconds <= 0L) {
            return 0L;
        }
        return (remainingSeconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY;
    }
}
