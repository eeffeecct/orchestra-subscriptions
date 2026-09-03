package com.orchestra.subscriptions.util;

/**
 * Маскирование подписочного токена для логов. Токен из {@code /sub/<token>} — это
 * ЕДИНСТВЕННЫЙ аутентификатор подписки: кто прочитал его целиком, тот забирает чужую
 * подписку (список узлов плюс персональные учётные данные), пароля к нему нет.
 * Логи Docker по дефолту 10MB rolling и уезжают в мониторинг, поэтому туда токен
 * попадает только в маске.
 * <p>
 * Контракт (формат читают операторы в логах — не менять):
 * <ul>
 *   <li>null или короче 8 символов → "***"</li>
 *   <li>"abcdefghij" → "abcd...ghij" (первые 4 + последние 4)</li>
 * </ul>
 * Достаточно, чтобы сопоставить строки лога между собой и с записью в БД,
 * и недостаточно, чтобы восстановить токен.
 */
public final class TokenMasker {

    private TokenMasker() {}

    public static String mask(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
