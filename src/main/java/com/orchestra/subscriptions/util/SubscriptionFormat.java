package com.orchestra.subscriptions.util;

import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.service.SubscriptionTime;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Рендер подписки в текст — одно место на всех, кто её показывает.
 * <p>
 * Экранов два: админский {@code /subs} и юзерский «Мои подписки». Разъехавшись, они начнут
 * описывать одно и то же состояние разными словами — и разбор жалобы «почему у меня написано
 * так, а у вас эдак» станет невозможным. Поэтому формулировки живут здесь, а экраны только
 * выбирают режим.
 * <p>
 * <b>У припаркованной подписки ссылка не печатается ни в одном режиме.</b> Она мертва до
 * включения: выдать её значило бы отправить человека вставлять в приложение заведомо нерабочий
 * конфиг и потом разбираться, почему «доступ не работает».
 * <p>
 * Чистые функции без Spring — проверяются юнит-тестом без контекста.
 */
public final class SubscriptionFormat {

    private SubscriptionFormat() {}

    /** Кому показываем. Админу нужны id и внутренние поля, человеку — смысл. */
    public enum Mode { ADMIN, USER }

    /**
     * Даёт ли подписка доступ прямо сейчас.
     * <p>
     * Одного {@code is_current} мало: статуса «истекла» в системе нет — при истечении
     * подписка получает {@code DISABLED}, а {@code expires_at} остаётся в прошлом и
     * {@code is_current} остаётся true. Решать по флагу значит писать «работает, до 3 августа»
     * поверх подписки, которая не работает уже неделю, и печатать к ней мёртвую ссылку.
     */
    public static boolean live(Subscription s, LocalDateTime now) {
        return s.isCurrent()
                && s.getState() == Customer.Status.ACTIVE
                && s.getExpiresAt() != null
                && s.getExpiresAt().isAfter(now);
    }

    /**
     * Замороженная подписка с наибольшим остатком, либо {@code null}, если остатка нет нигде.
     * <p>
     * Нужна ровно для одной подсказки, зато самой дорогой: когда работающая подписка отжила
     * своё, а оплаченные дни лежат на замороженной, человек идёт платить второй раз за то, что
     * у него уже есть. Экран обязан назвать бесплатный выход раньше кнопки «Продлить».
     * <p>
     * Пустые подписки сюда не попадают: предложить включить подписку без дней — значит
     * предложить остаться без доступа прямо сейчас.
     */
    public static Subscription richestParked(List<Subscription> subs) {
        return subs.stream()
                .filter(s -> !s.isCurrent())
                .filter(s -> s.getBalanceSeconds() != null && s.getBalanceSeconds() > 0)
                .max(java.util.Comparator.comparingLong(Subscription::getBalanceSeconds))
                .orElse(null);
    }

    /** Значок состояния: работает / не работает / заморожена. */
    public static String stateIcon(Subscription s) {
        if (!s.isCurrent()) {
            return "⏸";
        }
        return live(s, LocalDateTime.now()) ? "▶️" : "⚠️";
    }

    /**
     * Одна подписка одной-двумя строками.
     *
     * @param tierName человекочитаемое имя ступени («2 устройства»); {@code null} — покажем id
     *                 ступени в админском режиме и промолчим в юзерском
     */
    public static String line(Subscription s, Mode mode, String tierName) {
        StringBuilder sb = new StringBuilder();
        sb.append(stateIcon(s)).append(' ').append("<b>#").append(s.getSeq()).append("</b>");

        String tier = tierName != null ? tierName
                : (mode == Mode.ADMIN ? s.getDeviceTierId() : null);
        if (tier != null) {
            sb.append(" · ").append(HtmlUtils.htmlEscape(tier, "UTF-8"));
        }
        if (mode == Mode.ADMIN && s.getOrigin() != null) {
            sb.append(" · ").append(s.getOrigin());
        }
        sb.append('\n');

        if (s.isCurrent()) {
            String date = s.getExpiresAt() == null ? "—" : TimeFormat.mskDate(s.getExpiresAt());
            if (mode == Mode.ADMIN) {
                sb.append("   до ").append(date);
            } else if (live(s, LocalDateTime.now())) {
                sb.append("   действует до ").append(date);
            } else {
                // Не «действует»: у этой подписки срок вышел или её отключили.
                sb.append("   не действует").append(s.getExpiresAt() == null ? "" : " · истекла " + date);
            }
        } else {
            long days = SubscriptionTime.displayDays(
                    s.getBalanceSeconds() == null ? 0L : s.getBalanceSeconds());
            sb.append("   ").append(mode == Mode.ADMIN
                    ? "заморожено: " + days + " дн."
                    // Человеку важно не «сколько лежит», а что срок стоит, пока подписка
                    // заморожена: именно этого опасаются, когда видят слово «заморожена».
                    : "заморожена · остаток " + days + " дн., срок не расходуется");
        }
        if (mode == Mode.ADMIN) {
            sb.append(" · ").append(s.getState() == null ? "—" : s.getState());
            if (s.getDeviceLimit() != null) {
                sb.append(" · устройств ").append(s.getDeviceLimit());
            }
        } else if (s.getDeviceLimit() != null) {
            sb.append("\n   устройств: ").append(s.getDeviceLimit());
        }
        return sb.append('\n').toString();
    }

    /** Полный рендер админского {@code /subs}. */
    public static String adminList(Customer user, List<Subscription> subs) {
        String tag = user.getTelegramUsername() != null && !user.getTelegramUsername().isBlank()
                ? "@" + HtmlUtils.htmlEscape(user.getTelegramUsername(), "UTF-8")
                : "id=" + user.getTelegramId();
        StringBuilder sb = new StringBuilder("📋 <b>Подписки</b> — ").append(tag).append("\n\n");
        if (subs.isEmpty()) {
            sb.append("<i>Зеркальных строк нет. Либо зеркалирование выключено, либо перенос "
                    + "ещё не отработал — запустите /subcheck.</i>");
            return sb.toString();
        }
        for (Subscription s : subs) {
            sb.append(line(s, Mode.ADMIN, null));
            sb.append("   id=").append(s.getId()).append('\n');
        }
        return sb.toString();
    }
}
