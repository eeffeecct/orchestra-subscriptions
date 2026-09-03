package com.orchestra.subscriptions.util;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пересчёт смещений — то место, где ошибка не падает, а тихо уезжает: жирным станет соседнее
 * слово, а premium-эмодзи прилипнет не к тому символу. Увидеть это можно только по уже
 * разосланному сообщению, поэтому арифметика проверяется здесь, а не глазами в чате.
 */
class EntitiesTest {

    /** «/broadcast » — 11 символов, ровно столько срезается у обычной команды. */
    private static final int CUT = 11;

    @Test
    void reanchor_shouldShiftOffsetsLeftByCut() {
        // "/broadcast Привет, мир" → жирное «мир» стоит на 19-м символе исходника
        List<MessageEntity> shifted = Entities.reanchor(
                List.of(entity("bold", 19, 3)), CUT, "Привет, мир".length());

        assertThat(shifted).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("bold");
            assertThat(e.getOffset()).isEqualTo(8);
            assertThat(e.getLength()).isEqualTo(3);
        });
    }

    @Test
    void reanchor_shouldDropTheCommandItself() {
        List<MessageEntity> shifted = Entities.reanchor(
                List.of(entity("bot_command", 0, 10)), CUT, 5);

        assertThat(shifted).isEmpty();
    }

    @Test
    void reanchor_shouldKeepCustomEmojiId() {
        MessageEntity premium = MessageEntity.builder()
                .type("custom_emoji").offset(11).length(2).customEmojiId("5000000000000000001").build();

        List<MessageEntity> shifted = Entities.reanchor(List.of(premium), CUT, 2);

        assertThat(shifted).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("custom_emoji");
            assertThat(e.getOffset()).isZero();
            assertThat(e.getLength()).isEqualTo(2);
            assertThat(e.getCustomEmojiId()).isEqualTo("5000000000000000001");
        });
    }

    /**
     * Обрезанная наполовину сущность обычного форматирования — просто кривая буква, а
     * обрезанный {@code custom_emoji} — ошибка запроса: Telegram требует, чтобы длина точно
     * совпадала с глифом. Одна такая сущность роняет отправку каждому получателю, поэтому
     * такое эмодзи выбрасывается целиком.
     */
    @Test
    void reanchor_shouldDropClippedCustomEmojiButClipPlainFormatting() {
        MessageEntity premium = MessageEntity.builder()
                .type("custom_emoji").offset(10).length(2).customEmojiId("777").build();
        MessageEntity bold = entity("bold", 10, 6);

        List<MessageEntity> shifted = Entities.reanchor(List.of(premium, bold), CUT, 20);

        assertThat(shifted).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("bold");
            assertThat(e.getOffset()).isZero();
            assertThat(e.getLength()).isEqualTo(5); // потерял один символ, уехавший в команду
        });
    }

    @Test
    void reanchor_shouldClipEntityRunningPastTheTail() {
        List<MessageEntity> shifted = Entities.reanchor(List.of(entity("italic", 11, 50)), CUT, 4);

        assertThat(shifted).singleElement().satisfies(e -> {
            assertThat(e.getOffset()).isZero();
            assertThat(e.getLength()).isEqualTo(4);
        });
    }

    @Test
    void reanchor_shouldDropEntityBeyondTheTail() {
        assertThat(Entities.reanchor(List.of(entity("bold", 100, 3)), CUT, 4)).isEmpty();
    }

    /**
     * Смещения Telegram считает в UTF-16 code units — в той же единице, что и индексы Java.
     * Эмодзи из суррогатной пары занимает две: если бы считалось «по символам Unicode»,
     * следующая сущность уехала бы на единицу.
     */
    @Test
    void reanchor_shouldCountInUtf16CodeUnitsLikeJava() {
        String payload = "🐬 дельфин"; // 🐬 = 2 code units
        String raw = "/broadcast " + payload;
        int boldStart = raw.indexOf("дельфин");

        List<MessageEntity> shifted = Entities.reanchor(
                List.of(entity("bold", boldStart, "дельфин".length())), CUT, payload.length());

        assertThat(shifted).singleElement().satisfies(e ->
                assertThat(payload.substring(e.getOffset(), e.getOffset() + e.getLength()))
                        .isEqualTo("дельфин"));
    }

    /** Объект приезжает из апдейта и переиспользуется — правка на месте сломала бы повтор. */
    @Test
    void reanchor_shouldNotMutateSourceEntities() {
        MessageEntity source = entity("bold", 19, 3);

        Entities.reanchor(List.of(source), CUT, 11);

        assertThat(source.getOffset()).isEqualTo(19);
    }

    @Test
    void hasFormatting_shouldNotCountTheCommandHighlight() {
        assertThat(Entities.hasFormatting(List.of(entity("bot_command", 0, 10)))).isFalse();
        assertThat(Entities.hasFormatting(List.of(entity("bot_command", 0, 10), entity("bold", 11, 3))))
                .isTrue();
        assertThat(Entities.hasFormatting(List.of())).isFalse();
        assertThat(Entities.hasFormatting(null)).isFalse();
    }

    /**
     * Ссылки, @-упоминания, хэштеги, почту и телефоны Telegram размечает сам, без участия
     * автора. Если считать это «автор форматировал» — команда с любой ссылкой вылетает из
     * HTML-режима, и первым ломается сам документированный {@code <a href="https://…">}:
     * URL внутри атрибута тоже размечается, и теги уезжают получателям обычным текстом.
     */
    @Test
    void hasFormatting_shouldIgnoreWhatTelegramMarksUpByItself() {
        for (String auto : List.of("url", "mention", "hashtag", "cashtag", "email",
                "phone_number", "bot_command")) {
            assertThat(Entities.hasFormatting(List.of(entity(auto, 0, 5))))
                    .as("%s Telegram проставляет сам — это не разметка автора", auto)
                    .isFalse();
        }
    }

    @Test
    void hasFormatting_shouldCountWhatTakesADeliberateAction() {
        for (String manual : List.of("bold", "italic", "underline", "strikethrough", "spoiler",
                "code", "pre", "blockquote", "expandable_blockquote", "text_link", "text_mention",
                "custom_emoji")) {
            assertThat(Entities.hasFormatting(List.of(entity(manual, 0, 5))))
                    .as("%s без действий автора не появится", manual)
                    .isTrue();
        }
    }

    @Test
    void countCustomEmoji_shouldCountOnlyPremiumOnes() {
        List<MessageEntity> entities = List.of(
                entity("bold", 0, 2),
                MessageEntity.builder().type("custom_emoji").offset(3).length(2).customEmojiId("1").build(),
                MessageEntity.builder().type("custom_emoji").offset(6).length(2).customEmojiId("2").build());

        assertThat(Entities.countCustomEmoji(entities)).isEqualTo(2);
        assertThat(Entities.countCustomEmoji(null)).isZero();
    }

    @Test
    void reanchor_shouldTolerateNullsAndEmptyText() {
        assertThat(Entities.reanchor(null, CUT, 10)).isEmpty();
        assertThat(Entities.reanchor(List.of(entity("bold", 11, 3)), CUT, 0)).isEmpty();
    }

    private static MessageEntity entity(String type, int offset, int length) {
        return MessageEntity.builder().type(type).offset(offset).length(length).build();
    }
}
