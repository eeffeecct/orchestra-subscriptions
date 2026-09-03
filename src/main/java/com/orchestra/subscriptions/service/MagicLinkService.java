package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.MagicLink;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.MagicLinkRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.util.EmailMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Управляет одноразовыми токенами для входа в web-ЛК по email.
 * <p>
 * Жизненный цикл токена:
 * <ol>
 *   <li>{@link #create(Customer)} — генерируется secure random 32 байта, сохраняется
 *       в {@code magic_links} с TTL 15 минут. Возвращается base64url-строка для
 *       подстановки в URL письма.</li>
 *   <li>{@link #preview(String)} — те же проверки, но БЕЗ пометки used. Нужен
 *       {@code GET /auth}, чтобы показать страницу подтверждения входа, не сжигая
 *       токен: письмо до человека доходит через почтовые сканеры и предзагрузку
 *       ссылок, и токен, погашенный простым открытием URL, ломает вход живым людям.</li>
 *   <li>{@link #consume(String)} — при клике на {@code GET /auth?token=...} токен
 *       находится, проверяется {@code expires_at} и {@code used_at}, помечается
 *       used, и возвращается соответствующий {@link Customer}. Также проставляется
 *       {@code email_verified_at} если ещё не было — клик по письму = подтверждение
 *       владения адресом.</li>
 *   <li>{@link #cleanupExpired()} — раз в час @Scheduled чистит токены, у которых
 *       {@code expires_at < now() - 1 day}. Освобождает место и не хранит PII
 *       (email) дольше нужного. Гистерезис в 1 сутки — защита от удаления токена,
 *       которому только что вышел срок (юзер кликает в эту самую секунду).</li>
 * </ol>
 *
 * <h2>Безопасность</h2>
 * <ul>
 *   <li>Токен — 32 байта из {@link SecureRandom} → 256 бит энтропии. Перебор
 *       исключён.</li>
 *   <li>Single-use: после первого {@code consume} токен мёртв. Защищает от
 *       повтора по утечке URL (например через history браузера у общего ПК).</li>
 *   <li>Email-snapshot: токен привязан к адресу на момент создания. Если юзер
 *       сменил email до клика — токен ведёт в кабинет на старый адрес. Это
 *       намеренно: иначе атакующий мог бы перехватить чужой email через смену.</li>
 *   <li>Привязка к браузеру: {@link #create(Customer, String)} запоминает отпечаток
 *       nonce-cookie того браузера, который ссылку запросил, а {@link #matchesBrowser}
 *       отвечает, он ли её открыл. Токен, привязанный только к аккаунту, позволял
 *       атакующему заказать ссылку на СВОЙ адрес и заманить жертву её открыть —
 *       жертва оказывалась в чужой сессии и могла оплатить чужую подписку.</li>
 *   <li>TTL 15 минут — стандарт OWASP для magic-link.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MagicLinkService {

    /** Срок действия магических ссылок — стандарт OWASP. */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    /** Через сколько после expires удалять токен из БД. Гистерезис на «кликнул в последний момент». */
    static final Duration CLEANUP_RETENTION = Duration.ofDays(1);

    /** 32 байта → 256 бит энтропии, ~44 base64url-символа. */
    private static final int TOKEN_BYTES = 32;

    /** Потолок карты привязок: на вставке выше него сначала выметаем протухшее. */
    private static final int MAX_BROWSER_BINDINGS = 50_000;

    private final MagicLinkRepository magicLinkRepository;
    private final CustomerRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Привязка «браузер ↔ токен»: {@code sha256(token) → отпечаток nonce-cookie}, которую
     * получил браузер в момент запроса ссылки.
     * <p>
     * <b>Почему в памяти, а не колонкой в {@code magic_links}.</b> Потеря привязки вход НЕ
     * ломает: {@code GET /auth} тогда показывает страницу подтверждения — ровно то же, что
     * видит человек, открывший письмо на другом устройстве. То есть после рестарта хуже
     * становится на один клик, а не на отказ во входе, и ради этого не стоит ни миграции,
     * ни лишней записи в БД на каждое письмо. Инстанс приложения один, кластера нет — та же
     * причина, по которой in-memory сделан {@code MagicLinkRateLimiter}.
     * <p>
     * Храним отпечатки, а не сами значения: и токен, и nonce — секреты входа, а карта живёт
     * в heap и попадает в дампы вместе с ним.
     */
    private final ConcurrentHashMap<String, BrowserBinding> browserBindings = new ConcurrentHashMap<>();

    /** Отпечаток nonce-cookie браузера + срок, после которого привязку можно выбрасывать. */
    private record BrowserBinding(String nonceHashHex, LocalDateTime expiresAt) {}

    /**
     * Данные токена БЕЗ его погашения: кому он выписан и на какой (замаскированный) адрес
     * уходило письмо. Адрес маскируем прямо здесь — наружу, на страницу подтверждения,
     * чистая почта человека не выходит.
     */
    public record Preview(long telegramId, String maskedEmail) {}

    /**
     * Создаёт magic-link токен БЕЗ привязки к браузеру. Ссылка из этого токена всегда
     * потребует подтверждения на {@code GET /auth} — так и должно быть для каналов, где
     * запрос пришёл не из браузера (Mini App, бот): браузер, в котором человек откроет
     * письмо, мы там пометить не можем.
     */
    @Transactional
    public String create(Customer user) {
        return create(user, null);
    }

    /**
     * Создаёт magic-link токен для юзера. Юзер должен иметь сохранённый email
     * — без него токен бессмыслен (некуда слать письмо). Валидацию проверяет
     * caller (контроллер).
     *
     * @param browserNonce значение nonce-cookie, выданной браузеру в момент запроса ссылки;
     *                     {@code null}/пусто — привязки не будет (см. {@link #create(Customer)})
     * @return base64url-токен для подстановки в URL письма
     */
    @Transactional
    public String create(Customer user, String browserNonce) {
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Cannot create magic-link: user has no email");
        }
        String token = generateToken();
        LocalDateTime now = LocalDateTime.now();
        MagicLink link = MagicLink.builder()
                .token(token)
                .telegramId(user.getTelegramId())
                .email(email)
                .createdAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .build();
        magicLinkRepository.save(link);
        rememberBrowser(token, browserNonce, link.getExpiresAt());
        log.info("Magic-link created for telegramId={} email={} expires={} boundToBrowser={}",
                user.getTelegramId(), EmailMasker.mask(email), link.getExpiresAt(),
                browserNonce != null && !browserNonce.isBlank());
        return token;
    }

    /**
     * Проверяет токен так же, как {@link #consume(String)}, но НЕ помечает его
     * использованным. Нужен странице подтверждения входа: она показывается ДО того, как
     * человек нажал кнопку, и обязана оставить токен рабочим — иначе первый же почтовый
     * антивирус или предзагрузка ссылки в браузере сжигали бы вход за человека.
     *
     * @throws InvalidMagicLinkException те же случаи и с теми же сообщениями, что у consume
     */
    @Transactional(readOnly = true)
    public Preview preview(String token) {
        Validated validated = validate(token);
        return new Preview(validated.link().getTelegramId(),
                EmailMasker.mask(validated.link().getEmail()));
    }

    /**
     * Тот ли это браузер, который ссылку и запрашивал: сверяет nonce из cookie с отпечатком,
     * сохранённым в момент выпуска ЭТОГО токена.
     * <p>
     * {@code false} — это НЕ признак атаки и не повод отказать во входе: письмо часто
     * открывают на другом устройстве, а привязка живёт в памяти процесса и теряется при
     * рестарте. Единственное следствие — {@code /auth} просит подтверждение вместо
     * молчаливой выдачи сессии.
     */
    public boolean matchesBrowser(String token, String browserNonce) {
        if (token == null || token.isBlank() || browserNonce == null || browserNonce.isBlank()) {
            return false;
        }
        String key = sha256Hex(token);
        BrowserBinding binding = browserBindings.get(key);
        if (binding == null) {
            return false;
        }
        if (binding.expiresAt().isBefore(LocalDateTime.now())) {
            browserBindings.remove(key);
            return false;
        }
        // Сравниваем отпечатки, а не строки: constant-time, чтобы посимвольный подбор
        // nonce по времени ответа не работал.
        return MessageDigest.isEqual(
                binding.nonceHashHex().getBytes(StandardCharsets.UTF_8),
                sha256Hex(browserNonce).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Погашает токен при клике по magic-link. Если валиден — возвращает юзера и
     * выставляет {@code email_verified_at} в момент клика (если ещё не было).
     *
     * @throws InvalidMagicLinkException на любую ошибку: токен не найден, истёк,
     *                                   уже использован, либо юзер за это время удалён.
     *                                   Caller (контроллер) должен показать одинаковую
     *                                   страницу «ссылка устарела или уже использована»
     *                                   на все три случая — деталями не делимся (privacy).
     */
    @Transactional
    public Customer consume(String token) {
        Validated validated = validate(token);
        MagicLink link = validated.link();
        Customer user = validated.user();

        LocalDateTime now = LocalDateTime.now();
        link.setUsedAt(now);
        try {
            // saveAndFlush, не save: при FlushMode.AUTO версионный UPDATE иначе выполнится
            // на commit транзакции — уже ВНЕ этого try/catch, в @Transactional-прокси, и
            // OptimisticLockingFailure прилетит вызывающему как HTTP 500 вместо graceful
            // редиректа на /account/expired. Явный flush поднимает исключение здесь.
            magicLinkRepository.saveAndFlush(link);
        } catch (OptimisticLockingFailureException e) {
            // Race: параллельный consume того же токена уже пометил used_at и
            // получил Customer. Эта попытка — проиграла гонку. Откатываем семантически
            // через InvalidMagicLinkException, чтобы caller показал «недействительна».
            log.info("Magic-link consume race lost: telegramId={}", link.getTelegramId());
            throw new InvalidMagicLinkException("token already used");
        }

        // Токен погашен — привязка к браузеру больше ни на что не влияет, не держим её в памяти.
        browserBindings.remove(sha256Hex(token));

        // Подтверждение email — впервые делается именно здесь. После первого
        // успешного консьюма email_verified_at становится != null, и важные письма
        // (refund-подтверждение, восстановление) можно слать без оговорок.
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
            userRepository.save(user);
            log.info("Email verified via magic-link: telegramId={}", user.getTelegramId());
        }
        return user;
    }

    /**
     * Очистка протухших токенов — раз в час. Удаляет всё, что истекло более
     * {@link #CLEANUP_RETENTION} назад. Идемпотентно: нет токенов — просто
     * залогирует «removed=0».
     * <p>
     * Cron: каждый час в 17-ю минуту. Сдвиг от ровного часа — чтобы не
     * конкурировать с другими @Scheduled (см. AutoRenewService, PaymentReconcileService).
     * <p>
     * Заодно выметает протухшие привязки «браузер ↔ токен»: отдельная задача им не нужна,
     * это та же уборка за теми же токенами.
     */
    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minus(CLEANUP_RETENTION);
        int removed = magicLinkRepository.deleteAllExpiredBefore(cutoff);
        if (removed > 0) {
            log.info("Magic-links cleanup: removed {} tokens older than {}", removed, cutoff);
        }
        pruneBrowserBindings();
    }

    /**
     * Запоминает, какой браузер запросил ссылку. Пустой nonce — легальный случай (запрос
     * пришёл не из браузера), тогда привязки просто нет.
     */
    private void rememberBrowser(String token, String browserNonce, LocalDateTime expiresAt) {
        if (browserNonce == null || browserNonce.isBlank()) {
            return;
        }
        // Потолок на случай, если кто-то начнёт молотить по /auth/request через пул адресов:
        // карта не должна расти бесконечно. Сначала выметаем протухшее, и если места всё
        // равно нет — привязку не пишем. Человек в этом случае увидит подтверждение входа,
        // то есть штатный экран, а не отказ.
        if (browserBindings.size() >= MAX_BROWSER_BINDINGS) {
            pruneBrowserBindings();
            if (browserBindings.size() >= MAX_BROWSER_BINDINGS) {
                log.warn("Magic-link browser bindings are at cap ({}), skipping binding", MAX_BROWSER_BINDINGS);
                return;
            }
        }
        browserBindings.put(sha256Hex(token), new BrowserBinding(sha256Hex(browserNonce), expiresAt));
    }

    /** Выбрасывает привязки, чей токен уже не может быть погашен. */
    private void pruneBrowserBindings() {
        LocalDateTime now = LocalDateTime.now();
        int before = browserBindings.size();
        browserBindings.values().removeIf(binding -> binding.expiresAt().isBefore(now));
        int removed = before - browserBindings.size();
        if (removed > 0) {
            log.info("Magic-link browser bindings cleanup: removed {}, left {}",
                    removed, browserBindings.size());
        }
    }

    /** Пара «токен + его владелец», прошедшая все проверки. */
    private record Validated(MagicLink link, Customer user) {}

    /**
     * Общие проверки токена для {@link #preview} и {@link #consume}: существует, не
     * погашен, не истёк, юзер жив, email не менялся. Ничего не мутирует — единственный
     * побочный эффект в consume (пометка used) остаётся в самом consume.
     */
    private Validated validate(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidMagicLinkException("empty token");
        }
        MagicLink link = magicLinkRepository.findByToken(token)
                .orElseThrow(() -> new InvalidMagicLinkException("token not found"));

        if (link.getUsedAt() != null) {
            log.info("Magic-link already used: telegramId={} usedAt={}",
                    link.getTelegramId(), link.getUsedAt());
            throw new InvalidMagicLinkException("token already used");
        }
        if (link.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("Magic-link expired: telegramId={} expiredAt={}",
                    link.getTelegramId(), link.getExpiresAt());
            throw new InvalidMagicLinkException("token expired");
        }

        Customer user = userRepository.findByTelegramId(link.getTelegramId())
                .orElseThrow(() -> new InvalidMagicLinkException("user not found"));

        // Snapshot-проверка email: токен валиден только для того адреса, на который
        // его реально посылали. Если юзер успел поменять email до клика — токен
        // не сработает (а новый адрес требует свежего согласия + magic-link).
        if (!link.getEmail().equalsIgnoreCase(user.getEmail())) {
            log.warn("Magic-link email mismatch: telegramId={} tokenEmail={} userEmail={}",
                    link.getTelegramId(),
                    EmailMasker.mask(link.getEmail()),
                    EmailMasker.mask(user.getEmail()));
            throw new InvalidMagicLinkException("email changed");
        }
        return new Validated(link, user);
    }

    /** Отпечаток секрета для карты привязок — hex sha256, сами значения в памяти не держим. */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен для любой JVM — сюда попасть нельзя.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** base64url без padding — URL-safe и короче обычного base64. */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Любая ошибка валидации — единый тип, чтобы контроллер показал одну страницу «недействительна». */
    public static class InvalidMagicLinkException extends RuntimeException {
        public InvalidMagicLinkException(String reason) { super(reason); }
    }
}
