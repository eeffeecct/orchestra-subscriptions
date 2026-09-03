package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.bot.TelegramSender;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.CustomerRepository;
import com.orchestra.subscriptions.util.Entities;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Рассылка сообщения всем юзерам (или только активным) от имени владельца.
 * Flow: admin отправляет /broadcast &lt;text&gt;, бот создаёт черновик (draft) и показывает
 * preview + кнопки подтверждения. По нажатию кнопки draft вынимается из мапы и запускается
 * отправка в фоне. Rate limit ≈ 28 msg/sec — под лимит Telegram 30/sec для массовых.
 *
 * <p>Рассылать можно не только текст: к сообщению цепляется фото (по {@code file_id} уже
 * залитой в Telegram картинки), а разметка — включая premium-эмодзи — переносится
 * сущностями, а не HTML-ом. Подробности про сущности см. {@link BroadcastContent} и
 * {@link Entities}.
 */
@Slf4j
@Service
public class BroadcastService {

    /** Интервал между сообщениями в мс. 35мс ≈ 28 msg/sec, ниже лимита Telegram. */
    private static final long RATE_LIMIT_MS = 35;

    /** TTL черновика в мс. После этого клик по кнопке подтверждения вернёт "черновик не найден". */
    private static final long DRAFT_TTL_MS = 10 * 60 * 1000;

    /** Лимит Telegram на текст сообщения. */
    public static final int TEXT_LIMIT = 4096;
    /** Лимит Telegram на подпись к медиа — вчетверо короче обычного сообщения. */
    public static final int CAPTION_LIMIT = 1024;

    private final CustomerRepository userRepository;
    /**
     * Отправка. Раньше здесь висел {@code @Lazy SubscriptionBot} — исключительно ради его
     * {@code execute(...)}, из-за чего сервис замыкал цикл с ботом. С фото это перестало
     * работать даже формально: медиа уходит multipart-запросом, {@code SendPhoto} — не
     * {@code BotApiMethod}, и в делегат бота не пролезает. Так что зависимость теперь на
     * {@link TelegramSender} — он и есть единственная точка отправки, и цикла с ним нет.
     */
    private final TelegramSender telegramSender;

    private final Map<String, DraftEntry> drafts = new ConcurrentHashMap<>();

    public BroadcastService(CustomerRepository userRepository, TelegramSender telegramSender) {
        this.userRepository = userRepository;
        this.telegramSender = telegramSender;
    }

    /**
     * Создаёт и сохраняет черновик. Возвращает id + подсчёт получателей для preview.
     */
    public BroadcastDraft prepare(BroadcastContent content) {
        long activeCount = userRepository.countByStatus(Customer.Status.ACTIVE);
        long disabledCount = userRepository.countByStatus(Customer.Status.DISABLED);
        long totalCount = userRepository.count();
        String id = UUID.randomUUID().toString();
        drafts.put(id, new DraftEntry(content, Instant.now().toEpochMilli()));
        return new BroadcastDraft(id, content, activeCount, disabledCount, totalCount);
    }

    /**
     * Забирает содержимое черновика. null — не найден или истёк.
     * Draft удаляется (одноразовое использование).
     */
    public BroadcastContent consumeDraft(String draftId) {
        DraftEntry entry = drafts.remove(draftId);
        if (entry == null) return null;
        if (Instant.now().toEpochMilli() - entry.createdAtMs > DRAFT_TTL_MS) {
            return null;
        }
        return entry.content;
    }

    /**
     * Запускает рассылку в фоне. Возвращает future со статистикой, чтобы админ мог получить
     * итоговый месседж по завершении.
     */
    public CompletableFuture<BroadcastStats> execute(BroadcastContent content, Target target) {
        return CompletableFuture.supplyAsync(() -> sendBlocking(content, target));
    }

    /** Для тестов: синхронная отправка (чтобы не гонять CompletableFuture в юнит-тестах). */
    BroadcastStats sendBlocking(BroadcastContent content, Target target) {
        List<Customer> recipients = switch (target) {
            case ACTIVE -> userRepository.findAllByStatus(Customer.Status.ACTIVE);
            case DISABLED -> userRepository.findAllByStatus(Customer.Status.DISABLED);
            case ALL -> userRepository.findAll();
        };
        Instant start = Instant.now();
        int success = 0;
        int failed = 0;
        for (int i = 0; i < recipients.size(); i++) {
            Customer user = recipients.get(i);
            try {
                send(user.getTelegramId().toString(), content);
                success++;
            } catch (TelegramApiException | RuntimeException e) {
                // Типичный кейс: юзер заблокировал бота → 403. Не прерываем рассылку.
                failed++;
                log.warn("Broadcast to telegramId={} failed: {}", user.getTelegramId(), e.getMessage());
            }
            // Throttle между отправками, кроме последней.
            if (i < recipients.size() - 1) {
                try {
                    Thread.sleep(RATE_LIMIT_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Broadcast done: target={} photo={} entities={} sent={} failed={} duration={}s",
                target, content.hasPhoto(), content.usesEntities(), success, failed, elapsed.getSeconds());
        return new BroadcastStats(success, failed, elapsed);
    }

    /**
     * Отправка одного экземпляра рассылки. Публичный метод, а не приватный хелпер: тем же
     * вызовом владельцу показывается превью — иначе «как выглядит черновик» и «что придёт
     * людям» разъезжались бы при первой же правке рендеринга.
     */
    public void send(String chatId, BroadcastContent content) throws TelegramApiException {
        if (content.hasPhoto()) {
            telegramSender.execute(photoFor(chatId, content));
        } else {
            telegramSender.execute(messageFor(chatId, content));
        }
    }

    /** Текстовый вариант рассылки. */
    public static SendMessage messageFor(String chatId, BroadcastContent content) {
        SendMessage msg = new SendMessage(chatId, content.text());
        applyFormatting(content, msg::setEntities, msg::setParseMode);
        msg.disableWebPagePreview();
        return msg;
    }

    /**
     * Фото с подписью. Картинка уходит по {@code file_id} — файл уже лежит в Telegram
     * (владелец прислал его боту), поэтому на тысячу получателей не будет тысячи загрузок.
     */
    public static SendPhoto photoFor(String chatId, BroadcastContent content) {
        SendPhoto photo = new SendPhoto(chatId, new InputFile(content.photoFileId()));
        if (!content.text().isEmpty()) {
            photo.setCaption(content.text());
            applyFormatting(content, photo::setCaptionEntities, photo::setParseMode);
        }
        return photo;
    }

    /**
     * Либо HTML, либо сущности — вместе Telegram их не принимает, да и смысла нет: сущности
     * уже описывают разметку до символа, а parse_mode заставил бы разбирать текст заново
     * (и подавиться первой же угловой скобкой, набранной как обычный символ).
     */
    private static void applyFormatting(BroadcastContent content,
                                        java.util.function.Consumer<List<MessageEntity>> entities,
                                        java.util.function.Consumer<String> parseMode) {
        if (content.html()) {
            parseMode.accept("HTML");
        } else {
            entities.accept(content.entities());
        }
    }

    /**
     * Кому уходит рассылка.
     * <p>
     * {@link #DISABLED} заведён под винбек: без него «вернитесь, мы соскучились» пришлось бы
     * слать через {@link #ALL}, то есть тем же письмом задеть действующих подписчиков —
     * не та аудитория и лишний повод отписаться.
     */
    public enum Target {
        /** Действующие подписки. */
        ACTIVE,
        /** Отключившиеся: срок истёк и не продлён. Аудитория винбека. */
        DISABLED,
        /** Все подряд, включая тех, кто ни разу не активировал доступ. */
        ALL
    }

    /**
     * Что именно рассылаем: текст, разметка к нему и (необязательно) фото.
     *
     * <p>Разметка хранится ровно в том виде, в каком её прислал Telegram — списком сущностей.
     * Это не оптимизация, а единственный способ не потерять premium-эмодзи: у него нет
     * текстового представления, в тексте на его месте стоит обычный глиф, а «анимированность»
     * живёт отдельной сущностью {@code custom_emoji} со смещением и id.
     *
     * <p>Два взаимоисключающих режима разметки:
     * <ul>
     *   <li>{@code html=true} — текст разбирается как HTML. Исторический контракт
     *       {@code /broadcast}: {@code <b>}, {@code <a href>}, {@code <tg-emoji>} набираются
     *       руками.</li>
     *   <li>{@code html=false} — разметку уже собрал клиент Telegram, отправляем сущностями.
     *       parse_mode при этом выключен, и угловые скобки в тексте остаются обычными
     *       символами.</li>
     * </ul>
     *
     * @param text        текст сообщения или подпись к фото (для фото без подписи — пустая строка)
     * @param entities    разметка Telegram; в HTML-режиме игнорируется и обнуляется
     * @param photoFileId {@code file_id} картинки в Telegram или null
     * @param html        разбирать ли текст как HTML вместо сущностей
     */
    public record BroadcastContent(String text, List<MessageEntity> entities,
                                   String photoFileId, boolean html) {

        public BroadcastContent {
            text = text == null ? "" : text;
            entities = (html || entities == null) ? List.of() : List.copyOf(entities);
        }

        /** Текст с HTML-разметкой, без фото — «классический» {@code /broadcast <текст>}. */
        public static BroadcastContent html(String text) {
            return new BroadcastContent(text, List.of(), null, true);
        }

        public boolean hasPhoto() {
            return photoFileId != null && !photoFileId.isBlank();
        }

        /** true — отправлять сущностями (и не включать HTML). */
        public boolean usesEntities() {
            return !html;
        }

        /** Ничего не рассылаем: ни текста, ни картинки. */
        public boolean isEmpty() {
            return text.isEmpty() && !hasPhoto();
        }

        /** Лимит Telegram на эту конкретную форму сообщения. */
        public int limit() {
            return hasPhoto() ? CAPTION_LIMIT : TEXT_LIMIT;
        }

        /**
         * Сколько символов увидит Telegram. В режиме сущностей — ровно длина текста; в
         * HTML-режиме теги до получателя не доезжают, и считать их — значит отказывать в
         * рассылке, которую Telegram принял бы.
         *
         * <p>Оценка нарочно щадящая: экранированные {@code &lt;} тут по-прежнему считаются
         * четырьмя символами вместо одного, то есть длину можно только переоценить, но не
         * недооценить. Точный арбитр всё равно один — Telegram, и он высказывается на превью,
         * до первого получателя.
         */
        public int visibleLength() {
            return html ? text.replaceAll("<[^>]*>", "").length() : text.length();
        }

        /** Не влезает в лимит Telegram — отправка упала бы у всех получателей сразу. */
        public boolean tooLong() {
            return visibleLength() > limit();
        }

        /** Сколько premium-эмодзи уедет получателям — показывается владельцу в превью. */
        public long customEmojiCount() {
            return Entities.countCustomEmoji(entities);
        }
    }

    public record BroadcastDraft(String id, BroadcastContent content,
                                long activeCount, long disabledCount, long totalCount) {}

    public record BroadcastStats(int success, int failed, Duration duration) {}

    private record DraftEntry(BroadcastContent content, long createdAtMs) {}
}
