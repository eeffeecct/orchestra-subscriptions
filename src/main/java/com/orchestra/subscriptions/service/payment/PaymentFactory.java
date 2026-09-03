package com.orchestra.subscriptions.service.payment;

import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ЕДИНСТВЕННОЕ место, где рождается строка платежа. Одиннадцатое место сломало бы главный
 * инвариант этапа — «у каждого платежа с рождения написано, в какую подписку он идёт», —
 * поэтому запрет проверяется тестом-сторожем ({@code PaymentFactoryGuardTest}), а не
 * договорённостью.
 * <p>
 * Строка сохраняется ДО обращения к провайдеру. Порядок не косметический: упавший вызов
 * провайдера обязан оставлять след в нашей БД, иначе оплаченный счёт, о котором мы ничего не
 * знаем, невозможно ни догнать реконсилером, ни найти при разборе.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFactory {

    private final PaymentRepository paymentRepository;
    private final PaymentTargetResolver targetResolver;

    /**
     * Создать PENDING-платёж, определив цель по пожеланию канала.
     * <p>
     * Цель называют те каналы, у которых есть экран для вопроса: бот (экран «Мои подписки»)
     * и Mini App (строка «Зачислится в:», поле {@code target} в теле запроса). Каналы без
     * выбора — автосписание, web-ЛК, докупка слота — идут через {@link #createIntoCurrent}
     * и завести новую подписку не могут структурно.
     *
     * @throws CheckoutRejectedException если канал попросил отдельную подписку, а её нельзя
     *                                   завести (флаг, лимит, дубль ступени)
     */
    public Payment create(PaymentDraft draft, TargetSpec spec) {
        return create(draft, targetResolver.resolve(draft.telegramId(), draft.deviceTierId(), spec));
    }

    /** Создать PENDING-платёж с уже принятым решением о цели. */
    public Payment create(PaymentDraft draft, PaymentTarget target) {
        Payment payment = paymentRepository.save(Payment.builder()
                .telegramId(draft.telegramId())
                .telegramUsername(draft.telegramUsername())
                .planId(draft.planId())
                .deviceTierId(draft.deviceTierId())
                .promoCodeUsed(draft.promoCode())
                .amountKopeks(draft.amountKopeks())
                .provider(draft.provider())
                .autorenew(draft.autorenew())
                .status(Payment.Status.PENDING)
                .subscriptionId(target.subscriptionId())
                .targetKind(target.kind().name())
                .build());
        log.debug("Создан PENDING-платёж id={} telegramId={} цель={} подписка={}",
                payment.getId(), draft.telegramId(), target.kind(), target.subscriptionId());
        return payment;
    }

    /**
     * Создать PENDING-платёж в работающую сейчас подписку — для каналов без выбора цели
     * (автосписание, web-ЛК). Новую подписку этот путь завести не может в принципе:
     * см. {@link PaymentTargetResolver#resolveCurrent(long)}.
     */
    public Payment createIntoCurrent(PaymentDraft draft) {
        return create(draft, targetResolver.resolveCurrent(draft.telegramId()));
    }

    /**
     * Докупка слота «+1 устройство» — ВСЕГДА в текущую подписку, пожелание канала не
     * принимается. Цена слота считается pro-rata из остатка текущей подписки, поэтому
     * зачисление в припаркованную означало бы счёт по остатку не той подписки.
     */
    public Payment createAddon(PaymentDraft draft) {
        return createIntoCurrent(draft);
    }
}
