package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.service.DeviceTrackingService;
import com.orchestra.subscriptions.service.MultiSubscriptionPolicy;
import com.orchestra.subscriptions.service.SubscriptionSwitchService;
import com.orchestra.subscriptions.service.SubscriptionTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Подписки человека в Mini App: что произойдёт при переключении и само переключение.
 * <p>
 * Отдельный контроллер, а не ещё двести строк в {@link WebAppController} — тот уже за
 * тысячу строк и обслуживает всё остальное Mini App разом. Тот же раздел, что и в боте:
 * экран подписок вынесен в свой обработчик.
 * <p>
 * <b>Обещание экрана то же, что и в боте: назвать ВСЕ последствия до нажатия.</b>
 * Переключение меняет четыре вещи разом — чьи дни идут, чьи замирают, какая ссылка рабочая
 * и какие устройства останутся в слотах. Поэтому «предпросмотр» здесь отдельный запрос, а
 * не то, что фронт додумывает из списка подписок.
 */
@Slf4j
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class WebAppSubscriptionController {

    /** Сколько устройств назвать поимённо, прежде чем свернуть в «и ещё N». */
    private static final int MAX_DEVICES_NAMED = 5;

    private final TelegramInitDataValidator validator;
    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository userRepository;
    private final SubscriptionSwitchService switchService;
    private final DeviceTrackingService deviceTrackingService;
    private final MeViewAssembler meViewAssembler;
    private final RequestRateLimiter rateLimiter;
    private final MultiSubscriptionPolicy policy;

    /**
     * Что изменится, если включить эту подписку.
     * <p>
     * Отдаёт и {@code currentSubscriptionId} — это же значение фронт возвращает в
     * {@code activate} как «состояние, которое я показал человеку». Без него защита от
     * протухшей вкладки была бы фикцией: фронту неоткуда взять точку сравнения.
     */
    @GetMapping("/{id}/switch-preview")
    public ResponseEntity<?> switchPreview(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @PathVariable Long id) {

        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        if (!rateLimiter.tryAcquire("subpreview:" + telegramId, 30, Duration.ofMinutes(1))) {
            return ResponseEntity.status(429).body(Map.of("error", "rate_limited"));
        }

        // Принадлежность проверяется ЗАПРОСОМ, а не сравнением после загрузки: id приезжает
        // из адреса и подделывается, а забытое сравнение превратило бы его в способ
        // подсмотреть чужую подписку.
        Subscription target = subscriptionRepository.findByIdAndTelegramId(id, telegramId).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        }
        Subscription current = subscriptionRepository
                .findByTelegramIdAndCurrentTrue(telegramId).orElse(null);

        LocalDateTime now = LocalDateTime.now();
        long balance = target.getBalanceSeconds() == null ? 0L : target.getBalanceSeconds();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetId", target.getId());
        body.put("targetSeq", target.getSeq());
        body.put("currentSubscriptionId", current == null ? null : current.getId());
        body.put("alreadyCurrent", target.isCurrent());
        // Пустая цель: включить её — значит отключить доступ. Фронт обязан показать отказ, а не
        // кнопку; сам отказ продублирован в switchTo, потому что экран показа его не ловит.
        body.put("emptyTarget", balance <= 0);
        body.put("switchingEnabled", policy.switchingEnabled());

        Map<String, Object> willActivate = new LinkedHashMap<>();
        willActivate.put("seq", target.getSeq());
        willActivate.put("daysLeft", SubscriptionTime.displayDays(balance));
        willActivate.put("untilEpoch", balance <= 0 ? 0
                : SubscriptionTime.expiryFrom(now, balance).toEpochSecond(ZoneOffset.UTC));
        body.put("willActivate", willActivate);

        if (current != null && !current.getId().equals(target.getId())) {
            long left = SubscriptionTime.remainingSeconds(current.getExpiresAt(), now);
            Map<String, Object> willFreeze = new LinkedHashMap<>();
            willFreeze.put("id", current.getId());
            willFreeze.put("seq", current.getSeq());
            willFreeze.put("daysLeft", SubscriptionTime.displayDays(left));
            body.put("willFreeze", willFreeze);
        } else {
            body.put("willFreeze", null);
        }

        // Ссылка почти всегда меняется (у каждой подписки свой аккаунт в панели), но
        // «почти» — не то слово, которым стоит обещать человеку. Считаем честно.
        body.put("linkChanges", !Objects.equals(
                current == null ? null : current.getSubscriptionLink(), target.getSubscriptionLink()));

        List<UserDevice> demoted = deviceTrackingService.previewDemotion(target);
        body.put("devicesDemoted", demoted.stream()
                .limit(MAX_DEVICES_NAMED)
                .map(DeviceTrackingService::displayName)
                .toList());
        body.put("devicesDemotedMore", Math.max(0, demoted.size() - MAX_DEVICES_NAMED));

        return ResponseEntity.ok(body);
    }

    /**
     * Тело {@code POST /{id}/activate}.
     *
     * @param expectedCurrentSubscriptionId какая подписка была текущей на экране, с которого
     *                                      человек нажал; {@code null} — «текущей не было»
     */
    public record ActivateRequest(Long expectedCurrentSubscriptionId) {}

    /**
     * Включает подписку {@code id}.
     * <p>
     * <b>Сверка с ожидаемым состоянием (CAS) обязательна.</b> Mini App живёт во вкладке,
     * которая может провисеть сутки: за это время человек мог переключиться из бота, а
     * оплата — активировать другую подписку. Без сверки нажатие на кнопке недельной давности
     * замораживало бы подписку, которую человек на экране даже не видел, — а это ровно тот
     * класс жалоб («сам ничего не делал, доступ отвалился»), который потом не разобрать.
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @PathVariable Long id,
            @RequestBody(required = false) ActivateRequest request) {

        long telegramId;
        try {
            telegramId = validator.validateAndExtractUserId(initData);
        } catch (TelegramInitDataValidator.InvalidInitDataException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid initData"));
        }
        if (request == null) {
            // Тело обязательно: без него нечего сверять, а «активировать не глядя» —
            // именно то, от чего защищает этот эндпоинт.
            return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        }
        // Второй пояс поверх суточного лимита: тот считается по журналу и допускает 10
        // переключений в сутки, но не мешает выстрелить ими за секунду и раскачать панель.
        if (!rateLimiter.tryAcquire("subswitch:" + telegramId, 10, Duration.ofMinutes(1))) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "rate_limited",
                    "message", "Слишком частые запросы. Повторите попытку через минуту. "
                            + "Сроки подписок не изменяются."));
        }

        Subscription target = subscriptionRepository.findByIdAndTelegramId(id, telegramId).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        }
        if (target.isCurrent()) {
            // Двойное нажатие или повтор после потери ответа — не ошибка. Отдаём состояние:
            // человек хотел именно эту подписку, и она уже работает.
            return ResponseEntity.ok(freshMe(telegramId));
        }

        Long serverCurrentId = subscriptionRepository.findByTelegramIdAndCurrentTrue(telegramId)
                .map(Subscription::getId).orElse(null);
        if (!Objects.equals(serverCurrentId, request.expectedCurrentSubscriptionId())) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "stale_state",
                    "message", "Состояние подписок изменилось. Обновите экран и повторите попытку."));
        }

        SubscriptionSwitchService.Result result;
        try {
            // Ловим СНАРУЖИ транзакции переключения: нарушение уникального индекса «одна
            // текущая подписка» отравляет транзакцию Postgres, обработать его внутри нельзя.
            result = switchService.switchByUser(telegramId, target.getId());
        } catch (Exception e) {
            log.error("Mini App: переключение не удалось telegramId={} subId={}", telegramId, id, e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "switch_failed",
                    "message", "Переключение не выполнено. Состояние подписок не изменилось. "
                            + "Повторите попытку."));
        }

        switch (result.outcome()) {
            case NOT_FOUND -> {
                return ResponseEntity.status(404).body(Map.of("error", "not_found"));
            }
            case ALREADY_CURRENT -> {
                return ResponseEntity.ok(freshMe(telegramId));
            }
            case EMPTY -> {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "empty_target",
                        "message", "На этой подписке не осталось оплаченных дней. Её включение "
                                + "отключит доступ. Пополните подписку — после этого её можно включить."));
            }
            case PAUSED -> {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "paused",
                        "message", "Переключение подписок временно недоступно. "
                                + "Сроки подписок не изменяются. Повторите попытку позже."));
            }
            case RATE_LIMITED -> {
                return ResponseEntity.status(429).body(Map.of(
                        "error", "daily_limit",
                        "message", "Исчерпан суточный лимит переключений. Повторите попытку завтра. "
                                + "Сроки подписок не изменяются."));
            }
            case SWITCHED -> { /* дальше — панель и свежее состояние */ }
        }

        try {
            // Панель обновляем ПОСЛЕ фиксации в базе: недоступная панель не отменяет уже
            // принятое решение, её догонит реконсилер.
            switchService.pushToPanelAfterCommit(result);
        } catch (Exception e) {
            log.warn("Mini App: не смог сразу досдать переключение в панель (telegramId={}) — "
                    + "доведёт реконсилер", telegramId, e);
        }

        return ResponseEntity.ok(freshMe(telegramId));
    }

    /**
     * Свежее состояние тем же payload'ом, что отдаёт {@code /api/me}. Именно тем же, а не
     * «похожим»: переключение меняет срок, ссылку и лимит устройств разом, и фронт обязан
     * перерисоваться из одного согласованного снимка, иначе на экране окажется новая
     * подписка со старой ссылкой.
     */
    private Map<String, Object> freshMe(long telegramId) {
        Customer user = userRepository.findByTelegramId(telegramId).orElse(null);
        return meViewAssembler.assemble(telegramId, user);
    }
}
