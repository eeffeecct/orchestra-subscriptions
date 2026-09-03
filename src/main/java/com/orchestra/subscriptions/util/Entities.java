package com.orchestra.subscriptions.util;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Перенос телеграмной разметки ({@code message.entities} / {@code caption_entities}) на текст,
 * у которого срезали начало.
 *
 * <p>Зачем это нужно. Premium-эмодзи не существует в виде символа: в тексте на его месте стоит
 * обычный unicode-глиф, а «анимированность» живёт отдельно — сущностью {@code custom_emoji} со
 * смещением и id. Пересказать её HTML-ом можно (тег {@code <tg-emoji>}), но владелец команду
 * набирает не HTML-ом, а вставкой эмодзи прямо в поле ввода — то есть в боте оказывается
 * <b>текст + список сущностей</b>. Единственный способ разослать это без потерь — отправить
 * получателям ровно тот же список сущностей, а не пытаться его отрендерить.
 *
 * <p>Загвоздка в том, что смещения считаются от начала исходного сообщения, а рассылается не
 * оно целиком, а хвост после {@code /broadcast}. Срезали 11 символов — все сущности уехали на
 * 11 влево, иначе жирным станет не то слово, а эмодзи «поедет» на соседний символ.
 *
 * <p>Смещения Telegram считает в UTF-16 code units — в той же единице, что и
 * {@link String#length()}, поэтому арифметика ложится на индексы Java один в один.
 */
public final class Entities {

    /** Сама команда («/broadcast»). */
    public static final String BOT_COMMAND = "bot_command";
    /** Premium-эмодзи. */
    public static final String CUSTOM_EMOJI = "custom_emoji";

    /**
     * Типы сущностей, которые Telegram НЕ проставляет сам — их можно получить только тем, что
     * автор осознанно что-то сделал: нажал «жирный», вставил анимированное эмодзи, оформил
     * ссылку своим текстом. Именно по ним {@link #hasFormatting} решает, что сообщение уже
     * размечено клиентом.
     *
     * <p>Список — allowlist, а не «всё кроме автоматических», намеренно. Если Telegram заведёт
     * ещё один автоматический тип, незнакомый тип просто не попадёт сюда, и сообщение останется
     * в HTML-режиме — то есть поведение не изменится. Обратный список ошибался бы в другую
     * сторону: новый автоматический тип начал бы молча выключать HTML, а это ровно та поломка,
     * из-за которой allowlist и появился.
     */
    private static final Set<String> DELIBERATE = Set.of(
            "bold", "italic", "underline", "strikethrough", "spoiler",
            "code", "pre", "blockquote", "expandable_blockquote",
            "text_link", "text_mention", CUSTOM_EMOJI);

    private Entities() {
    }

    /**
     * Сдвигает сущности на {@code cut} символов влево и подрезает под текст длиной {@code length}.
     * Сущности, целиком оставшиеся в срезанной части (та же {@code bot_command} команды) или
     * вылезшие за хвост, отбрасываются.
     *
     * <p>{@code custom_emoji} при частичном перекрытии не подрезается, а отбрасывается: Telegram
     * требует, чтобы длина сущности точно совпадала с глифом, и «половина эмодзи» — это ошибка
     * запроса, то есть недоставленная рассылка, а не кривая буква.
     *
     * @param source исходные сущности (может быть null)
     * @param cut    сколько символов срезано с начала
     * @param length длина получившегося текста
     * @return новые сущности с пересчитанными смещениями; никогда не null
     */
    public static List<MessageEntity> reanchor(List<MessageEntity> source, int cut, int length) {
        if (source == null || source.isEmpty() || length <= 0) {
            return List.of();
        }
        List<MessageEntity> out = new ArrayList<>(source.size());
        for (MessageEntity src : source) {
            if (src == null || src.getOffset() == null || src.getLength() == null) {
                continue;
            }
            int start = src.getOffset() - cut;
            int end = start + src.getLength();
            boolean clipped = start < 0 || end > length;
            if (clipped && CUSTOM_EMOJI.equals(src.getType())) {
                continue;
            }
            start = Math.max(start, 0);
            end = Math.min(end, length);
            if (end <= start) {
                continue;
            }
            out.add(at(src, start, end - start));
        }
        return List.copyOf(out);
    }

    /**
     * Размечал ли автор сообщение средствами Telegram.
     *
     * <p>По этому признаку решается, чем отправлять: «нет» — текст разбирается как HTML
     * (исторический контракт {@code /broadcast}: {@code <b>}, {@code <a href>} и прочее
     * набираются руками), «да» — разметка уже собрана клиентом, и HTML включать нельзя, иначе
     * случайная угловая скобка в тексте развалит отправку.
     *
     * <p>Считаются только «сделанные руками» типы из {@link #DELIBERATE}. Отличать их
     * обязательно: Telegram сам, без всякого участия автора, размечает ссылки, {@code @}-упоминания,
     * хэштеги, адреса почты и телефоны. Если считать разметкой и их, то {@code /broadcast} с
     * любой ссылкой в тексте вылетал бы из HTML-режима — а первым бы пострадал сам
     * документированный {@code <a href="https://...">}: URL внутри атрибута Telegram размечает
     * как ссылку, и теги уехали бы получателям обычными символами.
     */
    public static boolean hasFormatting(List<MessageEntity> entities) {
        return entities != null && entities.stream()
                .anyMatch(e -> e != null && DELIBERATE.contains(e.getType()));
    }

    /** Сколько premium-эмодзи в списке — для отчёта владельцу в превью. */
    public static long countCustomEmoji(List<MessageEntity> entities) {
        return entities == null ? 0 : entities.stream()
                .filter(e -> e != null && CUSTOM_EMOJI.equals(e.getType()))
                .count();
    }

    /**
     * Копия сущности с другими координатами. Именно копия, а не мутация исходной: объект
     * приезжает из апдейта и переиспользуется — незаметная правка смещения на месте ломала бы
     * повторный разбор того же сообщения.
     */
    private static MessageEntity at(MessageEntity src, int offset, int length) {
        return MessageEntity.builder()
                .type(src.getType())
                .offset(offset)
                .length(length)
                .url(src.getUrl())
                .user(src.getUser())
                .language(src.getLanguage())
                .customEmojiId(src.getCustomEmojiId())
                .unixTime(src.getUnixTime())
                .dateTimeFormat(src.getDateTimeFormat())
                .build();
    }
}
