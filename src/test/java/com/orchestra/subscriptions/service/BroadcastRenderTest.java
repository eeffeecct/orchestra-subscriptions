package com.orchestra.subscriptions.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Во что превращается черновик перед отправкой. Проверяется без Spring: это чистый рендер,
 * и именно он решает, доедут ли premium-эмодзи.
 */
class BroadcastRenderTest {

    private static final MessageEntity PREMIUM = MessageEntity.builder()
            .type("custom_emoji").offset(0).length(2).customEmojiId("5000000000000000001").build();

    @Test
    void messageFor_withoutTelegramFormatting_shouldParseAsHtml() {
        SendMessage msg = BroadcastService.messageFor("42",
                BroadcastService.BroadcastContent.html("Завтра <b>обновление</b>"));

        assertThat(msg.getChatId()).isEqualTo("42");
        assertThat(msg.getParseMode()).isEqualTo("HTML");
        assertThat(msg.getEntities()).isNull();
    }

    /**
     * Сущности и parse_mode вместе Telegram не принимает, и это не мелочь: включённый HTML
     * заставил бы разбирать текст заново, а любая угловая скобка, набранная как обычный
     * символ, уронила бы отправку каждому получателю.
     */
    @Test
    void messageFor_withEntities_shouldSendThemInsteadOfParseMode() {
        SendMessage msg = BroadcastService.messageFor("42",
                new BroadcastService.BroadcastContent("🎉 скидка", List.of(PREMIUM), null, false));

        assertThat(msg.getParseMode()).isNull();
        assertThat(msg.getEntities()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("custom_emoji");
            assertThat(e.getCustomEmojiId()).isEqualTo("5000000000000000001");
        });
    }

    @Test
    void photoFor_shouldSendFileIdWithCaptionEntities() {
        SendPhoto photo = BroadcastService.photoFor("42",
                new BroadcastService.BroadcastContent("🎉 скидка", List.of(PREMIUM), "AgACfile", false));

        assertThat(photo.getChatId()).isEqualTo("42");
        assertThat(photo.getPhoto().getAttachName()).isEqualTo("AgACfile");
        assertThat(photo.getCaption()).isEqualTo("🎉 скидка");
        assertThat(photo.getParseMode()).isNull();
        assertThat(photo.getCaptionEntities()).singleElement().satisfies(e ->
                assertThat(e.getCustomEmojiId()).isEqualTo("5000000000000000001"));
    }

    @Test
    void photoFor_withHtmlCaption_shouldSetParseMode() {
        SendPhoto photo = BroadcastService.photoFor("42",
                new BroadcastService.BroadcastContent("<b>жирно</b>", List.of(), "AgACfile", true));

        assertThat(photo.getParseMode()).isEqualTo("HTML");
        assertThat(photo.getCaptionEntities()).isNull();
    }

    /** Картинка без подписи — не пустая подпись, а её отсутствие. */
    @Test
    void photoFor_withoutCaption_shouldLeaveCaptionUnset() {
        SendPhoto photo = BroadcastService.photoFor("42",
                new BroadcastService.BroadcastContent("", List.of(), "AgACfile", true));

        assertThat(photo.getCaption()).isNull();
        assertThat(photo.getParseMode()).isNull();
    }

    /** HTML-режим сущности не хранит: иначе они молча ехали бы мимо parse_mode. */
    @Test
    void content_htmlMode_shouldDropEntities() {
        var content = new BroadcastService.BroadcastContent("🎉", List.of(PREMIUM), null, true);

        assertThat(content.entities()).isEmpty();
        assertThat(content.usesEntities()).isFalse();
    }
}
