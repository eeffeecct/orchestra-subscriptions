package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.MagicLink;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.MagicLinkRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Полный тест магических ссылок через настоящий Postgres + Liquibase. Проверяем
 * жизненный цикл: create, consume (валидно/просрочено/повторно/email-mismatch),
 * cleanup. Мокаем только внешние клиенты, чтобы Spring контекст поднялся.
 */
@SpringBootTest
@Testcontainers
class MagicLinkServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MagicLinkService magicLinkService;

    @Autowired
    MagicLinkRepository magicLinkRepository;

    @Autowired
    CustomerRepository userRepository;

    @MockitoBean
    com.orchestra.subscriptions.access.AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;


    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;


    @BeforeEach
    void setUp() {
        magicLinkRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Customer createUserWithEmail(long telegramId, String email) {
        return userRepository.save(Customer.builder()
                .telegramId(telegramId)
                .providerUsername("sub_" + telegramId)
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .email(email)
                .build());
    }

    // ==================== create ====================

    @Test
    void create_savesTokenWithExpectedFields() {
        Customer user = createUserWithEmail(1L, "user@example.com");
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        String token = magicLinkService.create(user);

        assertThat(token).isNotBlank().hasSizeGreaterThan(40);
        MagicLink saved = magicLinkRepository.findByToken(token).orElseThrow();
        assertThat(saved.getTelegramId()).isEqualTo(1L);
        assertThat(saved.getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getCreatedAt()).isAfter(before);
        assertThat(saved.getExpiresAt())
                .isAfter(saved.getCreatedAt().plusMinutes(14))
                .isBefore(saved.getCreatedAt().plusMinutes(16));
        assertThat(saved.getUsedAt()).isNull();
    }

    @Test
    void create_throwsWhenUserHasNoEmail() {
        Customer user = userRepository.save(Customer.builder()
                .telegramId(2L)
                .providerUsername("sub_2")
                .status(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build()); // email = null

        assertThatThrownBy(() -> magicLinkService.create(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no email");
    }

    @Test
    void create_generatesUniqueTokens() {
        Customer user = createUserWithEmail(3L, "u@example.com");
        String t1 = magicLinkService.create(user);
        String t2 = magicLinkService.create(user);
        assertThat(t1).isNotEqualTo(t2);
    }

    // ==================== consume ====================

    @Test
    void consume_returnsUserAndMarksUsedAndVerifiesEmail() {
        Customer user = createUserWithEmail(10L, "verify@example.com");
        assertThat(user.getEmailVerifiedAt()).isNull();
        String token = magicLinkService.create(user);

        Customer returned = magicLinkService.consume(token);

        assertThat(returned.getTelegramId()).isEqualTo(10L);
        // used_at проставлен
        MagicLink link = magicLinkRepository.findByToken(token).orElseThrow();
        assertThat(link.getUsedAt()).isNotNull();
        // email_verified_at проставлен на юзере
        Customer reloaded = userRepository.findByTelegramId(10L).orElseThrow();
        assertThat(reloaded.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void consume_keepsExistingVerifiedAt_onSecondMagicLink() {
        Customer user = createUserWithEmail(11L, "already@example.com");
        LocalDateTime earlyVerified = LocalDateTime.now().minusDays(3).withNano(0);
        user.setEmailVerifiedAt(earlyVerified);
        userRepository.save(user);

        String token = magicLinkService.create(user);
        magicLinkService.consume(token);

        Customer reloaded = userRepository.findByTelegramId(11L).orElseThrow();
        // Не перезаписываем — иначе теряем «когда впервые подтвердил». Сейчас сервис
        // ставит verified только если был null. Этот тест эту инвариантность защищает.
        assertThat(reloaded.getEmailVerifiedAt())
                .isCloseTo(earlyVerified, within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void consume_throwsOnDoubleUse() {
        Customer user = createUserWithEmail(12L, "once@example.com");
        String token = magicLinkService.create(user);
        magicLinkService.consume(token); // первый раз ок

        assertThatThrownBy(() -> magicLinkService.consume(token))
                .isInstanceOf(MagicLinkService.InvalidMagicLinkException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void consume_throwsOnExpired() {
        Customer user = createUserWithEmail(13L, "old@example.com");
        // Создаём токен и руками сдвигаем expires в прошлое — имитация просрочки
        String token = magicLinkService.create(user);
        MagicLink link = magicLinkRepository.findByToken(token).orElseThrow();
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        magicLinkRepository.save(link);

        assertThatThrownBy(() -> magicLinkService.consume(token))
                .isInstanceOf(MagicLinkService.InvalidMagicLinkException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void consume_throwsOnUnknownToken() {
        assertThatThrownBy(() -> magicLinkService.consume("nonexistent-token"))
                .isInstanceOf(MagicLinkService.InvalidMagicLinkException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void consume_throwsOnEmptyToken() {
        assertThatThrownBy(() -> magicLinkService.consume(""))
                .isInstanceOf(MagicLinkService.InvalidMagicLinkException.class);
        assertThatThrownBy(() -> magicLinkService.consume(null))
                .isInstanceOf(MagicLinkService.InvalidMagicLinkException.class);
    }

    @Test
    void consume_throwsWhenEmailChangedAfterLinkCreated() {
        Customer user = createUserWithEmail(14L, "old-addr@example.com");
        String token = magicLinkService.create(user);

        // Юзер сменил email до клика — snapshot в magic_links не совпадает
        user.setEmail("new-addr@example.com");
        userRepository.save(user);

        assertThatThrownBy(() -> magicLinkService.consume(token))
                .isInstanceOf(MagicLinkService.InvalidMagicLinkException.class)
                .hasMessageContaining("email changed");
    }

    @Test
    void consume_throwsWhenUserDeleted() {
        Customer user = createUserWithEmail(15L, "gone@example.com");
        String token = magicLinkService.create(user);
        userRepository.delete(user);

        assertThatThrownBy(() -> magicLinkService.consume(token))
                .isInstanceOf(MagicLinkService.InvalidMagicLinkException.class)
                .hasMessageContaining("user not found");
    }

    // ==================== cleanup ====================

    @Test
    void cleanup_removesOnlyTokensOlderThanRetention() {
        Customer user = createUserWithEmail(20L, "cleanup@example.com");
        // Свежий токен — должен остаться
        String fresh = magicLinkService.create(user);
        // Старый токен — expires_at давно в прошлом + cleanup retention
        String old = magicLinkService.create(user);
        MagicLink oldLink = magicLinkRepository.findByToken(old).orElseThrow();
        oldLink.setExpiresAt(LocalDateTime.now()
                .minus(MagicLinkService.CLEANUP_RETENTION)
                .minusHours(1));
        magicLinkRepository.save(oldLink);

        magicLinkService.cleanupExpired();

        assertThat(magicLinkRepository.findByToken(fresh)).isPresent();
        assertThat(magicLinkRepository.findByToken(old)).isEmpty();
    }

    @Test
    void cleanup_isNoOpWhenNothingToRemove() {
        Customer user = createUserWithEmail(21L, "noop@example.com");
        magicLinkService.create(user);

        // Должно отработать без exceptions, токен на месте
        magicLinkService.cleanupExpired();

        assertThat(magicLinkRepository.count()).isEqualTo(1L);
    }

}
