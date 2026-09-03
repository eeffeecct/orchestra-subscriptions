package com.orchestra.subscriptions.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест {@link SubscriptionLinks#extractToken}: тот же алгоритм, что использовался
 * внутри accessLink, вынесен для лукапа «токен → юзер» (customers.subscription_token).
 * Должен совпадать с backfill-SQL миграции 023.
 */
class SubscriptionLinksTokenTest {

    @Test
    void extractsTokenFromFullUrl() {
        assertThat(SubscriptionLinks.extractToken("https://example.com/sub/AbCdEf123"))
                .isEqualTo("AbCdEf123");
    }

    @Test
    void extractsTokenFromRelativeLink() {
        assertThat(SubscriptionLinks.extractToken("/sub/tok123")).isEqualTo("tok123");
    }

    @Test
    void stripsQueryAndFragment() {
        assertThat(SubscriptionLinks.extractToken("https://x/sub/tok?name=y#frag")).isEqualTo("tok");
    }

    @Test
    void nullForLinksWithoutSub() {
        assertThat(SubscriptionLinks.extractToken("https://x/other/path")).isNull();
        assertThat(SubscriptionLinks.extractToken(null)).isNull();
        assertThat(SubscriptionLinks.extractToken("  ")).isNull();
        assertThat(SubscriptionLinks.extractToken("https://x/sub/")).isNull();
    }

    @Test
    void accessLink_stillBuiltFromSameToken() {
        assertThat(SubscriptionLinks.accessLink("https://host:8443/sub/tok", "https://pub"))
                .isEqualTo("https://pub/sub/tok");
    }
}
