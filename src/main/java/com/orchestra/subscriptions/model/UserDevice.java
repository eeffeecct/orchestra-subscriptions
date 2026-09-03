package com.orchestra.subscriptions.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Устройство юзера, замеченное на подписочном эндпоинте {@code /sub/<token>}.
 * Идентичность — HWID, который клиентское приложение (и совместимые клиенты) шлют заголовком {@code x-hwid}
 * при каждом обновлении подписки; модель/ОС берём из {@code x-device-model} /
 * {@code x-device-os} / {@code x-ver-os}. Клиенты без HWID (v2rayN, Hiddify и т.п.)
 * здесь не регистрируются и в лимит не считаются.
 * <p>
 * {@code telegram_id} — снапшот, не FK (конвенция репо: referrals/magic_links так же).
 * Уникальность пары (telegram_id, hwid) — одно устройство = одна строка, повторные
 * фетчи только двигают {@code last_seen_at}.
 */
@Entity
@Table(name = "user_devices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    /** HWID из заголовка {@code x-hwid} (UUID-подобная строка 10-64 симв., см. DeviceTrackingService). */
    @Column(name = "hwid", nullable = false, length = 64)
    private String hwid;

    /**
     * К какой подписке относится устройство. Лимит устройств живёт на подписке, поэтому
     * привязка обязана быть к ней, а не к человеку: иначе смена ступени на одной подписке
     * отключала бы устройства другой. {@code null} — строка старше этой правки.
     */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    /** Сырой {@code x-device-model}: iOS — идентификатор вида «iPhone15,2», Android — код модели. */
    @Column(name = "device_model", length = 128)
    private String deviceModel;

    /** {@code x-device-os}: iOS / Android / Windows / macOS. */
    @Column(name = "device_os", length = 64)
    private String deviceOs;

    /** {@code x-ver-os}: версия ОС, напр. «18.3». */
    @Column(name = "os_version", length = 64)
    private String osVersion;

    /** User-Agent клиента на момент последнего фетча, напр. «клиентское приложение/4.6.0». */
    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /**
     * Пользовательское имя устройства («телефон мамы») — задаётся в Mini App. NULL = не задано,
     * показываем железное имя из {@code DeviceNames.label}. Для семейной подписки, где три
     * одинаковых «iPhone15,2» неотличимы.
     */
    @Column(name = "custom_name", length = 64)
    private String customName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /** Обновляется на фетчах с шагом ≥1ч (см. DeviceTrackingService) — не пишем БД на каждый полл. */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    public enum Status {
        /** Занимает слот, получает ноды. */
        ACTIVE,
        /** Сверх лимита: нод не получает; авто-активируется, когда слот освободится. */
        BLOCKED,
        /** Отключено юзером («Отключить» в боте/Mini App): нод не получает, слот не занимает,
         *  само не возвращается — только кнопкой «Вернуть». */
        REVOKED
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (firstSeenAt == null) firstSeenAt = now;
        if (lastSeenAt == null) lastSeenAt = now;
        if (status == null) status = Status.ACTIVE;
    }
}
