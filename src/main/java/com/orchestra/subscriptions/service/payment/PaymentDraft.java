package com.orchestra.subscriptions.service.payment;

import com.orchestra.subscriptions.model.Payment;
import lombok.Builder;

/**
 * Что человек покупает — без ответа на вопрос «в какую подписку». Вторую половину
 * (цель) добавляет {@link PaymentTargetResolver}, а собирает их вместе {@link PaymentFactory}.
 * <p>
 * Разделение нужно, чтобы цель нельзя было ЗАБЫТЬ: у платежа нет способа появиться на свет
 * без явно переданной цели — это не дисциплина вызывающих, а сигнатура. Проявляется это в
 * {@code target_kind}: он заполнен у каждой новой строки.
 * <p>
 * ⚠️ {@code subscription_id} при этом законно бывает пустым — у {@code NEW} (первая покупка
 * человека) и {@code SEPARATE} (отдельная подписка): подписки, в которую зачислять, ещё нет,
 * её создаст активация. Поэтому «платёж без цели» ищется по {@code target_kind IS NULL},
 * а не по пустому {@code subscription_id} — иначе критерий никогда не станет нулевым.
 * <p>
 * Идентификатора платежа у провайдера здесь нет намеренно: строка сохраняется ДО обращения
 * к провайдеру (иначе упавший вызов оставлял бы оплату без следа в нашей БД), поэтому в
 * момент создания его ещё не существует — вызывающий дописывает его вторым сохранением.
 */
@Builder
public record PaymentDraft(Long telegramId,
                           String telegramUsername,
                           String planId,
                           String deviceTierId,
                           long amountKopeks,
                           Payment.Provider provider,
                           String promoCode,
                           boolean autorenew) {
}
