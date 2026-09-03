package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.bot.TelegramSender;
import com.orchestra.subscriptions.bot.SubscriptionBot;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class BroadcastServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** Premium-эмодзи: в тексте обычный глиф, «анимированность» — вот эта сущность. */
    private static final MessageEntity PREMIUM = MessageEntity.builder()
            .type("custom_emoji").offset(0).length(2).customEmojiId("5000000000000000001").build();

    @Autowired
    BroadcastService broadcastService;

    @Autowired
    CustomerRepository userRepository;

    /**
     * Отправка идёт через {@link TelegramSender}, а не через бота: фото уезжает multipart'ом,
     * и {@code SendPhoto} в {@code SubscriptionBot.execute(BotApiMethod)} не пролезает по типу.
     */
    @MockitoBean
    TelegramSender telegramSender;

    @MockitoBean
    SubscriptionBot subscriptionBot;

    @MockitoBean
    AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;



    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void prepare_shouldCountActiveAndTotal() {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.ACTIVE));
        userRepository.save(user(3L, Customer.Status.DISABLED));

        BroadcastService.BroadcastDraft draft = broadcastService.prepare(
                BroadcastService.BroadcastContent.html("hello <b>world</b>"));

        assertThat(draft.id()).isNotBlank();
        assertThat(draft.content().text()).isEqualTo("hello <b>world</b>");
        assertThat(draft.activeCount()).isEqualTo(2);
        assertThat(draft.disabledCount()).isEqualTo(1);
        assertThat(draft.totalCount()).isEqualTo(3);
    }

    /**
     * Винбек уходит только отключившимся. Без отдельной цели его пришлось бы слать через ALL,
     * то есть тем же письмом «вернитесь» задеть действующих подписчиков — не та аудитория и
     * лишний повод отписаться.
     */
    @Test
    void sendBlocking_toDisabled_shouldSkipActiveUsers() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.DISABLED));
        userRepository.save(user(3L, Customer.Status.DISABLED));
        when(telegramSender.execute(any(SendMessage.class))).thenReturn(null);

        BroadcastService.BroadcastStats stats = broadcastService.sendBlocking(
                BroadcastService.BroadcastContent.html("вернитесь"), BroadcastService.Target.DISABLED);

        assertThat(stats.success()).isEqualTo(2);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender, org.mockito.Mockito.times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().stream().map(SendMessage::getChatId).toList())
                .as("действующий подписчик винбек получать не должен")
                .containsExactlyInAnyOrder("2", "3");
    }

    @Test
    void consumeDraft_shouldReturnContentOnceThenNull() {
        BroadcastService.BroadcastDraft draft = broadcastService.prepare(
                BroadcastService.BroadcastContent.html("msg"));

        assertThat(broadcastService.consumeDraft(draft.id()).text()).isEqualTo("msg");
        assertThat(broadcastService.consumeDraft(draft.id())).isNull(); // one-shot
    }

    @Test
    void consumeDraft_shouldReturnNullForUnknownId() {
        assertThat(broadcastService.consumeDraft("fake-id")).isNull();
    }

    /** Черновик обязан пережить хранение целиком: и картинка, и premium-эмодзи. */
    @Test
    void consumeDraft_shouldKeepPhotoAndPremiumEmoji() {
        BroadcastService.BroadcastDraft draft = broadcastService.prepare(
                new BroadcastService.BroadcastContent("🎉 скидка", List.of(PREMIUM), "AgACfile", false));

        BroadcastService.BroadcastContent restored = broadcastService.consumeDraft(draft.id());

        assertThat(restored.photoFileId()).isEqualTo("AgACfile");
        assertThat(restored.customEmojiCount()).isEqualTo(1);
        assertThat(restored.entities().getFirst().getCustomEmojiId()).isEqualTo("5000000000000000001");
    }

    @Test
    void sendBlocking_toActive_shouldOnlyMessageActiveUsers() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.ACTIVE));
        userRepository.save(user(3L, Customer.Status.DISABLED));
        when(telegramSender.execute(any(SendMessage.class))).thenReturn(null);

        BroadcastService.BroadcastStats stats = broadcastService.sendBlocking(
                BroadcastService.BroadcastContent.html("ping"), BroadcastService.Target.ACTIVE);

        assertThat(stats.success()).isEqualTo(2);
        assertThat(stats.failed()).isZero();

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender, org.mockito.Mockito.times(2)).execute(captor.capture());
        List<String> chatIds = captor.getAllValues().stream().map(SendMessage::getChatId).toList();
        assertThat(chatIds).containsExactlyInAnyOrder("1", "2");
        // Парс-мод везде HTML
        assertThat(captor.getAllValues()).allSatisfy(m -> assertThat(m.getParseMode()).isEqualTo("HTML"));
    }

    /**
     * Рассылка с картинкой. Уходит {@code file_id} уже залитого в Telegram файла — иначе на
     * каждого получателя пришлась бы отдельная закачка одной и той же картинки.
     */
    @Test
    void sendBlocking_withPhoto_shouldSendPhotoWithCaptionToEveryone() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.ACTIVE));
        when(telegramSender.execute(any(SendPhoto.class))).thenReturn(null);

        BroadcastService.BroadcastStats stats = broadcastService.sendBlocking(
                new BroadcastService.BroadcastContent("Смотрите", List.of(), "AgACfile", true),
                BroadcastService.Target.ACTIVE);

        assertThat(stats.success()).isEqualTo(2);
        ArgumentCaptor<SendPhoto> captor = ArgumentCaptor.forClass(SendPhoto.class);
        verify(telegramSender, org.mockito.Mockito.times(2)).execute(captor.capture());
        verify(telegramSender, never()).execute(any(SendMessage.class));
        assertThat(captor.getAllValues()).allSatisfy(p -> {
            assertThat(p.getPhoto().getAttachName()).isEqualTo("AgACfile");
            assertThat(p.getCaption()).isEqualTo("Смотрите");
            assertThat(p.getParseMode()).isEqualTo("HTML");
        });
        assertThat(captor.getAllValues().stream().map(SendPhoto::getChatId).toList())
                .containsExactlyInAnyOrder("1", "2");
    }

    /**
     * Главное, ради чего рассылка вообще знает про сущности: premium-эмодзи доезжает до
     * каждого получателя со своим id, а не превращается в обычный глиф.
     */
    @Test
    void sendBlocking_withPremiumEmoji_shouldCarryEntitiesToEveryRecipient() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.ACTIVE));
        when(telegramSender.execute(any(SendPhoto.class))).thenReturn(null);

        broadcastService.sendBlocking(
                new BroadcastService.BroadcastContent("🎉 скидка", List.of(PREMIUM), "AgACfile", false),
                BroadcastService.Target.ACTIVE);

        ArgumentCaptor<SendPhoto> captor = ArgumentCaptor.forClass(SendPhoto.class);
        verify(telegramSender, org.mockito.Mockito.times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(p -> {
            assertThat(p.getParseMode()).as("сущности и parse_mode вместе Telegram не примет").isNull();
            assertThat(p.getCaptionEntities()).singleElement().satisfies(e -> {
                assertThat(e.getType()).isEqualTo("custom_emoji");
                assertThat(e.getCustomEmojiId()).isEqualTo("5000000000000000001");
            });
        });
    }

    @Test
    void sendBlocking_toAll_shouldMessageEveryoneIncludingDisabled() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.DISABLED));
        when(telegramSender.execute(any(SendMessage.class))).thenReturn(null);

        BroadcastService.BroadcastStats stats = broadcastService.sendBlocking(
                BroadcastService.BroadcastContent.html("ping"), BroadcastService.Target.ALL);

        assertThat(stats.success()).isEqualTo(2);
        verify(telegramSender, org.mockito.Mockito.times(2)).execute(any(SendMessage.class));
    }

    @Test
    void sendBlocking_shouldContinueWhenOneSendFails() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.ACTIVE));
        userRepository.save(user(3L, Customer.Status.ACTIVE));

        // Юзер 2 заблокировал бот — бросаем 403
        when(telegramSender.execute(any(SendMessage.class)))
                .thenReturn(null) // 1
                .thenThrow(new TelegramApiException("blocked")) // 2
                .thenReturn(null); // 3

        BroadcastService.BroadcastStats stats = broadcastService.sendBlocking(
                BroadcastService.BroadcastContent.html("ping"), BroadcastService.Target.ACTIVE);

        assertThat(stats.success()).isEqualTo(2);
        assertThat(stats.failed()).isEqualTo(1);
        verify(telegramSender, org.mockito.Mockito.times(3)).execute(any(SendMessage.class));
    }

    /** Недоставленное фото тоже не должно обрывать рассылку — например, протухший file_id. */
    @Test
    void sendBlocking_withPhoto_shouldContinueWhenOneSendFails() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.ACTIVE));
        when(telegramSender.execute(any(SendPhoto.class)))
                .thenThrow(new TelegramApiException("wrong file identifier"))
                .thenReturn(null);

        BroadcastService.BroadcastStats stats = broadcastService.sendBlocking(
                new BroadcastService.BroadcastContent("", List.of(), "AgACfile", true),
                BroadcastService.Target.ACTIVE);

        assertThat(stats.success()).isEqualTo(1);
        assertThat(stats.failed()).isEqualTo(1);
    }

    @Test
    void sendBlocking_shouldApplyRateLimitBetweenSends() throws TelegramApiException {
        // 5 получателей → минимум 4 интервала * 35мс = 140мс
        for (long i = 1; i <= 5; i++) {
            userRepository.save(user(i, Customer.Status.ACTIVE));
        }
        when(telegramSender.execute(any(SendMessage.class))).thenReturn(null);

        long start = System.currentTimeMillis();
        broadcastService.sendBlocking(
                BroadcastService.BroadcastContent.html("ping"), BroadcastService.Target.ACTIVE);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(4 * 35L);
    }

    @Test
    void sendBlocking_shouldCountAllFailuresWhenBotAlwaysThrows() throws TelegramApiException {
        userRepository.save(user(1L, Customer.Status.ACTIVE));
        userRepository.save(user(2L, Customer.Status.ACTIVE));
        doThrow(new TelegramApiException("invalid token"))
                .when(telegramSender).execute(any(SendMessage.class));

        BroadcastService.BroadcastStats stats = broadcastService.sendBlocking(
                BroadcastService.BroadcastContent.html("ping"), BroadcastService.Target.ACTIVE);

        assertThat(stats.success()).isZero();
        assertThat(stats.failed()).isEqualTo(2);
    }

    private Customer user(Long telegramId, Customer.Status status) {
        return Customer.builder()
                .telegramId(telegramId)
                .telegramUsername("user" + telegramId)
                .providerUsername("tg_user" + telegramId)
                .subscriptionLink("https://example.com/sub/" + telegramId)
                .status(status)
                .expiresAt(status == Customer.Status.ACTIVE
                        ? LocalDateTime.now().plusDays(10)
                        : LocalDateTime.now().minusDays(1))
                .build();
    }
}
