package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.SubscriptionSwitchService;
import com.orchestra.subscriptions.service.SubscriptionTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Экран «Мои подписки». Проверяется не вёрстка, а обещания, которые экран даёт человеку:
 * что он назовёт ВСЕ последствия переключения до нажатия, что не покажет ссылку замороженной
 * подписки и что не даст включить пустую.
 */
class SubscriptionScreenHandlerTest {

    private static final long TG_ID = 555L;
    private static final long CHAT_ID = 555L;

    private AppConfig appConfig;
    private SubscriptionRepository subscriptionRepository;
    private SubscriptionSwitchService switchService;
    private DeviceTrackingService deviceTrackingService;
    private com.orchestra.subscriptions.repository.CustomerRepository userRepository;
    private com.orchestra.subscriptions.service.AutoRenewInfoService autoRenewInfo;
    private TelegramSender telegramSender;
    private SubscriptionScreenHandler handler;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
        subscriptionRepository = mock(SubscriptionRepository.class);
        switchService = mock(SubscriptionSwitchService.class);
        deviceTrackingService = mock(DeviceTrackingService.class);
        userRepository = mock(com.orchestra.subscriptions.repository.CustomerRepository.class);
        autoRenewInfo = mock(com.orchestra.subscriptions.service.AutoRenewInfoService.class);
        // По умолчанию — человек без автопродления: пометка «продлевается» не должна
        // появляться сама собой ни на одном из остальных тестов.
        when(userRepository.findByTelegramId(TG_ID)).thenReturn(Optional.of(new Customer()));
        when(autoRenewInfo.forUser(any())).thenReturn(renewInfo(false, null, null));
        telegramSender = mock(TelegramSender.class);
        when(deviceTrackingService.previewDemotion(any())).thenReturn(List.of());
        when(appConfig.findDeviceTierOptional(any())).thenReturn(Optional.empty());
        // Без публичного адреса ссылка отдаётся сырой — тогда тест не проверил бы, что человек
        // получает именно ссылку сервиса с маршрутизацией (см. память проекта: сырой AccessProvider-линк
        // юзеру не показываем нигде).
        when(appConfig.getTelegram().getPublicBaseUrl()).thenReturn("https://bot.example");
        handler = new SubscriptionScreenHandler(appConfig, subscriptionRepository,
                switchService, deviceTrackingService, userRepository, autoRenewInfo, telegramSender);
    }

    @Test
    @DisplayName("Список: ссылка есть только у работающей подписки")
    void listPrintsLinkOnlyForCurrent() throws Exception {
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .thenReturn(List.of(current(1L, 1), parked(2L, 2, 40)));

        handler.sendSubscriptionList(CHAT_ID, TG_ID);

        String text = captureLast().getText();
        assertThat(text).contains("/sub/currenttoken");
        assertThat(text)
                .as("ссылка замороженной подписки мертва — печатать её значит послать человека "
                        + "вставлять нерабочий конфиг")
                .doesNotContain("parkedtoken");
        assertThat(text).contains("остаток 40 дн., срок не расходуется");
    }

    @Test
    @DisplayName("Подтверждение называет все последствия переключения")
    void confirmNamesEveryConsequence() throws Exception {
        Subscription target = parked(2L, 2, 40);
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID)).thenReturn(Optional.of(target));
        when(subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID))
                .thenReturn(Optional.of(current(1L, 1)));
        when(deviceTrackingService.previewDemotion(target)).thenReturn(List.of(device("Пылесос")));

        handler.sendSwitchConfirm(CHAT_ID, TG_ID, "2");

        String text = captureLast().getText();
        assertThat(text).contains("срок подписки #2");               // чей срок пойдёт
        assertThat(text).contains("будет заморожена");                // чей замрёт
        assertThat(text).contains("Ссылка сменится");                // ссылку менять руками
        assertThat(text).contains("Пылесос");                        // какие устройства отключатся
        assertThat(text).contains("доступ может не работать до минуты");
    }

    @Test
    @DisplayName("Пустую подписку включить нельзя — кнопки «всё равно» нет")
    void emptyTargetIsRefused() throws Exception {
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID))
                .thenReturn(Optional.of(parked(2L, 2, 0)));

        handler.sendSwitchConfirm(CHAT_ID, TG_ID, "2");

        SendMessage sent = captureLast();
        assertThat(sent.getText()).contains("не осталось оплаченных дней");
        assertThat(sent.getText()).contains("отключит доступ");
        assertThat(markupData(sent))
                .as("единственный выход — назад; «всё равно включить» поймало бы тех, кто жмёт не глядя")
                .containsExactly("sub:list");
    }

    @Test
    @DisplayName("Чужая подписка в кнопке не переключает — молча уводит к списку")
    void foreignSubscriptionFallsBackToList() throws Exception {
        when(subscriptionRepository.findByIdAndTelegramId(777L, TG_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .thenReturn(List.of(current(1L, 1)));

        handler.handleSwitch(CHAT_ID, TG_ID, "777");

        verify(switchService, never()).switchByUser(anyLong(), anyLong());
        assertThat(captureLast().getText()).contains("Мои подписки");
    }

    @Test
    @DisplayName("Уже текущая подписка — не ошибка, а перерисовка списка")
    void alreadyCurrentIsNotAnError() throws Exception {
        Subscription target = parked(2L, 2, 40);
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID)).thenReturn(Optional.of(target));
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .thenReturn(List.of(current(1L, 1), target));
        when(switchService.switchByUser(TG_ID, 2L)).thenReturn(
                new SubscriptionSwitchService.Result(
                        SubscriptionSwitchService.Outcome.ALREADY_CURRENT, null, null, 0L));

        handler.handleSwitch(CHAT_ID, TG_ID, "2");

        assertThat(captureLast().getText()).contains("Мои подписки");
        verify(switchService, never()).pushToPanelAfterCommit(any());
    }

    /**
     * Сами гейты (аварийный тормоз и суточный лимит) живут в сервисе — иначе Mini App обходил
     * бы их одним своим появлением. Экрану остаётся честно назвать причину: у человека на
     * замороженной подписке лежат деньги, и «ничего не произошло» читается как их пропажа.
     */
    @Test
    @DisplayName("Аварийный тормоз: кнопка на месте, ответ честный")
    void switchingDisabledAnswersPlainly() throws Exception {
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID))
                .thenReturn(Optional.of(parked(2L, 2, 40)));
        when(switchService.switchByUser(TG_ID, 2L)).thenReturn(
                new SubscriptionSwitchService.Result(
                        SubscriptionSwitchService.Outcome.PAUSED, null, null, 0L));

        handler.handleSwitch(CHAT_ID, TG_ID, "2");

        String text = captureLast().getText();
        assertThat(text).contains("временно недоступно");
        assertThat(text).contains("Сроки подписок не изменяются");
        verify(switchService, never()).pushToPanelAfterCommit(any());
    }

    @Test
    @DisplayName("Исчерпанный суточный лимит объясняется, а не молчит")
    void rateLimitedIsExplained() throws Exception {
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID))
                .thenReturn(Optional.of(parked(2L, 2, 40)));
        when(switchService.switchByUser(TG_ID, 2L)).thenReturn(
                new SubscriptionSwitchService.Result(
                        SubscriptionSwitchService.Outcome.RATE_LIMITED, null, null, 0L));

        handler.handleSwitch(CHAT_ID, TG_ID, "2");

        assertThat(captureLast().getText()).contains("суточный лимит переключений");
        verify(switchService, never()).pushToPanelAfterCommit(any());
    }

    @Test
    @DisplayName("Успешное переключение отдаёт новую ссылку и предупреждает о замене")
    void successfulSwitchShowsNewLink() throws Exception {
        Subscription target = parked(2L, 2, 40);
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID)).thenReturn(Optional.of(target));
        when(subscriptionRepository.findById(2L)).thenReturn(Optional.of(target));
        when(switchService.switchByUser(TG_ID, 2L)).thenReturn(
                new SubscriptionSwitchService.Result(SubscriptionSwitchService.Outcome.SWITCHED,
                        1L, 2L, SubscriptionTime.daysToSeconds(40)));

        handler.handleSwitch(CHAT_ID, TG_ID, "2");

        String text = captureLast().getText();
        assertThat(text).contains("Подписка #2 включена");
        assertThat(text).contains("40 дн.");
        assertThat(text).contains("Ссылка изменилась");
        verify(switchService).pushToPanelAfterCommit(any());
    }

    /**
     * Кнопка «Включить #2» висит в истории чата вечно, а дни на подписке за это время могли
     * кончиться (подписка доработала своё, или по её платежу прошёл возврат). Отказ обязан
     * стоять на пути ДЕЙСТВИЯ, а не только на экране показа: иначе человек одним нажатием
     * замораживает работающую подписку и остаётся вообще без доступа.
     */
    @Test
    @DisplayName("Устаревшая кнопка на опустевшую подписку не переключает")
    void staleButtonOnEmptyTargetIsRefused() throws Exception {
        Subscription empty = parked(2L, 2, 0);
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID)).thenReturn(Optional.of(empty));
        when(switchService.switchByUser(TG_ID, 2L)).thenReturn(
                new SubscriptionSwitchService.Result(
                        SubscriptionSwitchService.Outcome.EMPTY, null, null, 0L));

        handler.handleSwitch(CHAT_ID, TG_ID, "2");

        assertThat(captureLast().getText()).contains("не осталось оплаченных дней");
        verify(switchService, never()).pushToPanelAfterCommit(any());
    }

    @Test
    @DisplayName("Истёкшая подписка не выдаётся за работающую и её ссылка не печатается")
    void expiredCurrentIsNotShownAsWorking() throws Exception {
        Subscription expired = Subscription.builder()
                .id(1L).telegramId(TG_ID).seq(1).current(true)
                .state(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(3))
                .subscriptionLink("https://example.com/sub/currenttoken")
                .build();
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .thenReturn(List.of(expired, parked(2L, 2, 40)));

        handler.sendSubscriptionList(CHAT_ID, TG_ID);

        String text = captureLast().getText();
        assertThat(text).doesNotContain("работает, до");
        assertThat(text).contains("не действует");
        assertThat(text)
                .as("мёртвую ссылку печатать нельзя — человек будет вставлять её, пока не поймёт, "
                        + "что дело в сроке")
                .doesNotContain("currenttoken");
    }

    /**
     * Второй по частоте вопрос к списку после «какая работает» — «за какую с меня спишут».
     * До пометки ответа на него не было ни на одном экране: в боте сумма и тариф лежали в
     * /autorenew, а какой подписке они принадлежат, там не говорилось вовсе.
     */
    @Test
    @DisplayName("Список помечает подписку, которую продлят автоматически")
    void listMarksAutorenewedSubscription() throws Exception {
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .thenReturn(List.of(current(1L, 1), parked(2L, 2, 40)));
        when(autoRenewInfo.forUser(any()))
                .thenReturn(renewInfo(true, LocalDateTime.of(2026, 9, 1, 12, 0), null));

        handler.sendSubscriptionList(CHAT_ID, TG_ID);

        String text = captureLast().getText();
        assertThat(text).contains("продлевается автоматически");
        assertThat(text).contains("1 сентября");
        // Пометка обязана стоять у РАБОТАЮЩЕЙ: замороженные не продлеваются никогда, и метка
        // на них означала бы обещание списания, которого не будет.
        int markPos = text.indexOf("продлевается автоматически");
        assertThat(text.indexOf("#2")).isGreaterThan(markPos);
    }

    /**
     * Ровно тот случай, когда человек платит второй раз за то, что у него уже оплачено:
     * действующая подписка отработала своё, а дни лежат на замороженной. Экран обязан назвать
     * бесплатный выход раньше, чем человек пойдёт в оплату.
     */
    @Test
    @DisplayName("Ничего не работает, а дни лежат на замороженной — предлагаем включить, а не купить")
    void deadCurrentWithParkedDaysOffersSwitch() throws Exception {
        Subscription expired = Subscription.builder()
                .id(1L).telegramId(TG_ID).seq(1).current(true)
                .state(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(2))
                .build();
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .thenReturn(List.of(expired, parked(2L, 2, 40)));

        handler.sendSubscriptionList(CHAT_ID, TG_ID);

        String text = captureLast().getText();
        assertThat(text).contains("сохранено 40 дн.");
        assertThat(text).contains("оплата не требуется");
    }

    /** Пустую замороженную предлагать нельзя: её включение — это «остаться без доступа». */
    @Test
    @DisplayName("Подсказки про сохранённые дни нет, когда сохранять нечего")
    void deadCurrentWithoutParkedDaysStaysSilent() throws Exception {
        Subscription expired = Subscription.builder()
                .id(1L).telegramId(TG_ID).seq(1).current(true)
                .state(Customer.Status.DISABLED)
                .expiresAt(LocalDateTime.now().minusDays(2))
                .build();
        when(subscriptionRepository.findByTelegramIdOrderBySeqAsc(TG_ID))
                .thenReturn(List.of(expired, parked(2L, 2, 0)));

        handler.sendSubscriptionList(CHAT_ID, TG_ID);

        assertThat(captureLast().getText()).doesNotContain("оплата не требуется");
    }

    /**
     * Автопродление всегда продлевает РАБОТАЮЩУЮ подписку, поэтому переключение уводит
     * списание на другую. Человек, не предупреждённый об этом, ждёт продления той, которую
     * только что заморозил, — и остаётся без неё.
     * <p>
     * Про смену числа устройств экран молчит намеренно: с 14.08.2026 списание берёт ступень
     * самой продлеваемой подписки, и переключение её не меняет.
     */
    @Test
    @DisplayName("Подтверждение говорит, что автопродление уедет на новую подписку")
    void confirmSaysAutorenewFollowsTheSwitch() throws Exception {
        Subscription target = parked(2L, 2, 40);
        when(subscriptionRepository.findByIdAndTelegramId(2L, TG_ID)).thenReturn(Optional.of(target));
        when(subscriptionRepository.findByTelegramIdAndCurrentTrue(TG_ID))
                .thenReturn(Optional.of(current(1L, 1)));
        when(autoRenewInfo.forUser(any()))
                .thenReturn(renewInfo(true, LocalDateTime.now().plusDays(10), "d3"));

        handler.sendSwitchConfirm(CHAT_ID, TG_ID, "2");

        String text = captureLast().getText();
        assertThat(text).contains("Автопродление перейдёт на подписку #2");
        assertThat(text)
                .as("число устройств продлеваемой подписки списание больше не меняет")
                .doesNotContain("лимит устройств");
    }

    // ===== helpers =====

    /** Снимок автопродления: включено/выключено, дата списания, ступень продлеваемой подписки. */
    private static com.orchestra.subscriptions.service.AutoRenewInfoService.Info renewInfo(
            boolean on, LocalDateTime nextCharge, String tierId) {
        return new com.orchestra.subscriptions.service.AutoRenewInfoService.Info(
                on, on, on, on ? "month" : null, on ? "Месяц" : null,
                tierId, tierId == null ? null : "3 устройства",
                on ? 299 : 0, nextCharge, 1, null, 2);
    }

    private static Subscription current(long id, int seq) {
        return Subscription.builder()
                .id(id).telegramId(TG_ID).seq(seq).current(true)
                .state(Customer.Status.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(12))
                .subscriptionLink("https://example.com/sub/currenttoken")
                .build();
    }

    private static Subscription parked(long id, int seq, int days) {
        return Subscription.builder()
                .id(id).telegramId(TG_ID).seq(seq).current(false)
                .state(Customer.Status.ACTIVE)
                .balanceSeconds(SubscriptionTime.daysToSeconds(days))
                .subscriptionLink("https://example.com/sub/parkedtoken")
                .build();
    }

    private static UserDevice device(String name) {
        return UserDevice.builder()
                .id(1L).telegramId(TG_ID).hwid("hwid-0000000001")
                .customName(name)
                .status(UserDevice.Status.ACTIVE)
                .lastSeenAt(LocalDateTime.now())
                .build();
    }

    private SendMessage captureLast() throws Exception {
        ArgumentCaptor<SendMessage> cap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramSender, atLeastOnce()).execute(cap.capture());
        return cap.getAllValues().get(cap.getAllValues().size() - 1);
    }

    private static List<String> markupData(SendMessage msg) {
        return ((org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup)
                msg.getReplyMarkup()).getKeyboard().stream()
                .flatMap(java.util.Collection::stream)
                .map(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton::getCallbackData)
                .toList();
    }
}
