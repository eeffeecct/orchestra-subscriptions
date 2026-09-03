package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.util.DeviceNames;
import com.orchestra.subscriptions.web.api.RequestRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Учёт устройств по HWID-заголовкам клиента на подписочном эндпоинте {@code /sub/<token>}
 * + enforcement лимита устройств тарифа ({@code customers.device_limit}).
 * <p>
 * клиентское приложение (и v2RayTun, Shadowrocket с включённой опцией и др.) шлёт при каждом обновлении
 * подписки заголовки {@code x-hwid} / {@code x-device-model} / {@code x-device-os} /
 * {@code x-ver-os} — тот же контракт, что у Remnawave HWID device limit. Клиенты без HWID
 * (v2rayN, Hiddify, Streisand) проходят без учёта: это кооперативный контроль, не
 * security-граница; на уровне соединений его страхует per-tier AccessProvider {@code ip_limit}.
 * <p>
 * Правила:
 * <ul>
 *   <li>Слот занимают только ACTIVE-устройства, обновлявшие подписку за последние
 *       {@code app.device-inactivity-days} дней — проданный/сброшенный телефон
 *       освобождает слот сам.</li>
 *   <li>Новое устройство при свободном слоте → ACTIVE + DM «подключено новое устройство».</li>
 *   <li>Сверх лимита → BLOCKED + DM «лимит исчерпан» (одноразово, при регистрации);
 *       авто-активация при следующем фетче, когда слот освободится.</li>
 *   <li>REVOKED (юзер нажал «Отключить») слот не занимает и само не возвращается.</li>
 *   <li>Любая внутренняя ошибка → {@link Verdict#ALLOW} (fail-open): выдача подписки
 *       важнее учёта устройств.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTrackingService {

    /** Валидация x-hwid как у Remnawave: 10-64 символа, латиница/цифры/{@code =}/{@code -}. */
    private static final Pattern HWID_PATTERN = Pattern.compile("^[A-Za-z0-9=-]{10,64}$");

    /** Шаг обновления {@code last_seen_at}: клиенты поллят подписку часто, БД на каждый полл не пишем. */
    private static final Duration LAST_SEEN_STEP = Duration.ofHours(1);

    /**
     * Потолок строк устройств на юзера. Держатель токена (или тот, кому расшарили семейную
     * ссылку) может слать рандомные x-hwid — без потолка таблица растёт бесконечно, а экран
     * «Мои устройства» упирается в лимит длины Telegram-сообщения. При достижении — вытесняем
     * самую старую не-ACTIVE строку (REVOKED/BLOCKED телефон, который давно не появлялся),
     * а если вытеснять нечего — новые HWID просто не регистрируем.
     */
    private static final int MAX_TRACKED_DEVICES = 20;

    /** Лимит DM про устройства (подключено/лимит) на юзера в час — анти-спам при переборе HWID. */
    private static final int MAX_DEVICE_DMS_PER_HOUR = 5;

    /** Чтение подписки — через общий фасад: источник переключается флагом. */
    private final SubscriptionLookupService lookup;
    private final UserDeviceRepository deviceRepository;
    private final AppConfig appConfig;
    private final DeviceNotificationService deviceNotificationService;
    private final RequestRateLimiter rateLimiter;

    /** Заголовки устройства из запроса подписки. Все поля могут быть null. */
    public record DeviceHeaders(String hwid, String model, String os, String osVersion, String userAgent) {
        public boolean hasValidHwid() {
            return hwid != null && HWID_PATTERN.matcher(hwid.trim()).matches();
        }
    }

    public enum Verdict {
        /** Отдаём подписку как обычно. */
        ALLOW,
        /** Устройство сверх лимита/отключено — отдать {@code app.device-limit-lines} вместо нод. */
        BLOCK
    }

    /**
     * Главная точка входа с {@code /sub/<token>}: регистрирует/обновляет устройство и
     * решает, отдавать ли ему ноды. Никогда не бросает — fail-open (см. javadoc класса).
     * Вызывающая сторона дополнительно оборачивает в try/catch: constraint-violation при
     * конкурентной регистрации может вылететь на коммите транзакции, после return.
     */
    @Transactional
    public Verdict onSubscriptionFetch(String token, DeviceHeaders headers) {
        try {
            return track(token, headers);
        } catch (Exception e) {
            log.warn("Device tracking failed for token — allowing subscription", e);
            return Verdict.ALLOW;
        }
    }

    private Verdict track(String token, DeviceHeaders h) {
        if (h == null || !h.hasValidHwid()) {
            // Клиент без HWID (или мусор в заголовке) — passthrough без учёта.
            return Verdict.ALLOW;
        }
        SubscriptionView user = lookup.byToken(token).orElse(null);
        if (user == null) {
            // Токен не наш/до backfill'а — не мешаем выдаче.
            return Verdict.ALLOW;
        }

        String hwid = h.hwid().trim();
        LocalDateTime now = LocalDateTime.now();
        int limit = effectiveDeviceLimit(user);
        UserDevice existing = findDevice(user, hwid);

        if (existing != null) {
            return switch (existing.getStatus()) {
                case ACTIVE -> {
                    // Известное ACTIVE-устройство тоже сверяем с лимитом: он мог УПАСТЬ уже
                    // после его регистрации (смена ступени на меньшую, откат ступени после
                    // возврата), либо слот успело занять вернувшееся из простоя устройство.
                    // Без этой ветки оплаченная ступень ограничивала бы только те устройства,
                    // которые юзер ещё не подключал, а обещание «лишние будут отключены»
                    // (ScreenHandler, Mini App) не выполнялось бы никогда.
                    if (limit > 0 && !keepsSlot(user, existing, limit, now)) {
                        existing.setStatus(UserDevice.Status.BLOCKED);
                        touch(existing, h, now, true);
                        log.info("Device lost its slot for telegramId={} (limit={})",
                                user.telegramId(), limit);
                        yield Verdict.BLOCK;
                    }
                    touch(existing, h, now, false);
                    yield Verdict.ALLOW;
                }
                case REVOKED -> {
                    touch(existing, h, now, false);
                    yield Verdict.BLOCK;
                }
                case BLOCKED -> {
                    if (limit <= 0 || occupiedSlots(user, now) < limit) {
                        existing.setStatus(UserDevice.Status.ACTIVE);
                        touch(existing, h, now, true);
                        notifyConnected(user, existing, limit, now);
                        yield Verdict.ALLOW;
                    }
                    touch(existing, h, now, false);
                    yield Verdict.BLOCK;
                }
            };
        }

        // Потолок хранимых строк: вытесняем старую не-ACTIVE, иначе не регистрируем вовсе
        // (без DM — иначе перебор HWID по чужому токену превращается в спам-канал).
        // Счётчик берём ДО вставки: он же питает owner-алерт об аномалии ниже.
        long totalBefore = user.subscriptionId() != null
                ? deviceRepository.countBySubscriptionId(user.subscriptionId())
                : deviceRepository.countByTelegramId(user.telegramId());
        if (totalBefore >= MAX_TRACKED_DEVICES && !evictOldestInactive(user.telegramId())) {
            log.warn("Device cap reached for telegramId={} and nothing evictable — skipping registration",
                    user.telegramId());
            return limit > 0 ? Verdict.BLOCK : Verdict.ALLOW;
        }

        long active = occupiedSlots(user, now);
        boolean overLimit = limit > 0 && active >= limit;
        UserDevice device = UserDevice.builder()
                .telegramId(user.telegramId())
                .subscriptionId(user.subscriptionId())
                .hwid(hwid)
                .deviceModel(trimTo(h.model(), 128))
                .deviceOs(trimTo(h.os(), 64))
                .osVersion(trimTo(h.osVersion(), 64))
                .userAgent(trimTo(h.userAgent(), 255))
                .status(overLimit ? UserDevice.Status.BLOCKED : UserDevice.Status.ACTIVE)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
        deviceRepository.save(device);

        String label = DeviceNames.label(device.getDeviceModel(), device.getDeviceOs(), device.getOsVersion());
        maybeAlertOwnerAnomaly(user, totalBefore + 1);
        if (overLimit) {
            log.info("Device over limit for telegramId={}: {} (active={}, limit={})",
                    user.telegramId(), label, active, limit);
            if (allowDeviceDm(user.telegramId())) {
                deviceNotificationService.sendDeviceLimitReached(user.telegramId(), label, limit);
            }
            return Verdict.BLOCK;
        }
        log.info("New device registered for telegramId={}: {} ({} of {})",
                user.telegramId(), label, active + 1, limit > 0 ? limit : "∞");
        if (allowDeviceDm(user.telegramId())) {
            deviceNotificationService.sendDeviceConnected(user.telegramId(), label, active + 1, limit);
        }
        return Verdict.ALLOW;
    }

    /**
     * Освобождает строку под новое устройство: удаляет самую давно не появлявшуюся
     * не-ACTIVE запись (BLOCKED раньше REVOKED — отключение юзером ценнее). ACTIVE не трогаем.
     */
    private boolean evictOldestInactive(Long telegramId) {
        return deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(telegramId).stream()
                .filter(d -> d.getStatus() != UserDevice.Status.ACTIVE)
                .min(Comparator
                        .comparing((UserDevice d) -> d.getStatus() == UserDevice.Status.REVOKED ? 1 : 0)
                        .thenComparing(UserDevice::getLastSeenAt))
                .map(victim -> {
                    log.info("Evicting stale device row id={} status={} for telegramId={}",
                            victim.getId(), victim.getStatus(), telegramId);
                    deviceRepository.delete(victim);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Owner-алерт «перепродажа/утечка ссылки»: после регистрации нового устройства общее число
     * строк достигло {@code app.device-anomaly-threshold}. Не чаще 1/час на юзера (ключ лимитера
     * отдельный от юзерских DM). Считаем ВСЕ строки, не только ACTIVE — перебор HWID сверх
     * лимита копит BLOCKED, и это ровно тот сигнал, который владельцу интересен.
     */
    private void maybeAlertOwnerAnomaly(SubscriptionView user, long total) {
        int threshold = appConfig.getDeviceAnomalyThreshold();
        if (threshold <= 0 || total < threshold) {
            return;
        }
        if (!rateLimiter.tryAcquire("device-anomaly:" + user.telegramId(), 1, Duration.ofHours(1))) {
            return;
        }
        log.warn("Device anomaly for telegramId={}: {} HWIDs (threshold {})",
                user.telegramId(), total, threshold);
        deviceNotificationService.notifyOwnerDeviceAnomaly(
                user.telegramId(), user.telegramUsername(), total, user.deviceLimit());
    }

    /** ≤{@value #MAX_DEVICE_DMS_PER_HOUR} DM про устройства в час на юзера. */
    private boolean allowDeviceDm(Long telegramId) {
        boolean allowed = rateLimiter.tryAcquire(
                "device-dm:" + telegramId, MAX_DEVICE_DMS_PER_HOUR, Duration.ofHours(1));
        if (!allowed) {
            log.debug("Device DM rate limit hit for telegramId={}", telegramId);
        }
        return allowed;
    }

    // ========================
    // Докупка «+1 устройство» (app.device-addon)
    // ========================

    /** Котировка докупки слота: цена pro-rata до конца подписки + Stars-эквивалент. */
    public record AddonQuote(long priceKopeks, long priceStars, long remainingDays, int currentLimit) {}

    /**
     * Доступна ли юзеру докупка слота и почём. {@code empty}, если: фича выключена;
     * у юзера нет выбранной ступени (legacy/триал — сначала тариф); ступень удалена из
     * конфига; докуплен максимум ({@code max-extra-devices} сверх ступени); подписка
     * неактивна/истекла (продавать слот к мёртвой подписке нельзя — цена pro-rata).
     */
    public Optional<AddonQuote> addonQuote(Customer user) {
        AppConfig.DeviceAddonConfig cfg = appConfig.getDeviceAddon();
        if (cfg == null || !cfg.enabled() || user == null) {
            return Optional.empty();
        }
        if (user.getDeviceTierId() == null || user.getDeviceLimit() == null) {
            return Optional.empty();
        }
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(user.getDeviceTierId()).orElse(null);
        if (tier == null) {
            return Optional.empty();
        }
        int extraBought = user.getDeviceLimit() - tier.getDevices();
        if (extraBought >= cfg.getMaxExtraDevices()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getStatus() != Customer.Status.ACTIVE
                || user.getExpiresAt() == null || !user.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        long remainingDays = Math.max(1, (Duration.between(now, user.getExpiresAt()).toHours() + 23) / 24);
        long priceKopeks = cfg.priceKopeks(remainingDays);
        // price-stars-per-month: 0 = Stars для аддона отключены (кнопка скрывается).
        long priceStars = cfg.getPriceStarsPerMonth() > 0
                ? com.orchestra.subscriptions.util.StarsAmount.compute(
                        cfg.getPriceStarsPerMonth(), cfg.getPriceKopeksPerMonth(), priceKopeks)
                : 0;
        return Optional.of(new AddonQuote(
                priceKopeks, priceStars, remainingDays, user.getDeviceLimit()));
    }

    // ========================
    // Экраны «Мои устройства» (бот + Mini App)
    // ========================

    public List<UserDevice> listDevices(Long telegramId) {
        return deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(telegramId);
    }

    /**
     * Устройства КОНКРЕТНОЙ подписки. Версия по человеку остаётся для админ-карточки: там
     * нужен весь парк человека целиком, а не срез одной подписки.
     */
    public List<UserDevice> listDevices(SubscriptionView sub) {
        if (sub.subscriptionId() == null) {
            return listDevices(sub.telegramId());
        }
        return deviceRepository.findAllBySubscriptionIdOrderByFirstSeenAtAsc(sub.subscriptionId());
    }

    /** Занятые слоты: ACTIVE-устройства в окне активности. */
    public long usedSlots(Long telegramId) {
        return activeDeviceCount(telegramId, LocalDateTime.now());
    }

    /** Занятые слоты ЭТОЙ подписки — то, что видит юзер на экране своей подписки. */
    public long usedSlots(SubscriptionView sub) {
        return occupiedSlots(sub, LocalDateTime.now());
    }

    /**
     * Какие устройства потеряют слот, если включить эту подписку. Нужен экрану подтверждения
     * переключения: «какие устройства отключатся» — обещание, которое обязано называть их
     * поимённо, иначе человек узнаёт об отключении постфактум и считает это поломкой.
     * <p>
     * Считается по устройствам САМОЙ подписки: после включения лимит будет её, и лишними
     * окажутся её же самые давно молчащие устройства.
     */
    public List<UserDevice> previewDemotion(Subscription target) {
        if (target == null) {
            return List.of();
        }
        SubscriptionView view = SubscriptionView.of(target, null);
        int limit = effectiveDeviceLimit(view);
        if (limit <= 0) {
            return List.of();
        }
        List<UserDevice> occupying = occupyingDevices(view, LocalDateTime.now());
        return occupying.size() <= limit
                ? List.of()
                : List.copyOf(occupying.subList(limit, occupying.size()));
    }

    /** Лимит юзера: свой {@code device_limit}, иначе {@code app.default-device-limit}. 0 = без лимита. */
    public int effectiveDeviceLimit(SubscriptionView sub) {
        return sub.deviceLimit() != null ? sub.deviceLimit() : appConfig.getDefaultDeviceLimit();
    }

    /**
     * Приводит УЖЕ зарегистрированные устройства к {@code newLimit}: лишние ACTIVE уходят
     * в BLOCKED. Вызывается там, где лимит понижается — покупка меньшей ступени, откат
     * ступени после возврата тарифа, возврат докупленного слота.
     * <p>
     * Почему отдельным вызовом, а не только в {@link #track}: смена ступени должна
     * применяться СРАЗУ (именно это обещают экран покупки и Mini App), не дожидаясь, пока
     * каждое устройство сходит за подпиской — иначе юзер видит «Занято 5 из 1» и продолжает
     * пользоваться всеми пятью.
     * <p>
     * Кого оставляем: {@code newLimit} самых свежих по {@code last_seen_at} — то, чем юзер
     * реально пользуется; молчащие устройства теряют слот первыми. Обратная операция не
     * нужна: как только слот освободится, BLOCKED-устройство вернёт себе ACTIVE само при
     * следующем фетче (ветка BLOCKED в {@link #track}).
     * <p>
     * Никогда не бросает — как и весь учёт устройств, это не должно ломать оплату.
     * <p>
     * {@code REQUIRES_NEW} — намеренно: вызывается ИЗНУТРИ платёжной транзакции, и любая
     * проблема здесь (битая строка устройства, таймаут) не должна утащить за собой оплату —
     * это ровно тот класс аварии «деньги списаны, услуги нет», который мы чиним отдельно.
     * Своя транзакция + внутренний catch дают полную изоляцию. Обратная сторона (откат
     * оплаты уже ПОСЛЕ понижения) самолечится: лимит в БД останется прежним, и
     * заблокированные устройства вернут себе ACTIVE при следующем фетче (ветка BLOCKED).
     *
     * @param newLimit новый лимит; {@code <= 0} (legacy/без лимита) — no-op
     * @return сколько устройств потеряли слот
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int enforceLimit(Long telegramId, int newLimit) {
        if (telegramId == null || newLimit <= 0) {
            return 0;
        }
        return applyLimit(telegramId, newLimit, () -> occupyingDevices(telegramId, LocalDateTime.now()));
    }

    /**
     * То же, но в пределах ОДНОЙ подписки. Нужен там, где лимит понижается не у работающей
     * подписки: откат ступени после возврата за замороженную подписку не имеет права снимать
     * со слотов устройства той, которой человек пользуется прямо сейчас.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int enforceLimit(SubscriptionView sub, int newLimit) {
        if (sub == null || sub.telegramId() == null || newLimit <= 0) {
            return 0;
        }
        return applyLimit(sub.telegramId(), newLimit,
                () -> occupyingDevices(sub, LocalDateTime.now()));
    }

    /**
     * Общее тело: лишние устройства (самые давно молчащие) уходят в BLOCKED.
     * <p>
     * Никогда не бросает — см. джавадок {@link #enforceLimit(Long, int)}.
     */
    private int applyLimit(Long telegramId, int newLimit,
                           java.util.function.Supplier<List<UserDevice>> occupyingSupplier) {
        try {
            List<UserDevice> occupying = occupyingSupplier.get();
            if (occupying.size() <= newLimit) {
                return 0;
            }
            List<UserDevice> surplus = occupying.subList(newLimit, occupying.size());
            for (UserDevice device : surplus) {
                device.setStatus(UserDevice.Status.BLOCKED);
                deviceRepository.save(device);
            }
            log.info("enforceLimit: {} device(s) lost their slot for telegramId={} (new limit {})",
                    surplus.size(), telegramId, newLimit);
            deviceNotificationService.sendDevicesDemoted(telegramId, surplus.size(), newLimit);
            return surplus.size();
        } catch (Exception e) {
            log.warn("enforceLimit failed for telegramId={} — devices left as is", telegramId, e);
            return 0;
        }
    }

    /**
     * «Отключить»: устройство перестаёт получать ноды и не занимает слот. Проверяет
     * принадлежность устройства юзеру. {@code false} — не найдено/чужое.
     */
    @Transactional
    public boolean revoke(Long telegramId, Long deviceId) {
        return updateOwnDevice(telegramId, deviceId, d -> d.setStatus(UserDevice.Status.REVOKED));
    }

    /**
     * Переименовать устройство («телефон мамы»). Пустое/blank имя очищает — обратно к железному
     * имени. Обрезка до 64 code points; хранится как есть, экранирование — на рендере.
     * {@code false} — устройство не найдено/чужое.
     */
    @Transactional
    public boolean rename(Long telegramId, Long deviceId, String rawName) {
        String name = rawName == null ? null : rawName.trim();
        String normalized = (name == null || name.isEmpty()) ? null : truncateCodePoints(name, 64);
        return updateOwnDevice(telegramId, deviceId, d -> d.setCustomName(normalized));
    }

    /** Отображаемое имя устройства: пользовательское, иначе железное из заголовков. */
    public static String displayName(UserDevice d) {
        if (d.getCustomName() != null && !d.getCustomName().isBlank()) {
            return d.getCustomName();
        }
        return DeviceNames.label(d.getDeviceModel(), d.getDeviceOs(), d.getOsVersion());
    }

    private static String truncateCodePoints(String s, int maxCodePoints) {
        if (s.codePointCount(0, s.length()) <= maxCodePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxCodePoints));
    }

    /**
     * «Вернуть» после revoke: ACTIVE, если есть свободный слот, иначе BLOCKED (авто-активируется,
     * когда слот освободится). {@code true} — устройство снова ACTIVE.
     */
    @Transactional
    public boolean restore(Long telegramId, Long deviceId) {
        SubscriptionView sub = lookup.currentFor(telegramId).orElse(null);
        if (sub == null) return false;
        int limit = effectiveDeviceLimit(sub);
        boolean[] activated = {false};
        boolean found = updateOwnDevice(telegramId, deviceId, d -> {
            boolean slotFree = limit <= 0
                    || activeDeviceCount(telegramId, LocalDateTime.now()) < limit;
            d.setStatus(slotFree ? UserDevice.Status.ACTIVE : UserDevice.Status.BLOCKED);
            activated[0] = slotFree;
        });
        return found && activated[0];
    }

    private boolean updateOwnDevice(Long telegramId, Long deviceId,
                                    java.util.function.Consumer<UserDevice> mutation) {
        Optional<UserDevice> opt = deviceRepository.findById(deviceId)
                .filter(d -> d.getTelegramId().equals(telegramId));
        if (opt.isEmpty()) {
            return false;
        }
        UserDevice device = opt.get();
        mutation.accept(device);
        deviceRepository.save(device);
        return true;
    }

    // ========================
    // Внутренности
    // ========================

    /**
     * Устройство ищем в пределах ПОДПИСКИ: один и тот же телефон может быть подключён к
     * двум разным подпискам одного человека, и это два разных слота с разными лимитами.
     * Подписка неизвестна (строки старше привязки) — падаем на поиск по человеку.
     */
    private UserDevice findDevice(SubscriptionView sub, String hwid) {
        if (sub.subscriptionId() != null) {
            UserDevice found = deviceRepository
                    .findBySubscriptionIdAndHwid(sub.subscriptionId(), hwid).orElse(null);
            if (found != null) {
                return found;
            }
        }
        UserDevice legacy = deviceRepository.findByTelegramIdAndHwid(sub.telegramId(), hwid).orElse(null);
        // Самолечение привязки. Строка без subscription_id (её завели до появления зеркала
        // либо в момент, когда фасад не нашёл подписку) НЕВИДИМА для подсчёта слотов подписки:
        // occupiedSlots считает по subscription_id и такую строку не увидит — человек получил
        // бы больше устройств, чем оплатил, а на экране бота при этом «занято 5 из 2».
        // Разовый бэкфилл эту дыру не закрывает: он отрабатывает только у людей, у которых
        // зеркальной строки ещё не было. Проставляем при первом же появлении устройства.
        if (legacy != null && legacy.getSubscriptionId() == null && sub.subscriptionId() != null) {
            legacy.setSubscriptionId(sub.subscriptionId());
            deviceRepository.save(legacy);
            log.debug("Устройство {} привязано к подписке id={}", legacy.getId(), sub.subscriptionId());
        }
        return legacy;
    }

    /**
     * Сколько слотов ЭТОЙ подписки занято. Считать все устройства человека было верно, пока
     * подписка была одна; при нескольких это показывало «занято 3 из 1» и отключало не то —
     * устройства припаркованной подписки съедали слоты активной.
     */
    private long occupiedSlots(SubscriptionView sub, LocalDateTime now) {
        if (sub.subscriptionId() == null) {
            return activeDeviceCount(sub.telegramId(), now);
        }
        return deviceRepository.countBySubscriptionIdAndStatusAndLastSeenAtAfter(
                sub.subscriptionId(), UserDevice.Status.ACTIVE, inactivityCutoff(now));
    }

    /** Устройства ЭТОЙ подписки, занимающие слот, свежие первыми. */
    private List<UserDevice> occupyingDevices(SubscriptionView sub, LocalDateTime now) {
        LocalDateTime cutoff = inactivityCutoff(now);
        List<UserDevice> rows = sub.subscriptionId() != null
                ? deviceRepository.findAllBySubscriptionIdOrderByFirstSeenAtAsc(sub.subscriptionId())
                : deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(sub.telegramId());
        return rows.stream()
                .filter(d -> d.getStatus() == UserDevice.Status.ACTIVE)
                .filter(d -> d.getLastSeenAt() != null && d.getLastSeenAt().isAfter(cutoff))
                .sorted(Comparator.comparing(UserDevice::getLastSeenAt).reversed())
                .toList();
    }

    /**
     * Держит ли устройство слот ЭТОЙ подписки при лимите {@code limit}.
     * <p>
     * Развилка по тому, ЧИСЛИТСЯ ли само устройство среди занятых слотов (т.е. попадает ли
     * его {@code last_seen_at} в окно активности):
     * <ul>
     *   <li><b>Числится</b> — слот за ним. Отбираем, только если слотов занято БОЛЬШЕ лимита
     *       (даунгрейд ступени): тогда ранжируем и оставляем {@code limit} самых свежих.</li>
     *   <li><b>Не числится</b> (вернулось из простоя — его слот успел освободиться сам) — ему
     *       нужен СВОБОДНЫЙ слот, ровно как новому устройству: {@code занято < лимита}.
     *       Иначе полежавший в ящике планшет, включённый на минуту, вытеснял бы ежедневный
     *       телефон: при {@code занято == лимиту} он проходил бы «в притык», {@link #touch}
     *       делал бы его самым свежим, и на следующем полле из ранжирования вылетал бы
     *       рабочий телефон — на 30 дней, пока слот планшета не протухнет.</li>
     * </ul>
     * Ранжируем по СОХРАНЁННОМУ {@code last_seen_at} (до {@link #touch}) — иначе
     * опрашивающее подписку устройство всегда оказывалось бы «самым свежим» и вытесняло
     * бы соседей по кругу. Сначала дешёвый COUNT: в норме строки устройств не читаются,
     * поллинг подписки не дорожает.
     */
    private boolean keepsSlot(SubscriptionView sub, UserDevice device, int limit, LocalDateTime now) {
        long occupied = occupiedSlots(sub, now);
        boolean holdsSlotAlready = device.getLastSeenAt() != null
                && device.getLastSeenAt().isAfter(inactivityCutoff(now));
        if (!holdsSlotAlready) {
            return occupied < limit;
        }
        if (occupied <= limit) {
            return true;
        }
        return occupyingDevices(sub, now).stream()
                .limit(limit)
                .anyMatch(d -> d.getId().equals(device.getId()));
    }

    private long activeDeviceCount(Long telegramId, LocalDateTime now) {
        return deviceRepository.countByTelegramIdAndStatusAndLastSeenAtAfter(
                telegramId, UserDevice.Status.ACTIVE, inactivityCutoff(now));
    }

    private LocalDateTime inactivityCutoff(LocalDateTime now) {
        return now.minusDays(Math.max(1, appConfig.getDeviceInactivityDays()));
    }

    /**
     * ACTIVE-устройства, реально занимающие слот (обновлявшие подписку в окне активности),
     * СВЕЖИЕ ПЕРВЫМИ. Порядок задаёт приоритет при нехватке слотов: хвост списка — самые
     * давно молчащие, они и теряют слот.
     */
    private List<UserDevice> occupyingDevices(Long telegramId, LocalDateTime now) {
        LocalDateTime cutoff = inactivityCutoff(now);
        return deviceRepository.findAllByTelegramIdOrderByFirstSeenAtAsc(telegramId).stream()
                .filter(d -> d.getStatus() == UserDevice.Status.ACTIVE)
                .filter(d -> d.getLastSeenAt() != null && d.getLastSeenAt().isAfter(cutoff))
                .sorted(Comparator.comparing(UserDevice::getLastSeenAt).reversed())
                .toList();
    }

    /**
     * Обновляет {@code last_seen_at} (+ метаданные модели/ОС, вдруг сменились с апдейтом ОС)
     * с шагом {@link #LAST_SEEN_STEP}, чтобы не писать БД на каждый полл подписки.
     * {@code force} — писать немедленно (смена статуса и так пишет строку).
     */
    private void touch(UserDevice device, DeviceHeaders h, LocalDateTime now, boolean force) {
        if (!force && device.getLastSeenAt() != null
                && Duration.between(device.getLastSeenAt(), now).compareTo(LAST_SEEN_STEP) < 0) {
            return;
        }
        device.setLastSeenAt(now);
        if (h.model() != null && !h.model().isBlank()) device.setDeviceModel(trimTo(h.model(), 128));
        if (h.os() != null && !h.os().isBlank()) device.setDeviceOs(trimTo(h.os(), 64));
        if (h.osVersion() != null && !h.osVersion().isBlank()) device.setOsVersion(trimTo(h.osVersion(), 64));
        if (h.userAgent() != null && !h.userAgent().isBlank()) device.setUserAgent(trimTo(h.userAgent(), 255));
        deviceRepository.save(device);
    }

    private void notifyConnected(SubscriptionView user, UserDevice device, int limit, LocalDateTime now) {
        if (!allowDeviceDm(user.telegramId())) {
            return;
        }
        String label = DeviceNames.label(device.getDeviceModel(), device.getDeviceOs(), device.getOsVersion());
        deviceNotificationService.sendDeviceConnected(
                user.telegramId(), label, occupiedSlots(user, now), limit);
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
