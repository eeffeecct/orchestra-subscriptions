package com.orchestra.subscriptions.util;

/**
 * Маскирование email для логов. 152-ФЗ + best-practice: не оставлять чистые
 * PII в долгоживущих логах. Логи Docker по дефолту 10MB rolling — попадание
 * туда email'ов делает их одной из крупнейших куч ПДн на сервере, что
 * нарушает «минимально необходимый объём» принципа из ст.5 152-ФЗ.
 * <p>
 * Контракт:
 * <ul>
 *   <li>null/blank → "null"</li>
 *   <li>"a@b.com" → "a***@b.com" (первая буква + домен)</li>
 *   <li>"alex@example.com" → "a***@example.com"</li>
 *   <li>"@example.com" (без локальной части) → "***@example.com"</li>
 *   <li>"weird" (без @) → "***" (полностью скрываем)</li>
 * </ul>
 * Достаточно для дебага (видим домен, узнаём «о ком речь»), но недостаточно
 * для восстановления адреса.
 */
public final class EmailMasker {

    private EmailMasker() {}

    public static String mask(String email) {
        if (email == null || email.isBlank()) return "null";
        int at = email.indexOf('@');
        if (at < 0) return "***";
        if (at == 0) return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at);
    }
}
