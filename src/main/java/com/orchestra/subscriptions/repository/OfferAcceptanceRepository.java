package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.OfferAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferAcceptanceRepository extends JpaRepository<OfferAcceptance, Long> {
}
