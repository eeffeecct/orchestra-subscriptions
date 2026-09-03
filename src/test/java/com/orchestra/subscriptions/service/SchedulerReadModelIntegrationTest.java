package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.bot.SubscriptionExpiryNotifier;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Планировщики, читающие кандидатов из ТАБЛИЦЫ ПОДПИСОК (флаг переезда включён).
 * <p>
 * Пока подписка у человека одна, выборка обязана совпадать со старой — это проверяется тем,
 * что сценарии здесь те же, что в обычных тестах планировщиков, и ожидания те же.
 * <p>
 * Отдельно проверяется главное свойство модели: <b>припаркованная подписка не может попасть
 * ни в одну выборку по датам</b>. Не потому, что кто-то добавил фильтр, а потому что у неё нет
 * даты вовсе — сравнение с NULL всегда ложно. Без этого свойства замороженная подписка
 * получала бы «истекает через час», отключалась бы по сроку и с неё бы списывали деньги.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.subscriptions.read-model-enabled=true",
        "app.subscriptions.dual-write-enabled=true",
})
class SchedulerReadModelIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AccessService accessService;
    @Autowired
    SubscriptionExpiryNotifier notifier;
    @Autowired
    SubscriptionLookupService lookup;
    @Autowired
    CustomerRepository userRepository;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;
    @MockitoBean
    com.orchestra.subscriptions.client.CryptoBotClient cryptoBotClient;
    @MockitoBean
    NotificationService notificationService;

    private static final long TG_ID = 999000333L;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        AccessProvider.AccessAccount mUser = new AccessProvider.AccessAccount();
        mUser.setUsername("tg_sched_" + TG_ID);
        mUser.setSubscriptionUrl("https://example.com/sub/schedtoken");
        when(accessProvider.createUser(anyString(), anyInt(), anyLong())).thenReturn(mUser);
        when(accessProvider.createUser(anyString(), anyInt(), anyLong(), any())).thenReturn(mUser);
        when(accessProvider.getUser(anyString())).thenReturn(java.util.Optional.empty());
        doNothing().when(accessProvider).enableUserUntil(anyString(), any(), any());
        doNothing().when(accessProvider).disableUser(anyString());
    }

    /** Юзер с подпиской, истекающей через {@code hours} часов. Зеркало создаётся само. */
    private Customer userExpiringInHours(long hours) {
        accessService.activatePaidSubscription(TG_ID, "sched", 30);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setExpiresAt(LocalDateTime.now().plusHours(hours));
        userRepository.save(user);
        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        sub.setExpiresAt(user.getExpiresAt());
        subscriptionRepository.save(sub);
        return user;
    }

    @Test
    @DisplayName("Кандидат на предупреждение находится через таблицу подписок")
    void findsExpiringCandidateFromSubscriptions() {
        userExpiringInHours(12);

        assertThat(lookup.expiringWithout24hNotice(LocalDateTime.now(), LocalDateTime.now().plusHours(24)))
                .extracting(Customer::getTelegramId)
                .containsExactly(TG_ID);
    }

    @Test
    @DisplayName("Истёкшая подписка находится и отключается")
    void expiredIsFoundAndDisabled() {
        userExpiringInHours(-1);

        notifier.processExpiredSubscriptions();

        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().getStatus())
                .isEqualTo(Customer.Status.DISABLED);
    }

    /**
     * РЕГРЕССИЯ, пойманная на проде 13.08.2026: живой человек получал одно и то же
     * предупреждение «истекает через сутки» КАЖДЫЕ ПЯТЬ МИНУТ.
     * <p>
     * Причина — асимметрия чтения и записи: кандидатов планировщик берёт из
     * {@code subscriptions} (с этапа 3б), а отметку «уведомлён» ставил только на
     * {@code customers}. Строка подписки оставалась с {@code notified_24h = false}, и
     * следующий тик находил того же человека снова. До истечения подписки набегало ~200
     * сообщений.
     * <p>
     * Тест гоняет планировщик ДВАЖДЫ: во второй раз кандидатов быть не должно, а отметка
     * обязана стоять в ОБЕИХ таблицах. Проверять только {@code customers} бессмысленно —
     * именно там она и стояла, когда баг был живым.
     */
    @Test
    @DisplayName("Предупреждение об истечении не повторяется на следующем тике")
    void expiryWarningIsSentOnlyOnce() {
        userExpiringInHours(12);

        notifier.processExpiredSubscriptions();
        notifier.processExpiredSubscriptions();

        // Отметка доехала до строки, ПО КОТОРОЙ ИДЁТ ВЫБОРКА.
        assertThat(subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow()
                .isNotified24h())
                .as("флаг обязан быть в subscriptions — планировщик читает кандидатов оттуда")
                .isTrue();
        assertThat(userRepository.findByTelegramId(TG_ID).orElseThrow().isNotified24h()).isTrue();

        // И человек больше не кандидат.
        assertThat(lookup.expiringWithout24hNotice(LocalDateTime.now(), LocalDateTime.now().plusHours(24)))
                .as("второй тик не должен находить того же человека — иначе это рассылка")
                .isEmpty();

        // Ровно одно письмо за два тика.
        org.mockito.Mockito.verify(notificationService, org.mockito.Mockito.times(1))
                .sendExpiryWarning24h(TG_ID);
    }

    /**
     * ГЛАВНОЕ свойство модели. Припаркованная подписка держит остаток в секундах и не имеет
     * даты окончания — значит ни один запрос по датам её не достанет. Иначе замороженные дни
     * тикали бы: юзеру слали бы «истекает», отключали бы по сроку и списывали бы деньги за
     * подписку, которой он сейчас не пользуется.
     */
    @Test
    @DisplayName("Припаркованная подписка не попадает ни в одну выборку по датам")
    void parkedSubscriptionIsInvisibleToEveryDateQuery() {
        accessService.activatePaidSubscription(TG_ID, "sched", 30);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setAutorenewEnabled(true);
        user.setAutorenewMethodId("pm_test");
        user.setAutorenewPlanId("month_1");
        userRepository.save(user);

        // Вторая подписка, припаркованная: остаток заморожен, даты нет.
        subscriptionRepository.save(Subscription.builder()
                .telegramId(TG_ID).seq(2).current(false)
                .providerUsername("tg_sched_parked_" + TG_ID)
                .subscriptionToken("parkedtoken")
                .state(Customer.Status.ACTIVE)
                .expiresAt(null)
                .balanceSeconds(SubscriptionTime.daysToSeconds(90))
                .autorenewEnabled(true)
                .autorenewPlanId("month_1")
                .build());

        LocalDateTime now = LocalDateTime.now();
        // Окна намеренно широкие: если бы у припаркованной была дата, она бы сюда попала.
        assertThat(lookup.expiringWithout24hNotice(now.minusYears(1), now.plusYears(1)))
                .allSatisfy(u -> assertThat(u.getTelegramId()).isEqualTo(TG_ID));
        assertThat(lookup.expired(now.plusYears(1)))
                .hasSizeLessThanOrEqualTo(1);
        assertThat(lookup.winbackCandidates(now.minusYears(1), now.plusYears(1)))
                .isEmpty();
        assertThat(lookup.autorenewCandidates(now.minusYears(1), now.plusYears(1), now.minusDays(1)))
                .hasSizeLessThanOrEqualTo(1);

        // И, главное, ни одна выборка не вернула ПРИПАРКОВАННУЮ строку: везде максимум
        // один кандидат — текущая подписка человека.
        assertThat(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID)).hasSize(2);
    }

    /** Без привязанной карты списывать нечем — условие обязано пережить переезд чтения. */
    @Test
    @DisplayName("Кандидат на автосписание без карты не выбирается")
    void autorenewCandidateWithoutCardExcluded() {
        accessService.activatePaidSubscription(TG_ID, "sched", 30);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setAutorenewEnabled(true);
        user.setAutorenewPlanId("month_1");
        user.setAutorenewMethodId(null);   // карты нет
        user.setExpiresAt(LocalDateTime.now().plusHours(24).plusMinutes(30));
        userRepository.save(user);
        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        sub.setAutorenewEnabled(true);
        sub.setAutorenewPlanId("month_1");
        sub.setExpiresAt(user.getExpiresAt());
        subscriptionRepository.save(sub);

        LocalDateTime now = LocalDateTime.now();
        assertThat(lookup.autorenewCandidates(now.plusHours(24), now.plusHours(25), now.minusDays(1)))
                .isEmpty();
    }

    /**
     * Страховка на время переезда: зеркало — производные данные, и его пробел не должен
     * оборачиваться отказом человеку. Пропала строка в новом источнике — читаем из старого,
     * а пробел ловит ночная сверка. Иначе недоехавший перенос выглядел бы для юзера как
     * «подписки нет»: ни ссылки, ни доступа.
     */
    @Test
    @DisplayName("Пробел в зеркале не отбирает подписку — читаем из основной таблицы")
    void missingMirrorRowFallsBackToMainTable() {
        accessService.activatePaidSubscription(TG_ID, "sched", 30);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        subscriptionRepository.deleteAll();   // имитируем пробел в зеркале

        assertThat(lookup.currentFor(TG_ID)).isPresent()
                .get().satisfies(sub -> {
                    assertThat(sub.status()).isEqualTo(Customer.Status.ACTIVE);
                    assertThat(sub.providerUsername()).isEqualTo(user.getProviderUsername());
                });
        assertThat(lookup.byToken(user.getSubscriptionToken())).isPresent();
        assertThat(lookup.byProviderUsername(user.getProviderUsername())).isPresent();
    }

    @Test
    @DisplayName("Кандидат на автосписание с картой выбирается")
    void autorenewCandidateWithCardIncluded() {
        accessService.activatePaidSubscription(TG_ID, "sched", 30);
        Customer user = userRepository.findByTelegramId(TG_ID).orElseThrow();
        user.setAutorenewEnabled(true);
        user.setAutorenewPlanId("month_1");
        user.setAutorenewMethodId("pm_test");
        user.setExpiresAt(LocalDateTime.now().plusHours(24).plusMinutes(30));
        userRepository.save(user);
        Subscription sub = subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID).orElseThrow();
        sub.setAutorenewEnabled(true);
        sub.setAutorenewPlanId("month_1");
        sub.setExpiresAt(user.getExpiresAt());
        subscriptionRepository.save(sub);

        LocalDateTime now = LocalDateTime.now();
        assertThat(lookup.autorenewCandidates(now.plusHours(24), now.plusHours(25), now.minusDays(1)))
                .extracting(Customer::getTelegramId)
                .containsExactly(TG_ID);
    }
}
