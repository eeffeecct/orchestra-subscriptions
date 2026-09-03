package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.client.CryptoBotClient;
import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.client.YooKassaClient;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.PromoCode;
import com.orchestra.subscriptions.model.PromoUsage;
import com.orchestra.subscriptions.repository.PaymentRepository;
import com.orchestra.subscriptions.repository.PromoCodeRepository;
import com.orchestra.subscriptions.repository.PromoUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Промокод занимается в момент ВЫСТАВЛЕНИЯ СЧЁТА, а не в момент оплаты.
 * <p>
 * До этого правило «один код — один раз на человека» держалось на read-only-проверке, которую
 * N параллельных чекаутов проходили все до одного: человек получал N счетов со скидкой и платил
 * по ним со скидкой. Здесь проверяется и сама гарантия, и её обратная сторона — что брошенный
 * чекаут не запирает код навсегда.
 * <p>
 * Liquibase включён намеренно: обычные тесты поднимают схему Hibernate'ом, а частичный уникальный
 * индекс {@code uk_promo_usages_user_code} Hibernate по сущностям не создаёт — без миграций тест
 * проверял бы только код и зеленел бы, даже если БД ничего не гарантирует.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.liquibase.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
class PromoReservationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PromoCodeService promoCodeService;
    @Autowired
    PromoCodeRepository promoRepository;
    @Autowired
    PromoUsageRepository usageRepository;
    @Autowired
    PaymentRepository paymentRepository;

    @MockitoBean
    AccessProvider accessProvider;
    @MockitoBean
    YooKassaClient yooKassaClient;
    @MockitoBean
    CryptoBotClient cryptoBotClient;
    /** Мокаем, чтобы owner-алерты денежных аномалий не уходили в реальный бот. */
    @MockitoBean
    NotificationService notificationService;

    private static final long TG_ID = 770001L;
    private static final long OTHER_TG_ID = 770002L;
    private static final String CODE = "WELCOME20";
    private static final long BASE_KOPEKS = 10_000L;

    @BeforeEach
    void setUp() {
        usageRepository.deleteAll();
        promoRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    // ================================================================
    //  Гарантия «один раз на человека» на выставлении счёта
    // ================================================================

    @Test
    @DisplayName("Второй параллельный чекаут того же кода скидки не получает")
    void secondCheckoutOfSameCodeIsRejected() {
        createPromo(null);
        Payment first = pendingPayment();
        Payment second = pendingPayment();

        PromoCodeService.PromoResult a =
                promoCodeService.reserveForCheckout("welcome20", BASE_KOPEKS, TG_ID, first.getId());
        PromoCodeService.PromoResult b =
                promoCodeService.reserveForCheckout("welcome20", BASE_KOPEKS, TG_ID, second.getId());

        assertThat(a.valid()).isTrue();
        assertThat(a.finalAmountKopeks()).isEqualTo(8_000L);
        assertThat(b.valid()).isFalse();
        // Код держит собственный неоплаченный счёт человека — и сказать ему надо именно это,
        // а не «Вы уже использовали этот промокод»: он его ещё не тратил.
        assertThat(b.reason()).isEqualTo("pending_checkout");
        // Слот списан ровно один раз — на первом счёте, а не на оплате.
        assertThat(usedCount()).isEqualTo(1);
        assertThat(usageRepository.findByPaymentId(second.getId())).isEmpty();
    }

    @Test
    @DisplayName("После оплаты код закрыт для человека уже насовсем")
    void paidCodeIsClosedForGood() {
        createPromo(null);
        Payment paid = pendingPayment();
        promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, paid.getId());
        paid.setStatus(Payment.Status.SUCCEEDED);
        paymentRepository.save(paid);

        Payment again = pendingPayment();
        PromoCodeService.PromoResult second =
                promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, again.getId());

        assertThat(second.valid()).isFalse();
        assertThat(second.reason()).isEqualTo("already_used");
        assertThat(promoCodeService.preview(CODE, BASE_KOPEKS, TG_ID).reason())
                .isEqualTo("already_used");
    }

    /**
     * Последнее слово — за БД, а не за проверкой в коде: именно на этом держится защита от
     * настоящей параллельности, которую проверкой из двух запросов не закрыть.
     */
    @Test
    @DisplayName("Второе неоткаченное применение одного кода тем же человеком БД не принимает")
    void databaseRejectsSecondLiveUsage() {
        PromoCode promo = createPromo(null);
        Payment first = pendingPayment();
        Payment second = pendingPayment();
        promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, first.getId());

        assertThatThrownBy(() -> usageRepository.saveAndFlush(PromoUsage.builder()
                .promoCodeId(promo.getId())
                .telegramId(TG_ID)
                .paymentId(second.getId())
                .discountKopeks(2_000L)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ================================================================
    //  Брошенный резерв не запирает код навсегда
    // ================================================================

    @Test
    @DisplayName("Брошенный счёт возвращает код человеку через сутки")
    void abandonedCheckoutReleasesCodeAfterTtl() {
        createPromo(null);
        Payment abandoned = pendingPayment();
        assertThat(promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, abandoned.getId())
                .valid()).isTrue();
        // Пока счёт свежий — код занят, и это правильно.
        assertThat(promoCodeService.preview(CODE, BASE_KOPEKS, TG_ID).valid()).isFalse();

        // Человек ушёл, не оплатив: счёт висит сутки с лишним.
        abandoned.setCreatedAt(LocalDateTime.now().minusHours(25));
        paymentRepository.save(abandoned);

        assertThat(promoCodeService.preview(CODE, BASE_KOPEKS, TG_ID).valid()).isTrue();

        Payment retry = pendingPayment();
        assertThat(promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, retry.getId())
                .valid()).isTrue();
        assertThat(usageRepository.findByPaymentId(abandoned.getId()).orElseThrow().getRevertedAt())
                .isNotNull();
        // Слот вернулся в оборот и занят заново — счётчик не должен «протечь» вверх.
        assertThat(usedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Отменённый счёт возвращает код сразу, не дожидаясь суток")
    void canceledCheckoutReleasesCodeImmediately() {
        createPromo(null);
        Payment canceled = pendingPayment();
        promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, canceled.getId());

        canceled.setStatus(Payment.Status.CANCELED);
        paymentRepository.save(canceled);
        promoCodeService.releaseReservation(canceled.getId());

        assertThat(usedCount()).isZero();
        assertThat(promoCodeService.preview(CODE, BASE_KOPEKS, TG_ID).valid()).isTrue();

        Payment retry = pendingPayment();
        assertThat(promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, retry.getId())
                .valid()).isTrue();
        assertThat(usedCount()).isEqualTo(1);
    }

    /** Резерв оплаченного счёта снимать нельзя: за него человек уже получил скидку. */
    @Test
    @DisplayName("Резерв ОПЛАЧЕННОГО счёта не снимается")
    void paidReservationIsNeverReleased() {
        createPromo(null);
        Payment paid = pendingPayment();
        promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, paid.getId());
        paid.setStatus(Payment.Status.SUCCEEDED);
        paymentRepository.save(paid);

        promoCodeService.releaseReservation(paid.getId());

        assertThat(usageRepository.findByPaymentId(paid.getId()).orElseThrow().getRevertedAt())
                .isNull();
        assertThat(usedCount()).isEqualTo(1);
    }

    // ================================================================
    //  Оплата: подтверждение резерва идемпотентно
    // ================================================================

    @Test
    @DisplayName("Повторная обработка успешной оплаты не списывает слот второй раз")
    void succeededPaymentDoesNotSpendSlotTwice() {
        createPromo(null);
        Payment paid = pendingPayment();
        promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, paid.getId());
        paid.setStatus(Payment.Status.SUCCEEDED);
        paymentRepository.save(paid);

        promoCodeService.recordUsageOnSucceeded(CODE, BASE_KOPEKS, TG_ID, paid.getId());
        promoCodeService.recordUsageOnSucceeded(CODE, BASE_KOPEKS, TG_ID, paid.getId());

        assertThat(usedCount()).isEqualTo(1);
        assertThat(usageRepository.findTop10ByPromoCodeIdOrderByUsedAtDesc(promoId())).hasSize(1);
    }

    /**
     * Счета, выставленные ДО появления резервирования, обязаны продолжать работать: у них
     * промокод записан только на строке платежа, и применение дописывается по факту оплаты.
     */
    @Test
    @DisplayName("Счёт без резерва (выставлен до деплоя) записывает применение на оплате")
    void legacyInvoiceWithoutReservationIsRecordedOnPayment() {
        createPromo(null);
        Payment legacy = paymentRepository.save(Payment.builder()
                .telegramId(TG_ID)
                .planId("month_1")
                .amountKopeks(8_000L)
                .promoCodeUsed(CODE)
                .provider(Payment.Provider.YOOKASSA)
                .status(Payment.Status.SUCCEEDED)
                .build());

        promoCodeService.recordUsageOnSucceeded(CODE, BASE_KOPEKS, TG_ID, legacy.getId());

        assertThat(usageRepository.findByPaymentId(legacy.getId())).isPresent();
        assertThat(usedCount()).isEqualTo(1);
    }

    // ================================================================
    //  Лимит применений тоже резервируется
    // ================================================================

    @Test
    @DisplayName("Выставленный счёт занимает слот max_uses, а не только оплаченный")
    void limitedCodeIsHeldByTheInvoiceItself() {
        createPromo(1);
        Payment mine = pendingPayment();
        assertThat(promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, mine.getId())
                .valid()).isTrue();

        Payment other = pendingPayment(OTHER_TG_ID);
        PromoCodeService.PromoResult second =
                promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, OTHER_TG_ID, other.getId());

        assertThat(second.valid()).isFalse();
        assertThat(second.reason()).isEqualTo("max_uses_reached");
    }

    @Test
    @DisplayName("Слот max_uses, занятый брошенным счётом, возвращается другим людям")
    void limitedCodeReturnsSlotFromAbandonedInvoice() {
        createPromo(1);
        Payment abandoned = pendingPayment();
        promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, TG_ID, abandoned.getId());
        abandoned.setCreatedAt(LocalDateTime.now().minusHours(25));
        paymentRepository.save(abandoned);

        Payment other = pendingPayment(OTHER_TG_ID);
        assertThat(promoCodeService.reserveForCheckout(CODE, BASE_KOPEKS, OTHER_TG_ID, other.getId())
                .valid()).isTrue();
        assertThat(usedCount()).isEqualTo(1);
    }

    // ===== helpers =====

    private PromoCode createPromo(Integer maxUses) {
        return promoCodeService.createPromo(CODE, PromoCode.DiscountType.PERCENT, 20,
                maxUses, null, 1L);
    }

    private Payment pendingPayment() {
        return pendingPayment(TG_ID);
    }

    private Payment pendingPayment(long telegramId) {
        return paymentRepository.save(Payment.builder()
                .telegramId(telegramId)
                .planId("month_1")
                .amountKopeks(BASE_KOPEKS)
                .provider(Payment.Provider.YOOKASSA)
                .status(Payment.Status.PENDING)
                .build());
    }

    private Long promoId() {
        return promoRepository.findByCode(CODE).orElseThrow().getId();
    }

    private int usedCount() {
        return promoRepository.findByCode(CODE).orElseThrow().getUsedCount();
    }
}
