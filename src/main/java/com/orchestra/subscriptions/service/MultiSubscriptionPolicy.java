package com.orchestra.subscriptions.service;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Единственная точка чтения флагов «нескольких подписок». Всё остальное обязано спрашивать
 * здесь, а не читать свойства напрямую: иначе выключение фичи перестало бы быть одним
 * действием, а именно ради этого флаги и заводились.
 * <p>
 * <b>Флаги проверяются только при СОЗДАНИИ платежа, никогда при активации.</b> Если владелец
 * выключил флаг, пока висит оплаченный, но ещё не активированный платёж, — платёж обязан
 * активироваться: деньги уже приняты, и запирать их флагом нельзя.
 * <p>
 * <b>Флаг не гейтит доступ к уже существующим подпискам.</b> Список, переключение и оплата в
 * существующую замороженную подписку работают независимо от {@code enabled} — у человека там
 * могут лежать замороженные деньги.
 * <p>
 * Флаги НЕ входят в горячий набор {@code /reloadconfig} (там только ноды, подписочные строки
 * и routing). Смена — правка конфига и рестарт: money-path не должен меняться командой в чате.
 * <p>
 * Связывание через {@code @ConfigurationProperties}, а не {@code @Value}: список
 * {@code allowed-telegram-ids} задаётся в YAML списком, а {@code @Value} такой формат не
 * читает и молча отдал бы пустоту — то есть «включено, но никому», что выглядело бы как
 * поломка allow-list'а.
 */
@lombok.extern.slf4j.Slf4j
@Component
@Setter
@ConfigurationProperties(prefix = "app.multi-subscription")
public class MultiSubscriptionPolicy {

    /** Разрешено ли СОЗДАВАТЬ вторую подписку. */
    private boolean enabled = false;

    /** Кому именно разрешено. Пусто = никому, даже при {@code enabled: true}. */
    private List<Long> allowedTelegramIds = List.of();

    /**
     * Открыть фичу ВСЕМ, а не только списку.
     * <p>
     * Отдельный флаг, а НЕ трактовка «пустой список = все». Пустой список сегодня значит
     * «никому», и переворачивать смысл существующей настройки нельзя: тогда затёртая или
     * забытая переменная молча открывала бы money-path всему свету. Открытие на всех —
     * осознанное действие, и в конфиге оно должно выглядеть осознанным.
     * <p>
     * {@code enabled: false} перекрывает и этот флаг: выключатель остаётся один.
     */
    private boolean allowAll = false;

    /**
     * Аварийный тормоз на выполнение переключения. Кнопку в интерфейсе НЕ прячет: молча
     * убрать её у человека с замороженными деньгами хуже, чем честно ответить «временно
     * недоступно».
     */
    private boolean switchingEnabled = true;

    /** Потолок числа подписок на человека. */
    private int maxPerUser = 3;

    /** Потолок переключений в сутки — защита от раскачки состояния панели. */
    private int maxSwitchesPerDay = 10;

    /** Можно ли этому человеку завести ещё одну подписку. */
    public boolean canOpenNew(long telegramId) {
        if (!enabled) {
            return false;
        }
        if (allowAll) {
            return true;
        }
        return allowedTelegramIds != null && allowedTelegramIds.contains(telegramId);
    }

    /**
     * Печатает действующую политику в лог при старте.
     * <p>
     * Не косметика: actuator в проде выключен, и убедиться, что переменная окружения
     * действительно связалась, было НЕЧЕМ — приложение с непривязавшимся флагом стартует
     * молча и ведёт себя ровно как с выключенным. Проверка сводилась к «пойти купить другой
     * тариф и посмотреть, появится ли экран-предупреждение». Одна строка в логе закрывает
     * этот пробел: после каждого деплоя видно, что именно включено.
     */
    @jakarta.annotation.PostConstruct
    void logEffectivePolicy() {
        log.info("Несколько подписок: enabled={}, всем={}, в списке={}, потолок на человека={}, "
                        + "переключений в сутки={}, переключение разрешено={}",
                enabled, allowAll,
                allowedTelegramIds == null ? 0 : allowedTelegramIds.size(),
                maxPerUser, maxSwitchesPerDay, switchingEnabled);
    }

    public boolean switchingEnabled() {
        return switchingEnabled;
    }

    public int maxPerUser() {
        return maxPerUser;
    }

    public int maxSwitchesPerDay() {
        return maxSwitchesPerDay;
    }
}
