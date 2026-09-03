package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.util.PromoMessages;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Общий ответ «промокод не подошёл» для Mini App и web-ЛК. Оба фронта разбирают одни и те же
 * поля ({@code error/reason/message/code}), поэтому тело ответа собирается в одном месте:
 * разъехавшийся формат означал бы, что один из двух экранов молча перестанет показывать причину.
 */
final class PromoResponses {

    private PromoResponses() {}

    /**
     * Лимит на предварительную проверку промокода, общий для обоих фронтов.
     * <p>
     * Ручка отвечает {@code valid:true} вместе с суммой скидки, то есть работает оракулом:
     * без лимита действующий код находится перебором, и кампания «код для отключившихся»
     * достаётся посторонним раньше адресатов. {@code max_uses} ограничивает лишь потолок
     * ущерба, но не сам перебор.
     * <p>
     * Ключ — telegram id, а не IP: обе ручки уже за аутентификацией, и осмысленная единица
     * здесь человек, а не адрес (за одним NAT сидит целый оператор). 15 попыток в час —
     * заведомо больше, чем нужно живому человеку: код вводят один-два раза за покупку.
     */
    static final String VALIDATE_LIMIT_KEY = "promo-validate:";
    static final int VALIDATE_MAX_PER_HOUR = 15;

    /** 429 «слишком часто» — одинаковый для Mini App и ЛК. */
    static ResponseEntity<Object> rateLimited() {
        return ResponseEntity.status(429).body(Map.of(
                "error", "rate_limited",
                "message", "Слишком много попыток. Повторите через час."));
    }

    /** 400 с причиной отказа. {@code reason == null} трактуем как общий {@code invalid}. */
    static ResponseEntity<Object> invalid(String rawCode, String reason) {
        String effectiveReason = reason == null ? "invalid" : reason;
        return ResponseEntity.badRequest().body(Map.of(
                "error", "promo_invalid",
                "reason", effectiveReason,
                "message", PromoMessages.reasonMessage(effectiveReason),
                "code", rawCode == null ? "" : rawCode));
    }
}
