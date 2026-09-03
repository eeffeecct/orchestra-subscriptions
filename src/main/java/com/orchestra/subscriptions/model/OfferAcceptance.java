package com.orchestra.subscriptions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Отметка о том, что человек принял публичную оферту при первом запуске бота.
 * <p>
 * Живёт отдельно от {@code customers}, потому что согласие берётся раньше, чем появляется
 * строка человека: на {@code /start} её ещё нет, а завести заранее нельзя —
 * {@code AccessService.createTrialAccess} отказывает по самому факту существования строки
 * и человек молча остался бы без триала.
 * <p>
 * Ключ — сам {@code telegram_id}: согласие одно на человека, повторное «Принять»
 * перезаписывает ту же строку и дублей не создаёт.
 */
@Entity
@Table(name = "offer_acceptances")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfferAcceptance {

    @Id
    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    /**
     * Адрес оферты на момент согласия. Текст на Telegraph правится, и без этой отметки
     * запись «согласился тогда-то» перестаёт что-либо подтверждать после первой же правки.
     * Пусто, если {@code OFFER_URL} на тот момент не был настроен.
     */
    @Column(name = "offer_url", length = 512)
    private String offerUrl;

    /**
     * Редакция оферты на момент акцепта — то, чего не даёт {@link #offerUrl}: адрес на
     * Telegraph постоянный, а текст по нему правится на месте. Значение приходит из
     * {@code app.telegram.offer-revision}, владелец меняет его вместе с текстом.
     * <p>
     * {@code null} у акцептов до появления поля и у тех, где параметр не настроен —
     * это честное «неизвестно», подставлять текущую редакцию задним числом нельзя.
     */
    @Column(name = "offer_revision", length = 64)
    private String offerRevision;
}
