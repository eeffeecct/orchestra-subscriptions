package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Догоняет потерянные YooKassa webhook-уведомления.
 * <p>
 * Проблема: webhook от YK не аутентифицирован и не ретраится сервером надёжно. Если в момент
 * доставки наш контейнер перезапускался (CI/CD деплой), webhook теряется, оплата на стороне YK
 * — succeeded, а в нашей БД Payment висит в PENDING. Юзер не получает подписку, пока админ
 * не дёрнет вручную.
 * <p>
 * Решение: раз в 10 минут берём свежие PENDING-платежи (с задержкой 5 минут — чтобы не гоняться
 * за теми, у которых webhook ещё в пути), опрашиваем YK API и приводим состояние в консистентное.
 * Все вызовы идут через {@link PaymentProcessingService}, который уже идемпотентен — поэтому
 * параллельный приход webhook не двойнит активацию.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconcileService {

    /** Игнорируем платежи моложе этого порога — webhook ещё может прийти штатно. */
    private static final long MIN_AGE_MINUTES = 5;
    /** Сколько берём за один тик. Защита от взрывного роста stuck-очереди. */
    private static final int BATCH_SIZE = 50;
    /**
     * После этого срока считаем PENDING-платёж безнадёжным и помечаем FAILED:
     * чтобы /stats и admin-card не показывали бесконечно растущий PENDING-хвост.
     * 24ч — достаточно: если за сутки YK API не вернул succeeded/canceled,
     * webhook уже точно не прилетит, либо платёж завис в YK по их же причинам
     * (3DS abandoned, fraud-hold), которые YK сам разрешит → новый платёж создастся отдельно.
     */
    private static final long GIVE_UP_HOURS = 24;

    private final PaymentRepository paymentRepository;
    private final YooKassaClient yooKassaClient;
    private final CryptoBotClient cryptoBotClient;
    private final PaymentProcessingService paymentProcessingService;

    /**
     * Feature-флаг для фонового scheduling. В prod = true. В тестах ставим false,
     * чтобы @Scheduled не фирился одновременно с ручными {@link #runOnce()} из @Test —
     * иначе flaky "wanted 1 time, was 2 times" из-за race condition (см. AutoRenewService).
     */
    @Value("${app.scheduler.reconcile-enabled:true}")
    private boolean schedulerEnabled;

    /** Раз в 10 минут проверяет stuck PENDING. */
    @Scheduled(fixedDelay = 10L * 60L * 1000L)
    public void scheduledTick() {
        if (!schedulerEnabled) {
            return;
        }
        runOnce();
    }

    /** Публичный метод для ручного вызова из тестов / админки. */
    public void runOnce() {
        LocalDateTime now = LocalDateTime.now();
        reconcileStaleYookassa(now);
        reconcileStaleCrypto(now);
        reconcileStaleStars(now);
    }

    private void reconcileStaleYookassa(LocalDateTime now) {
        LocalDateTime threshold = now.minusMinutes(MIN_AGE_MINUTES);
        List<Payment> stale = paymentRepository.findStaleYookassaPending(
                threshold, PageRequest.of(0, BATCH_SIZE));
        if (stale.isEmpty()) {
            log.debug("Reconcile: no stale YooKassa payments older than {}", threshold);
            return;
        }
        log.info("Reconcile: found {} stale YooKassa payments to check", stale.size());
        for (Payment p : stale) {
            try {
                reconcileOne(p, now);
            } catch (Exception e) {
                // Защитный catch: одна проблема не должна остановить весь прогон.
                log.error("Reconcile unexpected error for payment id={} ykId={}",
                        p.getId(), p.getYookassaPaymentId(), e);
            }
        }
    }

    /**
     * Догоняет потерянные CryptoBot-вебхуки: invoice_paid не довёл активацию (рестарт контейнера
     * или сбой AccessProvider → откат @Transactional). Опрашиваем Crypto Pay API и, если оплачено,
     * прогоняем идемпотентный {@link PaymentProcessingService#handleCryptoSucceeded}.
     */
    private void reconcileStaleCrypto(LocalDateTime now) {
        LocalDateTime threshold = now.minusMinutes(MIN_AGE_MINUTES);
        List<Payment> stale = paymentRepository.findStaleCryptoPending(
                threshold, PageRequest.of(0, BATCH_SIZE));
        if (stale.isEmpty()) {
            return;
        }
        log.info("Reconcile: found {} stale CryptoBot payments to check", stale.size());
        for (Payment p : stale) {
            try {
                CryptoBotClient.InvoiceResponse inv = cryptoBotClient.getInvoice(p.getCryptoInvoiceId());
                if (inv != null && "paid".equals(inv.getStatus())) {
                    log.warn("Reconciled stale crypto payment id={} invoice={}: webhook missed, processing as paid",
                            p.getId(), p.getCryptoInvoiceId());
                    paymentProcessingService.handleCryptoSucceeded(p.getCryptoInvoiceId());
                } else if (isOlderThanGiveUp(p, now)) {
                    markAsFailed(p.getId(), "crypto invoice not paid after " + GIVE_UP_HOURS + "h");
                }
            } catch (Exception e) {
                log.error("Crypto reconcile error for payment id={} invoice={}",
                        p.getId(), p.getCryptoInvoiceId(), e);
            }
        }
    }

    /**
     * Ретраит застрявшую активацию Stars: telegram_charge_id выставлен (звёзды списаны), но
     * платёж в PENDING — активация не прошла (AccessProvider был недоступен), а у Stars нет
     * webhook-передоставки. Повтор идемпотентен. FAILED НЕ ставим: деньги списаны — платёж
     * нужно либо активировать, либо вернуть вручную, но не «потерять» молча.
     */
    private void reconcileStaleStars(LocalDateTime now) {
        LocalDateTime threshold = now.minusMinutes(MIN_AGE_MINUTES);
        List<Payment> stale = paymentRepository.findStaleStarsPending(
                threshold, PageRequest.of(0, BATCH_SIZE));
        if (stale.isEmpty()) {
            return;
        }
        log.info("Reconcile: found {} stale Stars payments (charged but not activated) to retry", stale.size());
        for (Payment p : stale) {
            try {
                log.warn("Retrying Stars activation for payment id={} chargeId={}",
                        p.getId(), p.getTelegramChargeId());
                paymentProcessingService.handleStarsSucceeded(
                        String.valueOf(p.getId()), p.getTelegramChargeId(), 0L);
            } catch (Exception e) {
                log.error("Stars reconcile retry error for payment id={}", p.getId(), e);
            }
        }
    }

    /**
     * Опрашивает YK API по одному платежу и приводит локальный статус в консистентное состояние.
     * Не транзакционен: вся работа делегируется в {@link PaymentProcessingService} (его методы
     * @Transactional через proxy), либо в {@link #markAsFailed}, где нам достаточно атомарности
     * единичного {@code save()} от Spring Data JPA.
     */
    private void reconcileOne(Payment payment, LocalDateTime now) {
        String ykId = payment.getYookassaPaymentId();
        YooKassaClient.PaymentResponse actual;
        try {
            actual = yooKassaClient.getPayment(ykId);
        } catch (Exception e) {
            // 404 = платёж не существует на стороне YK (например, тестовая запись или давно
            // протухший). После GIVE_UP_HOURS помечаем FAILED, чтобы не висел вечно.
            if (isNotFound(e)) {
                log.warn("Reconcile: YK getPayment 404 for paymentId={} ykId={}",
                        payment.getId(), ykId);
                if (isOlderThanGiveUp(payment, now)) {
                    markAsFailed(payment.getId(), "404 from YK API");
                }
            } else {
                log.warn("Reconcile: YK getPayment failed for paymentId={} ykId={}: {}",
                        payment.getId(), ykId, e.getMessage());
            }
            return;
        }

        String status = actual == null ? null : actual.getStatus();
        if ("succeeded".equals(status)) {
            log.warn("Reconciled stale payment id={} ykId={}: webhook missed, processing as succeeded",
                    payment.getId(), ykId);
            paymentProcessingService.handleSucceeded(ykId);
        } else if ("canceled".equals(status)) {
            log.info("Reconciled stale payment id={} ykId={}: webhook missed, marking canceled",
                    payment.getId(), ykId);
            paymentProcessingService.handleCanceled(ykId);
        } else if ("waiting_for_capture".equals(status)) {
            // YK иногда задерживает auto-capture (наш default `capture=true`) для дополнительных
            // проверок — 3DS, fraud-hold. Обычно resolves секундами, но бывает залипает. Раньше
            // мы 24h ждали и помечали FAILED, оставляя юзера без подписки за оплаченные деньги.
            // Теперь — пушим capture явно. Идемпотентно: если уже succeeded, YK вернёт succeeded.
            log.info("Reconcile: payment id={} ykId={} stuck in waiting_for_capture, forcing capture",
                    payment.getId(), ykId);
            try {
                YooKassaClient.PaymentResponse captured = yooKassaClient.capturePayment(ykId);
                if ("succeeded".equals(captured.getStatus())) {
                    paymentProcessingService.handleSucceeded(ykId);
                } else if ("canceled".equals(captured.getStatus())) {
                    paymentProcessingService.handleCanceled(ykId);
                } else {
                    // Всё ещё странный статус (pending? waiting_for_capture?) — log и оставляем
                    // на следующий тик. Если упорно висит — give-up branch ниже сработает.
                    log.warn("Reconcile: capturePayment for {} returned status={}, will retry next tick",
                            ykId, captured.getStatus());
                }
            } catch (Exception e) {
                // Capture не получилось (network blip / unexpected YK error) — пробуем в next tick.
                log.warn("Reconcile: capturePayment failed for {}, will retry next tick", ykId, e);
            }
            // Give-up безопасности после 24h, даже если capture продолжает падать (страховка).
            if (isOlderThanGiveUp(payment, now)) {
                markAsFailed(payment.getId(), "stuck waiting_for_capture > " + GIVE_UP_HOURS + "h");
            }
        } else {
            // pending / null — даём ещё шанс, но не вечно.
            log.debug("Reconcile: payment id={} ykId={} still status={}",
                    payment.getId(), ykId, status);
            if (isOlderThanGiveUp(payment, now)) {
                markAsFailed(payment.getId(), "still " + status + " after " + GIVE_UP_HOURS + "h");
            }
        }
    }

    /**
     * Помечает зависший платёж как FAILED. Перед записью перечитываем статус — webhook мог
     * прилететь прямо сейчас и обогнать нас, тогда мы не должны переписать SUCCEEDED на FAILED.
     * <p>
     * Без {@code @Transactional}: метод вызывается из {@link #reconcileOne} в той же класс
     * через {@code this.markAsFailed()} — Spring AOP-proxy на self-call не сработал бы всё
     * равно. Атомарности нам достаточно от Spring Data: {@code save()} коммитится в своей
     * транзакции, читаемое через {@code findById} значение свежее (предыдущая транзакция
     * webhook-обработчика, если была, уже зафиксирована до момента вызова reconciler'а),
     * а {@code @Version} на Payment ловит редкий race с параллельным webhook'ом.
     */
    public void markAsFailed(Long paymentId, String reason) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() != Payment.Status.PENDING) {
                log.debug("markAsFailed: payment id={} no longer PENDING (now {}), skipping",
                        paymentId, p.getStatus());
                return;
            }
            p.setStatus(Payment.Status.FAILED);
            paymentRepository.save(p);
            log.warn("Marked stuck payment id={} ykId={} as FAILED: {}",
                    p.getId(), p.getYookassaPaymentId(), reason);
        });
    }

    /** Старее ли платёж, чем порог «дать ещё шанс». */
    private boolean isOlderThanGiveUp(Payment payment, LocalDateTime now) {
        return payment.getCreatedAt() != null
                && payment.getCreatedAt().isBefore(now.minusHours(GIVE_UP_HOURS));
    }

    /**
     * Распознаёт 404 от YK API. {@link YooKassaClient} оборачивает {@link WebClientResponseException}
     * в {@link IllegalStateException}, сохраняя оригинал как cause — поэтому смотрим cause.
     */
    private boolean isNotFound(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof WebClientResponseException w
                    && w.getStatusCode().value() == 404) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
