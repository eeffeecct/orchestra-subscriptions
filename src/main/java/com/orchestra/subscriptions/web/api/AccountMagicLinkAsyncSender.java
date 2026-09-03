package com.orchestra.subscriptions.web.api;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.service.MagicLinkService;
import com.orchestra.subscriptions.service.NotificationService;
import com.orchestra.subscriptions.util.SubscriptionLinks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Асинхронная отправка magic-link письма из публичного {@code /auth/request}.
 * <p>
 * Цель — устранить разницу в latency между known/unknown email в публичном
 * endpoint'е. Без async:
 * <ul>
 *   <li>known email path: 200-800мс (DB-insert токена + sync SMTP send)</li>
 *   <li>unknown email path: 5-20мс (только findByEmail)</li>
 * </ul>
 * Атакующий через измерение latency мог узнать какие email привязаны к
 * аккаунтам. С async — контроллер отвечает 200 моментально в обоих случаях
 * (~10-20мс на findByEmail), а DB-insert + SMTP уезжают в @Async pool в фоне.
 * <p>
 * <b>Почему отдельный класс:</b> @Async-proxy работает только при вызове через
 * Spring bean. Если бы метод жил в AccountController и вызывался изнутри —
 * self-invocation обошёл бы proxy, и фоновая отправка не запустилась бы.
 * <p>
 * <b>Здесь же лимит на адрес.</b> Контроллер лимитирует по IP, и этого мало:
 * пул адресов-источников (ботнет, облако, мобильный NAT) позволяет слать письма
 * в один ящик пачкой. Человек получает поток писем, которых не просил, а мы —
 * жалобы на спам и просадку репутации отправителя, которую восстанавливать
 * месяцами. Проверка стоит именно тут, потому что синхронная часть контроллера
 * выполняется только для known email: любая работа там измерима снаружи и выдала
 * бы, что адрес привязан к аккаунту (см. про anti-enumeration выше).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountMagicLinkAsyncSender {

    private final MagicLinkService magicLinkService;
    private final NotificationService notificationService;
    private final AppConfig appConfig;
    /** Тот же лимитер, что у контроллера, но с ключом по адресу — своей копии счётчиков не заводим. */
    private final MagicLinkRateLimiter magicLinkRateLimiter;

    /**
     * Создаёт токен и шлёт письмо, если адрес не выбрал свой лимит (1/мин, 5/час —
     * общие константы {@link MagicLinkRateLimiter}). Исключения swallow'ятся —
     * контроллер уже отдал юзеру 200 и не должен «откатывать» этот успех. Любая
     * ошибка (DB упала, SMTP лежит) попадает в WARN в логе.
     * <p>
     * Упёршийся в лимит адрес молча не получает письма: наверх сообщать некуда и
     * нечего — юзеру уже отдан generic-ответ «если адрес привязан, письмо придёт»,
     * и он одинаков для всех исходов by design.
     *
     * @param browserNonce значение nonce-cookie, которую контроллер выдал браузеру в этом
     *                     же ответе. Токен создаётся ЗДЕСЬ, уже после ответа клиенту,
     *                     поэтому связать его с браузером может только этот метод: nonce
     *                     приезжает параметром и уходит в
     *                     {@link MagicLinkService#create(Customer, String)}. Пусто —
     *                     привязки не будет, вход тогда потребует подтверждения.
     */
    @Async
    public void sendMagicLink(Customer user, String browserNonce) {
        try {
            MagicLinkRateLimiter.Decision decision =
                    magicLinkRateLimiter.tryAcquire("email:" + emailKey(user.getEmail()));
            if (!decision.allowed()) {
                log.info("Magic-link async send skipped by per-address limit for telegramId={} reason={}",
                        user.getTelegramId(), decision.reason());
                return;
            }

            String token = magicLinkService.create(user, browserNonce);
            String base = appConfig.getTelegram() == null ? null
                    : SubscriptionLinks.stripTrailingSlash(appConfig.getTelegram().getPublicBaseUrl());
            String authUrl = (base != null ? base : "") + "/auth?token=" + token;
            boolean sent = notificationService.sendMagicLinkEmail(user.getEmail(), authUrl);
            if (sent) {
                log.info("Magic-link sent async for telegramId={}", user.getTelegramId());
            } else {
                // SMTP-провайдер забраковал адрес (suppression list / bounce). В
                // публичном /auth/request flow юзер уже получил generic «если email
                // привязан — придёт письмо» — мы не можем surface'ить ошибку обратно,
                // это бы поломало anti-enumeration design. Логируем для ops-видимости.
                log.warn("Magic-link async send was rejected by SMTP for telegramId={} — likely suppression list",
                        user.getTelegramId());
            }
        } catch (Exception e) {
            log.warn("Async magic-link send failed for telegramId={}",
                    user.getTelegramId(), e);
        }
    }

    /**
     * Ключ лимита по адресу: SHA-256 от нормализованного (trim + lowercase) email в hex.
     * Сам адрес ключом не делаем сознательно — карта лимитера лежит в памяти процесса и
     * попадает в heap-дампы вместе с ней, а почта людей — персональные данные, которым
     * там делать нечего. Для счётчика достаточно стабильного отпечатка.
     */
    private static String emailKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен для любой JVM — сюда попасть нельзя.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
