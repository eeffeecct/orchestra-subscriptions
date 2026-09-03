package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.PromoUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromoUsageRepository extends JpaRepository<PromoUsage, Long> {

    /** Найти usage по платежу — нужно при refund для отката. */
    Optional<PromoUsage> findByPaymentId(Long paymentId);

    /** Последние применения кода — для /promo info CODE. */
    List<PromoUsage> findTop10ByPromoCodeIdOrderByUsedAtDesc(Long promoCodeId);
}
