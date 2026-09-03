package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted;
import org.telegram.telegrambots.meta.api.objects.chatmember.MemberStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состоит ли человек в канале, подписка на который обязательна для входа в бот
 * ({@code app.telegram.required-channel}). Единственный источник правды для
 * {@link ChannelGateHandler}.
 *
 * <p><b>Проверка требует прав администратора.</b> {@code getChatMember} по каналу Bot API
 * отдаёт только администратору канала. Если бота из админов убрали — каждый вызов будет
 * падать; тогда работает <b>fail-open</b>: пропускаем всех и пишем warn. Обратный выбор
 * (fail-closed) означал бы, что снятие бота с админки канала запирает вход в бот целиком,
 * включая оплаченные подписки, — цена ошибки несопоставима с ценой временно открытого гейта.
 * Признак в логах: {@code Channel membership check failed}.
 *
 * <p><b>Кэш — только положительные вердикты.</b> Отказ не кэшируется никогда: человек
 * подписывается и жмёт «Проверить» в ту же секунду, и повторный отказ из кэша выглядел бы
 * поломкой. Обратная сторона TTL: отписавшегося гейт заметит не мгновенно, а в пределах
 * {@link #ALLOWED_TTL} — это осознанный размен на один лишний round-trip к Telegram
 * на каждое сообщение.
 *
 * <p>Стейт in-memory, как у {@link PendingInputService}: инстанс приложения один, а рестарт
 * просто сбрасывает кэш (следующая проверка сходит в Telegram).
 */
@Slf4j
@Component
public class ChannelMembershipService {

    /** Сколько подтверждённое членство живёт без перепроверки. */
    private static final Duration ALLOWED_TTL = Duration.ofMinutes(10);
    /**
     * Сколько живёт пропуск, выданный по ошибке проверки (fail-open). Короче обычного:
     * канал/права могут вернуться, и держать открытым вход 10 минут из-за одного сетевого
     * сбоя незачем.
     */
    private static final Duration FAIL_OPEN_TTL = Duration.ofMinutes(1);

    private final AppConfig appConfig;
    private final TelegramSender telegramSender;

    /** telegramId → до какого момента вход считается открытым. Только «разрешено». */
    private final Map<Long, Instant> allowedUntil = new ConcurrentHashMap<>();

    public ChannelMembershipService(AppConfig appConfig, TelegramSender telegramSender) {
        this.appConfig = appConfig;
        this.telegramSender = telegramSender;
    }

    /** Включён ли гейт вообще (задан ли канал в конфиге). */
    public boolean required() {
        String channel = channel();
        return channel != null && !channel.isBlank();
    }

    /** {@code @username} или числовой id канала из конфига. */
    public String channel() {
        return appConfig.getTelegram().getRequiredChannel();
    }

    /**
     * Можно ли пускать в бот. Отвечает из кэша, если членство подтверждали недавно,
     * иначе спрашивает Telegram. Гейт выключен — всегда {@code true}.
     */
    public boolean isMember(Long telegramId) {
        if (!required()) {
            return true;
        }
        Instant until = allowedUntil.get(telegramId);
        if (until != null && Instant.now().isBefore(until)) {
            return true;
        }
        return isMemberFresh(telegramId);
    }

    /**
     * То же, но обязательно спросив Telegram — для кнопки «Проверить»: человек только что
     * подписался, и ответ из кэша («ещё не подписан») был бы враньём.
     */
    public boolean isMemberFresh(Long telegramId) {
        if (!required()) {
            return true;
        }
        allowedUntil.remove(telegramId);

        boolean allowed;
        Duration ttl;
        try {
            ChatMember member = telegramSender.execute(GetChatMember.builder()
                    .chatId(channel())
                    .userId(telegramId)
                    .build());
            allowed = joined(member);
            ttl = ALLOWED_TTL;
        } catch (Exception e) {
            // Чаще всего — бота сняли с админов канала или канал переименовали.
            // Пропускаем (см. javadoc класса) и шумим в лог: молча закрывшийся гейт
            // владелец бы не заметил, а молча открытый — тем более.
            log.warn("Channel membership check failed for telegramId={} channel={}: {}",
                    telegramId, channel(), e.getMessage());
            allowed = true;
            ttl = FAIL_OPEN_TTL;
        }

        if (allowed) {
            allowedUntil.put(telegramId, Instant.now().plus(ttl));
        }
        return allowed;
    }

    /**
     * Забыть подтверждение: следующая проверка обязательно сходит в Telegram.
     * Нужен там, где мы узнали об изменении раньше кэша.
     */
    public void forget(Long telegramId) {
        allowedUntil.remove(telegramId);
    }

    /**
     * Считается ли этот статус «человек в канале». {@code restricted} у каналов не
     * встречается, но формально это всё ещё участник, пока {@code is_member=true} —
     * разбираем и его, чтобы не выгнать подписчика из-за экзотики.
     * Неизвестный статус трактуем как «не подписан»: список статусов задаёт Telegram,
     * и молча пропускать что-то новое нельзя.
     */
    private static boolean joined(ChatMember member) {
        if (member == null || member.getStatus() == null) {
            return false;
        }
        return switch (member.getStatus()) {
            case MemberStatus.CREATOR, MemberStatus.ADMINISTRATOR, MemberStatus.MEMBER -> true;
            case "restricted" -> member instanceof ChatMemberRestricted restricted
                    && Boolean.TRUE.equals(restricted.getIsMember());
            default -> false;
        };
    }

    /** Уборка протухших записей — тот же паттерн, что у {@link PendingInputService}. */
    @Scheduled(fixedDelay = 600_000L)
    void sweepExpired() {
        Instant now = Instant.now();
        allowedUntil.entrySet().removeIf(e -> now.isAfter(e.getValue()));
    }
}
