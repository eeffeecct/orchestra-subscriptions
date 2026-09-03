package com.orchestra.subscriptions.access;

import com.orchestra.subscriptions.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Локальная реализация {@link AccessProvider} — единственная в этом репозитории.
 *
 * <p>Выдаёт аккаунту случайный непрозрачный токен и собирает из него ссылку
 * {@code <app.provider.subscription-url-prefix>/sub/<token>}, по которой клиент забирает свои
 * параметры доступа у {@code AccessDeliveryController}. Ничего наружу не вызывает: срок,
 * статус и лимит устройств хранятся в памяти процесса.
 *
 * <p><b>Чем это отличается от боевой реализации.</b> В продуктовой системе на этом месте
 * стоит HTTP-клиент управляющей панели: те же семь методов, но каждый — вызов её API с
 * повторной авторизацией по протухшему токену и разбором конфликтов (аккаунт уже создан
 * предыдущей попыткой, транзакция базы при этом откатилась). Весь этот код живёт за
 * интерфейсом и не виден ни подпискам, ни платежам — поэтому замена реализации не трогает
 * ни одного другого класса.
 *
 * <p><b>Границы заглушки.</b> Состояние живёт в памяти и обнуляется при рестарте.
 * Источником правды остаётся база: {@code subscriptions.provider_username} и
 * {@code subscription_link} переживают перезапуск, а {@link #getUser} на неизвестном имени
 * честно отвечает пустотой — ровно тот же ответ, что даёт недоступная панель, и вызывающий
 * код обязан его переживать (см. контракт метода в интерфейсе).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalAccessProvider implements AccessProvider {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 24 байта → 32 символа base64url: непрозрачно и не перебирается. */
    private static final int TOKEN_BYTES = 24;

    private final AppConfig appConfig;
    /** Сужает подбор существующего имени: чужой аккаунт занимать нельзя (см. {@link AccountOwnership}). */
    private final AccountOwnership accountOwnership;

    private final Map<String, AccessAccount> accounts = new ConcurrentHashMap<>();

    @Override
    public AccessAccount createUser(String username, int expireDays, long dataLimitGb) {
        return createUser(username, expireDays, dataLimitGb, null);
    }

    @Override
    public AccessAccount createUser(String username, int expireDays, long dataLimitGb,
                                    Integer deviceLimitOverride) {
        // Аккаунт с нулевым/отрицательным сроком живёт час: это черновик, который вот-вот
        // получит настоящий срок от активации оплаты, и вечным он остаться не должен.
        Instant expireAt = expireDays > 0
                ? Instant.now().plus(expireDays, ChronoUnit.DAYS)
                : Instant.now().plus(1, ChronoUnit.HOURS);

        AccessAccount account = new AccessAccount();
        account.setUsername(username);
        account.setStatus("active");
        account.setExpire(expireAt.getEpochSecond());
        account.setDataLimit(dataLimitGb > 0 ? dataLimitGb * 1024L * 1024L * 1024L : 0L);
        account.setUsedTraffic(0L);
        account.setSubscriptionUrl(subscriptionUrl(newToken()));

        // putIfAbsent, а не put: повторный create по тому же имени не должен молча
        // перевыпускать ссылку — у человека на руках уже может быть рабочая.
        AccessAccount existing = accounts.putIfAbsent(username, account);
        if (existing != null) {
            // Имя занято. Подобрать можно ТОЛЬКО осиротевший аккаунт — тот, за которым не
            // числится подписки: он остался от попытки, чья транзакция откатилась. Если имя
            // принадлежит живой подписке, подбор слил бы две подписки в одну.
            if (accountOwnership.ownedBySubscription(username)) {
                throw new IllegalStateException("Имя " + username + " уже принадлежит существующей "
                        + "подписке — занимать чужой аккаунт нельзя");
            }
            log.warn("Account {} already exists and belongs to nobody — reusing it", username);
            enableUserUntil(username, expireAt, deviceLimitOverride);
            return existing;
        }
        log.info("Created access account {} (expires {}, device limit {})",
                username, expireAt, effectiveDeviceLimit(deviceLimitOverride));
        return account;
    }

    @Override
    public Optional<AccessAccount> getUser(String username) {
        return Optional.ofNullable(accounts.get(username));
    }

    @Override
    public AccessAccount revokeSubscription(String username) {
        AccessAccount account = accounts.get(username);
        if (account == null) {
            throw new IllegalStateException("Cannot revoke: no access account named " + username);
        }
        account.setSubscriptionUrl(subscriptionUrl(newToken()));
        log.info("Rotated access token for {}", username);
        return account;
    }

    @Override
    public void disableUser(String username) {
        AccessAccount account = accounts.get(username);
        if (account == null) {
            log.warn("Cannot disable: no access account named {}", username);
            return;
        }
        account.setStatus("disabled");
        log.info("Disabled access account {}", username);
    }

    @Override
    public void enableUser(String username, int expireDays, Integer deviceLimitOverride) {
        enableUserUntil(username, Instant.now().plus(expireDays, ChronoUnit.DAYS), deviceLimitOverride);
    }

    @Override
    public void enableUserUntil(String username, Instant expireAt, Integer deviceLimitOverride) {
        // Аккаунт мог пропасть вместе с памятью процесса, а база о нём помнит. Восстанавливаем
        // молча: цель вызова — «пусть будет включён до этой даты», и она достижима.
        AccessAccount account = accounts.computeIfAbsent(username, name -> {
            AccessAccount fresh = new AccessAccount();
            fresh.setUsername(name);
            fresh.setUsedTraffic(0L);
            fresh.setDataLimit(0L);
            fresh.setSubscriptionUrl(subscriptionUrl(newToken()));
            return fresh;
        });
        account.setStatus("active");
        account.setExpire(expireAt.getEpochSecond());
        log.info("Enabled access account {} until {} (device limit {})",
                username, expireAt, effectiveDeviceLimit(deviceLimitOverride));
    }

    @Override
    public List<AccessNode> getNodes() {
        List<AppConfig.ServerConfig> servers = appConfig.getServers();
        if (servers == null || servers.isEmpty()) {
            return List.of();
        }
        return servers.stream().map(s -> {
            AccessNode node = new AccessNode();
            node.setName(s.getName());
            node.setAddress(s.getHost() + ":" + s.getPort());
            node.setStatus("connected");
            return node;
        }).toList();
    }

    private int effectiveDeviceLimit(Integer override) {
        if (override != null && override > 0) {
            return override;
        }
        return appConfig.getProvider().getDeviceLimit();
    }

    private String subscriptionUrl(String token) {
        String prefix = appConfig.getProvider().getSubscriptionUrlPrefix();
        String base = prefix == null || prefix.isBlank() ? "" : stripTrailingSlash(prefix);
        return base + "/sub/" + token;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String newToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
