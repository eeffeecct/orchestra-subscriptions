package com.orchestra.subscriptions.repository;

import com.orchestra.subscriptions.model.PromoCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {

    /** Lookup по коду. Сервис нормализует ввод в верхний регистр перед вызовом. */
    Optional<PromoCode> findByCode(String code);

    /** Все коды от новых к старым — для /promo list. */
    List<PromoCode> findAllByOrderByCreatedAtDesc();

    /**
     * Декремент при refund. Идемпотентность гарантируется на стороне сервиса
     * через {@code PromoUsage.reverted_at} — мы дёрнем decrement только один раз
     * на usage. Защита от уйти в минус: усердствует сама БД через max(0, ...).
     */
    @Modifying
    @Query("UPDATE PromoCode p SET p.usedCount = CASE WHEN p.usedCount > 0 THEN p.usedCount - 1 ELSE 0 END " +
            "WHERE p.id = :id")
    int decrementUsedCount(@Param("id") Long id);
}
