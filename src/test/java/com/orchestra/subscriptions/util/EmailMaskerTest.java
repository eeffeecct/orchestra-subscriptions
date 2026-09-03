package com.orchestra.subscriptions.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailMaskerTest {

    @Test
    void mask_nullOrBlank() {
        assertThat(EmailMasker.mask(null)).isEqualTo("null");
        assertThat(EmailMasker.mask("")).isEqualTo("null");
        assertThat(EmailMasker.mask("   ")).isEqualTo("null");
    }

    @Test
    void mask_standardEmail() {
        assertThat(EmailMasker.mask("alex@example.com")).isEqualTo("a***@example.com");
        assertThat(EmailMasker.mask("a@b.com")).isEqualTo("a***@b.com");
        assertThat(EmailMasker.mask("TestUser@Gmail.COM")).isEqualTo("T***@Gmail.COM");
    }

    @Test
    void mask_edgeCases() {
        // Без локальной части
        assertThat(EmailMasker.mask("@example.com")).isEqualTo("***@example.com");
        // Без @ — полностью скрываем
        assertThat(EmailMasker.mask("notanemail")).isEqualTo("***");
    }
}
