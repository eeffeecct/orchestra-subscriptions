package com.orchestra.subscriptions.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест сборки подписи устройства из клиентское приложение-заголовков: маппинг Apple-идентификаторов
 * на маркетинговые имена, Android-модели как есть, деградация при отсутствующих полях.
 */
class DeviceNamesTest {

    @Test
    void appleIdentifier_mappedToMarketingName() {
        assertThat(DeviceNames.label("iPhone15,2", "iOS", "18.3"))
                .isEqualTo("iPhone 14 Pro (iOS 18.3)");
    }

    @Test
    void androidModel_keptRaw() {
        assertThat(DeviceNames.label("ELP-NX1", "Android", "15"))
                .isEqualTo("ELP-NX1 (Android 15)");
    }

    @Test
    void unknownAppleIdentifier_keptRaw() {
        assertThat(DeviceNames.label("iPhone99,9", "iOS", "26.0"))
                .isEqualTo("iPhone99,9 (iOS 26.0)");
    }

    @Test
    void noModel_osOnly() {
        assertThat(DeviceNames.label(null, "Windows", null)).isEqualTo("Windows");
        assertThat(DeviceNames.label("", "macOS", "14.2")).isEqualTo("macOS 14.2");
    }

    @Test
    void modelOnly_noOs() {
        assertThat(DeviceNames.label("iPhone16,1", null, null)).isEqualTo("iPhone 15 Pro");
    }

    @Test
    void nothing_fallsBackToUnknown() {
        assertThat(DeviceNames.label(null, null, null)).isEqualTo("Неизвестное устройство");
        assertThat(DeviceNames.label(" ", "", null)).isEqualTo("Неизвестное устройство");
    }
}
