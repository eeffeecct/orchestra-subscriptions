package com.orchestra.subscriptions.util;

import java.util.Map;

/**
 * Человекочитаемые имена устройств из заголовков клиентское приложение ({@code x-device-model},
 * {@code x-device-os}, {@code x-ver-os}) — для уведомлений «устройство подключено»
 * и экрана «Мои устройства».
 * <p>
 * iOS шлёт сырой идентификатор железа («iPhone15,2»), не маркетинговое имя — маппим
 * ходовые модели вручную (полного официального справочника в рантайме нет). Android шлёт
 * код модели производителя («ELP-NX1») — оставляем как есть, вместе с версией ОС юзер
 * своё устройство узнаёт. Неизвестный идентификатор — тоже как есть: хуже не станет.
 */
public final class DeviceNames {

    private DeviceNames() {
    }

    /** Apple hardware identifier → маркетинговое имя (ходовые модели 2019+). */
    private static final Map<String, String> APPLE_MODELS = Map.ofEntries(
            Map.entry("iPhone11,8", "iPhone XR"),
            Map.entry("iPhone12,1", "iPhone 11"),
            Map.entry("iPhone12,3", "iPhone 11 Pro"),
            Map.entry("iPhone12,5", "iPhone 11 Pro Max"),
            Map.entry("iPhone12,8", "iPhone SE 2"),
            Map.entry("iPhone13,1", "iPhone 12 mini"),
            Map.entry("iPhone13,2", "iPhone 12"),
            Map.entry("iPhone13,3", "iPhone 12 Pro"),
            Map.entry("iPhone13,4", "iPhone 12 Pro Max"),
            Map.entry("iPhone14,4", "iPhone 13 mini"),
            Map.entry("iPhone14,5", "iPhone 13"),
            Map.entry("iPhone14,2", "iPhone 13 Pro"),
            Map.entry("iPhone14,3", "iPhone 13 Pro Max"),
            Map.entry("iPhone14,6", "iPhone SE 3"),
            Map.entry("iPhone14,7", "iPhone 14"),
            Map.entry("iPhone14,8", "iPhone 14 Plus"),
            Map.entry("iPhone15,2", "iPhone 14 Pro"),
            Map.entry("iPhone15,3", "iPhone 14 Pro Max"),
            Map.entry("iPhone15,4", "iPhone 15"),
            Map.entry("iPhone15,5", "iPhone 15 Plus"),
            Map.entry("iPhone16,1", "iPhone 15 Pro"),
            Map.entry("iPhone16,2", "iPhone 15 Pro Max"),
            Map.entry("iPhone17,3", "iPhone 16"),
            Map.entry("iPhone17,4", "iPhone 16 Plus"),
            Map.entry("iPhone17,1", "iPhone 16 Pro"),
            Map.entry("iPhone17,2", "iPhone 16 Pro Max"),
            Map.entry("iPhone17,5", "iPhone 16e"),
            Map.entry("iPhone18,1", "iPhone 17 Pro"),
            Map.entry("iPhone18,2", "iPhone 17 Pro Max"),
            Map.entry("iPhone18,3", "iPhone 17"),
            Map.entry("iPhone18,4", "iPhone Air"));

    /**
     * Собирает подпись устройства: «iPhone 14 Pro (iOS 18.3)», «ELP-NX1 (Android 15)»,
     * «Windows» — из того, что клиент прислал. Все поля могут быть null/пустыми;
     * совсем без данных — «Неизвестное устройство».
     */
    public static String label(String model, String os, String osVersion) {
        String prettyModel = prettyModel(model);
        String osPart = joinNonBlank(os, osVersion);
        if (!prettyModel.isBlank() && !osPart.isBlank()) {
            return prettyModel + " (" + osPart + ")";
        }
        if (!prettyModel.isBlank()) {
            return prettyModel;
        }
        if (!osPart.isBlank()) {
            return osPart;
        }
        return "Неизвестное устройство";
    }

    /** Маркетинговое имя для известных Apple-идентификаторов, иначе исходная строка (trimmed). */
    public static String prettyModel(String model) {
        if (model == null || model.isBlank()) return "";
        String trimmed = model.trim();
        return APPLE_MODELS.getOrDefault(trimmed, trimmed);
    }

    private static String joinNonBlank(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) return a.trim() + " " + b.trim();
        if (hasA) return a.trim();
        if (hasB) return b.trim();
        return "";
    }
}
