package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.service.BroadcastService;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор сообщения владельца в содержимое рассылки: что срезается, куда уезжает разметка и
 * когда текст ещё считается HTML-ом.
 *
 * <p>Ключевой инвариант — premium-эмодзи переживает срезание команды. В тексте он выглядит
 * обычным глифом, «анимированность» держится отдельной сущностью со смещением; ошибись в
 * смещении на символ — и получатели увидят обычную картинку вместо анимированной, причём
 * узнать об этом можно будет только после рассылки.
 */
class BroadcastComposeTest {

    @Test
    void compose_shouldStripCommandAndTrimTail() {
        BroadcastService.BroadcastContent content =
                AdminCommandHandler.compose("/broadcast   Завтра обновление   ", null, 1, null);

        assertThat(content.text()).isEqualTo("Завтра обновление");
        assertThat(content.html()).isTrue();
        assertThat(content.hasPhoto()).isFalse();
    }

    /** Исторический контракт команды: теги набираются руками и обязаны продолжать работать. */
    @Test
    void compose_shouldStayHtmlWhenOnlyTheCommandIsHighlighted() {
        BroadcastService.BroadcastContent content = AdminCommandHandler.compose(
                "/broadcast Завтра <b>обновление</b>",
                List.of(entity("bot_command", 0, 10)), 1, null);

        assertThat(content.html()).isTrue();
        assertThat(content.entities()).isEmpty();
        assertThat(content.text()).isEqualTo("Завтра <b>обновление</b>");
    }

    /**
     * Регрессия: ссылку Telegram размечает сам, и если считать это форматированием автора, то
     * ровно документированный {@code <a href="https://…">} перестаёт работать — URL внутри
     * атрибута выключает HTML, и теги уходят получателям обычным текстом.
     */
    @Test
    void compose_shouldStayHtmlWhenTelegramItselfMarkedUpALink() {
        String raw = "/broadcast Читайте <a href=\"https://t.me/orchestrav_pn_bot\">тут</a>";
        List<MessageEntity> raws = List.of(
                entity("bot_command", 0, 10),
                entity("url", raw.indexOf("https://"), "https://t.me/orchestrav_pn_bot".length()));

        BroadcastService.BroadcastContent content = AdminCommandHandler.compose(raw, raws, 1, null);

        assertThat(content.html()).isTrue();
        assertThat(content.entities()).isEmpty();
    }

    @Test
    void compose_shouldStayHtmlForMentionsAndHashtags() {
        String raw = "/broadcast Пишите @support #новости";
        List<MessageEntity> raws = List.of(
                entity("bot_command", 0, 10),
                entity("mention", raw.indexOf("@support"), 8),
                entity("hashtag", raw.indexOf("#новости"), 8));

        assertThat(AdminCommandHandler.compose(raw, raws, 1, null).html()).isTrue();
    }

    /** А вот premium-эмодзи рядом со ссылкой режим переключает — и ссылка едет сущностью. */
    @Test
    void compose_premiumEmojiNextToALink_shouldSwitchToEntitiesAndKeepBoth() {
        String raw = "/broadcast 🎉 https://t.me/orchestrav_pn_bot";
        List<MessageEntity> raws = List.of(
                entity("bot_command", 0, 10),
                MessageEntity.builder().type("custom_emoji")
                        .offset(raw.indexOf("🎉")).length(2).customEmojiId("42").build(),
                entity("url", raw.indexOf("https://"), "https://t.me/orchestrav_pn_bot".length()));

        BroadcastService.BroadcastContent content = AdminCommandHandler.compose(raw, raws, 1, null);

        assertThat(content.html()).isFalse();
        assertThat(content.entities()).extracting(MessageEntity::getType)
                .containsExactlyInAnyOrder("custom_emoji", "url");
    }

    @Test
    void compose_shouldCarryPremiumEmojiOntoTheStrippedText() {
        String raw = "/broadcast Скидка 🎉 на всё";
        int emojiAt = raw.indexOf("🎉");
        List<MessageEntity> raws = List.of(
                entity("bot_command", 0, 10),
                MessageEntity.builder().type("custom_emoji")
                        .offset(emojiAt).length(2).customEmojiId("5000000000000000001").build());

        BroadcastService.BroadcastContent content = AdminCommandHandler.compose(raw, raws, 1, null);

        assertThat(content.html()).as("разметка уже собрана клиентом — HTML выключаем").isFalse();
        assertThat(content.usesEntities()).isTrue();
        assertThat(content.customEmojiCount()).isEqualTo(1);
        MessageEntity moved = content.entities().stream()
                .filter(e -> "custom_emoji".equals(e.getType())).findFirst().orElseThrow();
        assertThat(content.text().substring(moved.getOffset(), moved.getOffset() + moved.getLength()))
                .as("сущность обязана указывать ровно на тот же глиф")
                .isEqualTo("🎉");
        assertThat(moved.getCustomEmojiId()).isEqualTo("5000000000000000001");
    }

    @Test
    void compose_shouldKeepBoldAlignedAfterStrippingCommand() {
        String raw = "/broadcast Завтра обновление";
        List<MessageEntity> raws = List.of(
                entity("bot_command", 0, 10),
                entity("bold", raw.indexOf("обновление"), "обновление".length()));

        BroadcastService.BroadcastContent content = AdminCommandHandler.compose(raw, raws, 1, null);

        MessageEntity bold = content.entities().stream()
                .filter(e -> "bold".equals(e.getType())).findFirst().orElseThrow();
        assertThat(content.text().substring(bold.getOffset(), bold.getOffset() + bold.getLength()))
                .isEqualTo("обновление");
    }

    /** {@code /broadcast_to <id> <текст>} — срезать надо два слова, а не одно. */
    @Test
    void compose_shouldStripRecipientIdForBroadcastTo() {
        String raw = "/broadcast_to 12345 Проверка 🎉";
        List<MessageEntity> raws = List.of(
                entity("bot_command", 0, 13),
                MessageEntity.builder().type("custom_emoji")
                        .offset(raw.indexOf("🎉")).length(2).customEmojiId("42").build());

        BroadcastService.BroadcastContent content = AdminCommandHandler.compose(raw, raws, 2, null);

        assertThat(content.text()).isEqualTo("Проверка 🎉");
        MessageEntity moved = content.entities().getFirst();
        assertThat(content.text().substring(moved.getOffset(), moved.getOffset() + moved.getLength()))
                .isEqualTo("🎉");
    }

    @Test
    void compose_shouldAllowPhotoWithoutCaption() {
        BroadcastService.BroadcastContent content =
                AdminCommandHandler.compose("/broadcast", List.of(entity("bot_command", 0, 10)), 1, "photo-1");

        assertThat(content.text()).isEmpty();
        assertThat(content.hasPhoto()).isTrue();
        assertThat(content.isEmpty()).as("картинка сама по себе — уже рассылка").isFalse();
    }

    @Test
    void compose_withoutTextAndPhoto_shouldBeEmpty() {
        assertThat(AdminCommandHandler.compose("/broadcast   ", null, 1, null).isEmpty()).isTrue();
        assertThat(AdminCommandHandler.compose(null, null, 1, null).isEmpty()).isTrue();
    }

    /** Теги до получателя не доезжают — считать их в лимит значит отказывать зря. */
    @Test
    void tooLong_shouldNotCountHtmlTags() {
        String caption = "<b>" + "я".repeat(1020) + "</b>";

        BroadcastService.BroadcastContent content =
                new BroadcastService.BroadcastContent(caption, List.of(), "photo-1", true);

        assertThat(caption.length()).as("сырых символов больше лимита").isGreaterThan(1024);
        assertThat(content.visibleLength()).isEqualTo(1020);
        assertThat(content.tooLong()).isFalse();
    }

    /** В режиме сущностей считать нечего — что видим, то Telegram и посчитает. */
    @Test
    void visibleLength_inEntitiesMode_shouldBePlainTextLength() {
        BroadcastService.BroadcastContent content = new BroadcastService.BroadcastContent(
                "<b>не тег</b>", List.of(entity("bold", 0, 3)), null, false);

        assertThat(content.visibleLength()).isEqualTo("<b>не тег</b>".length());
    }

    /** Подпись к фото короче обычного сообщения вчетверо — это чужой лимит, легко не заметить. */
    @Test
    void tooLong_shouldUseCaptionLimitForPhoto() {
        String text = "я".repeat(2000);

        assertThat(new BroadcastService.BroadcastContent(text, List.of(), null, true).tooLong())
                .as("2000 символов текстом — норма").isFalse();
        assertThat(new BroadcastService.BroadcastContent(text, List.of(), "photo-1", true).tooLong())
                .as("те же 2000 подписью к фото — уже нет").isTrue();
    }

    // ========================
    // ответ командой на чужое сообщение
    // ========================

    /** Картинка из сообщения, на которое отвечают, плюс свой текст команды. */
    @Test
    void broadcastContent_replyToPhoto_shouldTakePhotoAndKeepOwnText() {
        Message reply = new Message();
        reply.setPhoto(List.of(photoSize("file-small", 90), photoSize("file-big", 1280)));
        reply.setCaption("старая подпись");
        Message command = new Message();
        command.setText("/broadcast новая подпись");
        command.setReplyToMessage(reply);

        BroadcastService.BroadcastContent content =
                AdminCommandHandler.broadcastContent(command, "новая подпись", 1);

        assertThat(content.photoFileId()).isEqualTo("file-big");
        assertThat(content.text()).isEqualTo("новая подпись");
    }

    /** Голая команда ответом — рассылаем то сообщение целиком, вместе с его разметкой. */
    @Test
    void broadcastContent_bareReply_shouldTakeRepliedCaptionWithEntities() {
        String caption = "Скидка 🎉 на всё";
        Message reply = new Message();
        reply.setPhoto(List.of(photoSize("file-big", 1280)));
        reply.setCaption(caption);
        reply.setCaptionEntities(List.of(MessageEntity.builder().type("custom_emoji")
                .offset(caption.indexOf("🎉")).length(2).customEmojiId("42").build()));
        Message command = new Message();
        command.setText("/broadcast");
        command.setReplyToMessage(reply);

        BroadcastService.BroadcastContent content =
                AdminCommandHandler.broadcastContent(command, "", 1);

        assertThat(content.text()).isEqualTo(caption);
        assertThat(content.photoFileId()).isEqualTo("file-big");
        assertThat(content.html()).isFalse();
        assertThat(content.customEmojiCount()).isEqualTo(1);
        MessageEntity premium = content.entities().getFirst();
        assertThat(content.text().substring(premium.getOffset(),
                premium.getOffset() + premium.getLength())).isEqualTo("🎉");
    }

    /** Ответ на текстовое сообщение без картинки — тоже рассылка, просто без фото. */
    @Test
    void broadcastContent_bareReplyToText_shouldTakeItsText() {
        Message reply = new Message();
        reply.setText("Уже написанный анонс");
        Message command = new Message();
        command.setText("/broadcast");
        command.setReplyToMessage(reply);

        BroadcastService.BroadcastContent content =
                AdminCommandHandler.broadcastContent(command, "", 1);

        assertThat(content.text()).isEqualTo("Уже написанный анонс");
        assertThat(content.hasPhoto()).isFalse();
    }

    /**
     * Чужое сообщение уходит как есть, а не как разметка для бота. Иначе «цена &lt; 100» либо
     * поедет с испорченным смыслом, либо не отправится вовсе: его писали как текст.
     */
    @Test
    void broadcastContent_bareReply_shouldNeverReinterpretTextAsHtml() {
        Message reply = new Message();
        reply.setText("Цена <100 рублей за месяц");
        Message command = new Message();
        command.setText("/broadcast");
        command.setReplyToMessage(reply);

        BroadcastService.BroadcastContent content =
                AdminCommandHandler.broadcastContent(command, "", 1);

        assertThat(content.html()).isFalse();
        assertThat(content.text()).isEqualTo("Цена <100 рублей за месяц");
    }

    /** А свой текст рядом с командой — по-прежнему HTML: его набирали именно для бота. */
    @Test
    void broadcastContent_replyWithOwnText_shouldStayHtml() {
        Message reply = new Message();
        reply.setPhoto(List.of(photoSize("file-big", 1280)));
        Message command = new Message();
        command.setText("/broadcast <b>Смотрите</b>");
        command.setReplyToMessage(reply);

        BroadcastService.BroadcastContent content =
                AdminCommandHandler.broadcastContent(command, "<b>Смотрите</b>", 1);

        assertThat(content.html()).isTrue();
        assertThat(content.photoFileId()).isEqualTo("file-big");
    }

    /** Команда в подписи к фото важнее ответа: рассылаем присланную картинку, а не чужую. */
    @Test
    void broadcastContent_captionCommand_shouldWinOverReply() {
        Message reply = new Message();
        reply.setPhoto(List.of(photoSize("file-from-reply", 1280)));
        Message command = new Message();
        command.setPhoto(List.of(photoSize("file-attached", 1280)));
        command.setCaption("/broadcast Смотрите");
        command.setReplyToMessage(reply);

        BroadcastService.BroadcastContent content =
                AdminCommandHandler.broadcastContent(command, "Смотрите", 1);

        assertThat(content.photoFileId()).isEqualTo("file-attached");
        assertThat(content.text()).isEqualTo("Смотрите");
    }

    /** Без исходного сообщения (внутренний вызов) остаётся голый текст в HTML-режиме. */
    @Test
    void broadcastContent_withoutSourceMessage_shouldFallBackToHtmlText() {
        BroadcastService.BroadcastContent content =
                AdminCommandHandler.broadcastContent(null, "  Просто текст  ", 1);

        assertThat(content.text()).isEqualTo("Просто текст");
        assertThat(content.html()).isTrue();
        assertThat(content.hasPhoto()).isFalse();
    }

    private static PhotoSize photoSize(String id, int side) {
        return PhotoSize.builder().fileId(id).fileUniqueId(id).width(side).height(side).build();
    }

    private static MessageEntity entity(String type, int offset, int length) {
        return MessageEntity.builder().type(type).offset(offset).length(length).build();
    }
}
