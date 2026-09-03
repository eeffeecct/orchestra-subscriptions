package com.orchestra.subscriptions.util;

import com.orchestra.subscriptions.config.AppConfig;

import java.util.Map;

/**
 * Рендер «анимированных» Telegram-эмодзи (custom emoji) с graceful-фолбэком.
 *
 * <p>Бот может вставлять анимированные premium-эмодзи прямо в текст сообщения через
 * HTML-тег {@code <tg-emoji emoji-id="...">🙂</tg-emoji>} — НО только если у аккаунта
 * <b>владельца</b> бота есть Telegram Premium. У зрителей без Premium (и в клиентах,
 * которые их не умеют рисовать) показывается обычный эмодзи внутри тега — это и есть
 * естественный фолбэк.
 *
 * <p>Два уровня деградации, чтобы бот выглядел прилично в любой конфигурации:
 * <ol>
 *   <li>{@code app.emoji.enabled=false} (дефолт) → всегда обычный unicode-эмодзи,
 *       никаких {@code <tg-emoji>} в сообщениях.</li>
 *   <li>{@code enabled=true}, но для ключа не задан id в {@code app.emoji.ids} →
 *       тоже обычный unicode.</li>
 * </ol>
 * Так бот работает «из коробки» без Premium и без настройки, а когда владелец заполнит
 * {@code app.emoji.ids} (semantic-ключ → custom_emoji_id), сообщения «оживают» сами —
 * править код не нужно.
 *
 * <p><b>ВАЖНО:</b> {@code <tg-emoji>} работает только с {@code parse_mode=HTML} и только
 * когда внутри тега ровно один эмодзи. Все сообщения, использующие {@link Emoji}, обязаны
 * слаться как HTML.
 *
 * <p>Где взять {@code custom_emoji_id}: owner-команда {@code /emojiid} — переслать боту
 * сообщение с premium-эмодзи, он ответит их id.
 */
public final class Emoji {

    /**
     * Дефолтные unicode-фолбэки по semantic-ключам. Ключи — это же имена, которые владелец
     * заполняет в {@code app.emoji.ids}. Менять/добавлять ключи можно свободно: код сообщений
     * зовёт {@link #e(String)} по ключу, а конкретный значок (обычный или анимированный)
     * решается здесь и в конфиге.
     */
    private static final Map<String, String> FALLBACK = Map.ofEntries(
            Map.entry("dolphin", "🐬"),
            Map.entry("lock", "🔐"),
            Map.entry("key", "🔑"),
            Map.entry("planet", "🪐"),
            Map.entry("gift", "🎁"),
            Map.entry("rocket", "🚀"),
            Map.entry("chart", "📊"),
            Map.entry("question", "❓"),
            Map.entry("info", "❔"),
            Map.entry("news", "🔈"),
            Map.entry("ru", "🇷🇺"),
            Map.entry("bag", "🛍"),
            Map.entry("link", "🔗"),
            Map.entry("money", "💸"),
            Map.entry("card", "💳"),
            Map.entry("crypto", "💸"),
            Map.entry("coins", "💰"),
            Map.entry("star", "⭐️"),
            Map.entry("crown", "👑"),
            Map.entry("bolt", "⚡️"),
            Map.entry("hourglass", "⌛️"),
            Map.entry("down", "⬇️"),
            Map.entry("check", "✅"),
            Map.entry("cross", "❌"),
            Map.entry("plus", "➕"),
            Map.entry("pause", "⏸"),
            Map.entry("refresh", "🔄"),
            Map.entry("phone", "📱"),
            Map.entry("person", "👤"),
            Map.entry("people", "👥"),
            Map.entry("scroll", "📜"),
            Map.entry("receipt", "🧾"),
            Map.entry("book", "📖"),
            Map.entry("mail", "✉️"),
            Map.entry("recycle", "🔁"),
            Map.entry("sos", "🆘"),
            Map.entry("wave", "👋"),
            Map.entry("warn", "⚠️"),
            Map.entry("bang", "❕"),
            Map.entry("notice", "ℹ️"),
            Map.entry("urgent", "🔴"),
            Map.entry("growth", "📈"),
            Map.entry("back", "⬅️"),
            Map.entry("sparkles", "✨"),
            Map.entry("calendar", "📅"),
            Map.entry("shield", "🛡"),
            Map.entry("party", "🎉"),
            Map.entry("devices", "📱"),
            Map.entry("family", "👨‍👩‍👧‍👦"),
            Map.entry("ticket", "🎟")
    );

    private final AppConfig.EmojiConfig cfg;

    public Emoji(AppConfig.EmojiConfig cfg) {
        this.cfg = cfg;
    }

    /** Эмодзи по ключу с дефолтным unicode-фолбэком из {@link #FALLBACK}. */
    public String e(String key) {
        return e(key, FALLBACK.getOrDefault(key, ""));
    }

    /**
     * Сырой {@code custom_emoji_id} для ключа, или {@code null} (фича выключена / id не задан).
     * Нужен для {@code icon_custom_emoji_id} на кнопках (Bot API 9.4), где идёт не текст-тег,
     * а голый id. См. {@code IconInlineButton}.
     */
    public Long id(String key) {
        return idsEnabled() ? cfg.getIds().get(key) : null;
    }

    /**
     * Эмодзи по ключу с явным фолбэком (на случай ключа, которого нет в {@link #FALLBACK}).
     * Возвращает {@code <tg-emoji>} только если фича включена И для ключа задан id, иначе
     * сам фолбэк.
     */
    public String e(String key, String fallback) {
        if (idsEnabled()) {
            Long id = cfg.getIds().get(key);
            if (id != null) {
                return "<tg-emoji emoji-id=\"" + id + "\">" + fallback + "</tg-emoji>";
            }
        }
        return fallback;
    }

    /** true, если custom-эмодзи включены и карта id задана. */
    private boolean idsEnabled() {
        return cfg != null && cfg.isEnabled() && cfg.getIds() != null;
    }
}
