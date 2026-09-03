package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Subscription;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Что произойдёт при следующем автосписании — одним ответом на все экраны.
 * <p>
 * Экранов три: {@code /autorenew} в боте, экран автопродления в Mini App и список подписок.
 * До этого класса каждый считал ответ сам, и считали они РАЗНОЕ: бот показывал тариф и сумму,
 * Mini App — только слово «включено», а список подписок вообще молчал о том, какую из них
 * продлят. Человек с двумя подписками не мог узнать, за что спишут деньги, ни на одном экране.
 * <p>
 * <b>Продлевается всегда РАБОТАЮЩАЯ подписка</b> — {@code AutoRenewService.chargeAndActivate}
 * заводит платёж через {@code createIntoCurrent}. Замороженные не продлеваются никогда: карта
 * и настройки автопродления принадлежат человеку, а работает у него одна подписка.
 * <p>
 * <b>Ступень берётся у продлеваемой подписки</b> — ровно тем же способом, что и в
 * {@link AutoRenewService#chargeAndActivate}. Считать её здесь по-своему нельзя: экран,
 * называющий сумму не по той ступени, по которой спишут, — это обещание, которое сервис не
 * выполнит, а обнаружится оно списанием другой суммы.
 */
@Component
@RequiredArgsConstructor
public class AutoRenewInfoService {

    private final AppConfig appConfig;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * Снимок автопродления для экранов.
     *
     * @param enabled        человек не выключал автопродление
     * @param cardBound      карта привязана; без неё списывать нечем
     * @param shopEnabled    магазин умеет рекуррентные списания; при выключенном флаге
     *                       планировщик не делает ни одной попытки, и обещать продление нельзя
     * @param planId         тариф, который продлевают; {@code null} — автопродления не было
     * @param planName       имя тарифа; {@code null} при известном {@code planId} значит, что
     *                       тариф сняли с продажи и следующее списание отключит автопродление
     * @param tierId         id ступени продлеваемой подписки — её и спишут
     * @param tierName       её человекочитаемое имя; {@code null} — ступени нет (legacy-оплата),
     *                       тогда списывается базовая цена плана, а лимит устройств не меняется
     * @param amountRub      сумма списания; {@code 0}, когда считать не из чего
     * @param nextChargeAt   когда спишут; {@code null} — выключено или срока нет
     * @param targetSeq      номер подписки, которую продлят; {@code null} — работающей нет
     * @param lastFailureAt  когда последняя попытка списания не удалась
     * @param subscriptionCount сколько всего подписок у человека — от этого зависит, нужно ли
     *                       вообще объяснять, какая из них продлевается
     */
    public record Info(boolean enabled,
                       boolean cardBound,
                       boolean shopEnabled,
                       String planId,
                       String planName,
                       String tierId,
                       String tierName,
                       long amountRub,
                       LocalDateTime nextChargeAt,
                       Integer targetSeq,
                       LocalDateTime lastFailureAt,
                       long subscriptionCount) {

        /** Списание реально произойдёт: включено, есть чем платить и магазин это умеет. */
        public boolean willCharge() {
            return enabled && cardBound && shopEnabled;
        }

        /** Тариф автопродления сняли с продажи — продлить по нему уже не получится. */
        public boolean planGone() {
            return planId != null && planName == null;
        }

        /** Нужно ли называть номер подписки: у человека с одной он только мешает. */
        public boolean needsSubscriptionName() {
            return subscriptionCount > 1 && targetSeq != null;
        }
    }

    /** Снимок для конкретного человека. Читает только базу и конфиг — годится для экранов. */
    public Info forUser(Customer user) {
        Subscription current = subscriptionRepository
                .findByTelegramIdAndCurrentTrue(user.getTelegramId()).orElse(null);
        long count = subscriptionRepository.countByTelegramId(user.getTelegramId());

        AppConfig.Plan plan = appConfig.findPlanOptional(user.getAutorenewPlanId()).orElse(null);
        // Ступень — У ПРОДЛЕВАЕМОЙ ПОДПИСКИ, тем же способом, что и в chargeAndActivate.
        // Ступень последней покупки (autorenew_device_tier_id) больше не участвует: она
        // расходилась с подпиской после переключения, и экран называл цену чужой ступени.
        String tierId = current != null ? current.getDeviceTierId() : user.getDeviceTierId();
        AppConfig.DeviceTier tier = appConfig.findDeviceTierOptional(tierId).orElse(null);
        long amountRub = plan != null ? appConfig.tierPriceKopeks(plan, tier) / 100 : 0;

        // Дату берём у РАБОТАЮЩЕЙ подписки, а не у человека: у припаркованной expires_at пуст
        // по построению, и счёт «минус сутки от срока человека» показал бы дату той подписки,
        // которая сейчас спит.
        LocalDateTime renewedUntil = current != null ? current.getExpiresAt() : user.getExpiresAt();
        LocalDateTime nextChargeAt = user.isAutorenewEnabled() && renewedUntil != null
                ? renewedUntil.minusHours(AutoRenewService.WINDOW_START_HOURS)
                : null;

        return new Info(
                user.isAutorenewEnabled(),
                user.getAutorenewMethodId() != null,
                appConfig.getYookassa() != null && appConfig.getYookassa().isRecurrentEnabled(),
                user.getAutorenewPlanId(),
                plan != null ? plan.getName() : null,
                tierId,
                // Ступень удалили из конфига — показываем её id: списываться будет базовая
                // цена плана, и молча выдавать это за «ступени нет» неправильно.
                tier != null ? tier.getName() : tierId,
                amountRub,
                nextChargeAt,
                current != null ? current.getSeq() : null,
                user.getAutorenewLastFailureAt(),
                count);
    }
}
