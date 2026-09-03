package com.orchestra.subscriptions;

import com.orchestra.subscriptions.bot.CallbackRouter;
import com.orchestra.subscriptions.bot.EmailBotHandler;
import com.orchestra.subscriptions.bot.PaymentHandler;
import com.orchestra.subscriptions.bot.ScreenHandler;
import com.orchestra.subscriptions.bot.UserInputHandler;
import com.orchestra.subscriptions.bot.SubscriptionBot;
import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.EmailSettingsService;
import com.orchestra.subscriptions.service.NotificationService;
import com.orchestra.subscriptions.service.PaymentProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест wiring'а ПОЛНОГО прод-графа бинов. Остальные интеграционные тесты мокают
 * {@code NotificationService} (hub циклов SubscriptionBot ↔ сервисы) — поэтому они НЕ ловят
 * {@code BeanCurrentlyInCreationException}, если кто-то добавит зависимость, замыкающую
 * цикл без {@code @Lazy}-ребра (реальный случай: SubscriptionBot → UserInputHandler →
 * EmailBotHandler → EmailSettingsService → NotificationService → SubscriptionBot — цикл рвётся
 * ТОЛЬКО {@code @Lazy} на UserInputHandler в ctor SubscriptionBot). Здесь мокаются лишь внешние
 * API-клиенты (их {@code @PostConstruct} лезут в сеть); всё внутреннее — настоящее.
 * Падение этого теста = прод не стартует.
 */
@SpringBootTest
@Testcontainers
class ContextWiringIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    YooKassaClient yooKassaClient;
    @MockitoBean
    CryptoBotClient cryptoBotClient;

    @Autowired
    SubscriptionBot subscriptionBot;
    @Autowired
    NotificationService notificationService;
    @Autowired
    CallbackRouter callbackRouter;
    @Autowired
    ScreenHandler screenHandler;
    @Autowired
    PaymentHandler paymentHandler;
    @Autowired
    UserInputHandler userInputHandler;
    @Autowired
    EmailBotHandler emailBotHandler;
    @Autowired
    EmailSettingsService emailSettingsService;
    @Autowired
    DeviceTrackingService deviceTrackingService;
    @Autowired
    PaymentProcessingService paymentProcessingService;

    @Test
    void fullProductionBeanGraph_wiresWithoutCircularDependencies() {
        // Сам факт, что контекст поднялся — и есть проверка. Ассерты ниже фиксируют,
        // что ключевые бины (включая новые: reply-флоу, email-паритет, детект устройств)
        // реально созданы, а не вырезаны условной конфигурацией.
        assertThat(subscriptionBot).isNotNull();
        assertThat(notificationService).isNotNull();
        assertThat(callbackRouter).isNotNull();
        assertThat(screenHandler).isNotNull();
        assertThat(paymentHandler).isNotNull();
        assertThat(userInputHandler).isNotNull();
        assertThat(emailBotHandler).isNotNull();
        assertThat(emailSettingsService).isNotNull();
        assertThat(deviceTrackingService).isNotNull();
        assertThat(paymentProcessingService).isNotNull();
    }
}
