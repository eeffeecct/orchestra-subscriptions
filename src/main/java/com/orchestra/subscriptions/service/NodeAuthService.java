package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Персональные учётные данные для узлов раздачи, которые проверяют подписчика сами.
 *
 * <p><b>Задача.</b> Узел на каждое подключение должен решить, пускать ли этого человека.
 * Ходить за ответом в базу он не может (её адрес ему знать незачем), а раздать всем один
 * общий пароль — значит потерять три вещи сразу: смена ссылки подписки такой пароль не
 * отзывает, истёкший подписчик продолжает работать по закешированному конфигу, и в логах
 * узла все сессии обезличены.
 *
 * <p><b>Решение — выводимый пароль.</b> Учётка подписчика это
 * {@code <provider_username>.<HMAC-SHA256(secret, provider_username + ":" + subscription_token)>}.
 * Свойства, ради которых так:
 * <ul>
 *   <li>состояния не требует — ни колонки, ни таблицы: пароль выводится из того, что уже есть;</li>
 *   <li>сам ротируется при смене ссылки: перевыпуск токена подписки меняет подпись, и
 *       старый пароль перестаёт проходить на всех узлах разом;</li>
 *   <li>позволяет отказать неактивным (истёк срок / отключён) — чего общий пароль не умел;</li>
 *   <li>{@code id} в ответе даёт узлу пер-пользовательский учёт в его собственных логах.</li>
 * </ul>
 *
 * <p>Фича выключена, если не задан {@code app.node-auth.auth-secret} — тогда узлы работают
 * на своих собственных правилах, а этот эндпоинт недоступен.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeAuthService {

    /** Разделитель «имя.подпись». Точки нет в имени аккаунта (см. {@code AccessService#buildProviderUsername}). */
    private static final char SEPARATOR = '.';
    /** Длина подписи в base64url-символах: 24 символа ≈ 144 бита — с запасом против перебора. */
    private static final int MAC_CHARS = 24;

    private final AppConfig appConfig;
    /**
     * Чтение подписки — через общий фасад, чтобы источник данных переключался одним флагом
     * и одинаково для всех потребителей.
     */
    private final SubscriptionLookupService lookup;

    /** Результат проверки: пускать ли, и под каким идентификатором писать сессию. */
    public record AuthResult(boolean ok, String id) {
        static AuthResult deny() {
            return new AuthResult(false, "");
        }
    }

    /** Фича активна: секрет задан. */
    public boolean enabled() {
        AppConfig.NodeAuthConfig cfg = appConfig.getNodeAuth();
        return cfg != null && cfg.getAuthSecret() != null && !cfg.getAuthSecret().isBlank();
    }

    /**
     * Секретный сегмент пути эндпоинта авторизации: узел может не уметь слать кастомные
     * заголовки, поэтому «кто спрашивает» подтверждается неугадываемым путём. Сравнение
     * побайтовое в постоянное время; пустой секрет в конфиге → эндпоинт недоступен.
     */
    public boolean pathSecretMatches(String candidate) {
        AppConfig.NodeAuthConfig cfg = appConfig.getNodeAuth();
        if (cfg == null || cfg.getAuthPathSecret() == null || cfg.getAuthPathSecret().isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                cfg.getAuthPathSecret().getBytes(StandardCharsets.UTF_8),
                candidate == null ? new byte[0] : candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** Учётные данные конкретной подписки. Пусто — фича выключена или данных нет. */
    public Optional<String> authFor(SubscriptionView sub) {
        if (!enabled() || sub == null
                || sub.providerUsername() == null || sub.providerUsername().isBlank()
                || sub.subscriptionToken() == null || sub.subscriptionToken().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(sub.providerUsername() + SEPARATOR
                + sign(sub.providerUsername(), sub.subscriptionToken()));
    }

    /** Совместимость с вызывающими, у которых на руках подписчик (экраны бота, Mini App). */
    public Optional<String> authFor(Customer user) {
        return user == null ? Optional.empty() : authFor(SubscriptionView.of(user));
    }

    /** То же по подписочному токену — вызывается при сборке ответа на {@code /sub/<token>}. */
    public Optional<String> authForToken(String subscriptionToken) {
        if (!enabled() || subscriptionToken == null || subscriptionToken.isBlank()) {
            return Optional.empty();
        }
        return lookup.byToken(subscriptionToken).flatMap(this::authFor);
    }

    /**
     * Проверка учётных данных, пришедших от узла. Отказ (без подробностей наружу) на:
     * пустом/битом формате, неизвестном имени, неверной подписи, неактивной подписке.
     */
    public AuthResult verify(String auth) {
        if (auth == null || auth.isBlank()) {
            return AuthResult.deny();
        }
        String candidate = auth.trim();

        int sep = candidate.lastIndexOf(SEPARATOR);
        if (sep <= 0 || sep == candidate.length() - 1) {
            return AuthResult.deny();
        }
        String providerUsername = candidate.substring(0, sep);
        String mac = candidate.substring(sep + 1);

        SubscriptionView sub = lookup.byProviderUsername(providerUsername).orElse(null);
        if (sub == null || sub.subscriptionToken() == null) {
            return AuthResult.deny();
        }
        String expected = sign(providerUsername, sub.subscriptionToken());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                mac.getBytes(StandardCharsets.UTF_8))) {
            // Чаще всего — конфиг, розданный ДО смены ссылки: старый токен, старая подпись.
            log.debug("Node auth: signature mismatch for {}", providerUsername);
            return AuthResult.deny();
        }
        if (!sub.activeAt(LocalDateTime.now())) {
            log.debug("Node auth: inactive subscription for {}", providerUsername);
            return AuthResult.deny();
        }
        return new AuthResult(true, providerUsername);
    }

    private String sign(String providerUsername, String subscriptionToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    appConfig.getNodeAuth().getAuthSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] raw = mac.doFinal(
                    (providerUsername + ":" + subscriptionToken).getBytes(StandardCharsets.UTF_8));
            String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            return b64.substring(0, Math.min(MAC_CHARS, b64.length()));
        } catch (Exception e) {
            // Не должно случаться: HmacSHA256 есть в любой JRE, ключ непустой (проверено enabled()).
            throw new IllegalStateException("Failed to sign node auth credential", e);
        }
    }
}
