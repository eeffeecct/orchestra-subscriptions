package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Долговечная запись {@code telegram_payment_charge_id} в ОТДЕЛЬНОЙ транзакции.
 * <p>
 * Вынесено в отдельный бин, потому что {@code @Transactional(REQUIRES_NEW)} работает только
 * через Spring-прокси (вызов из {@link PaymentProcessingService#handleStarsSucceeded} —
 * cross-bean). Идея: зафиксировать charge id ДО активации подписки, чтобы откат активации
 * при сбое AccessProvider не потерял id — у Stars нет webhook-передоставки, и {@code PaymentReconcileService}
 * ретраит активацию именно по сохранённому charge id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarsChargePersister {

    private final PaymentRepository paymentRepository;

    /**
     * Атомарно и идемпотентно записывает charge id (только если ещё пуст и платёж STARS).
     * Коммитится в собственной транзакции независимо от исхода вызывающей.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistChargeId(Long paymentId, String telegramChargeId) {
        if (paymentId == null || telegramChargeId == null) {
            return;
        }
        int updated = paymentRepository.setStarsChargeIdIfAbsent(paymentId, telegramChargeId);
        if (updated > 0) {
            log.info("Persisted Stars charge id for payment id={}", paymentId);
        }
    }
}
