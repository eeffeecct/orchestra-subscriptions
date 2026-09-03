package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.access.AccessProvider;
import com.orchestra.subscriptions.model.PendingGrant;
import com.orchestra.subscriptions.model.UserDevice;
import com.orchestra.subscriptions.repository.PendingGrantRepository;
import com.orchestra.subscriptions.repository.UserDeviceRepository;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сроки хранения: чистка отпечатков устройств и просроченных подарочных грантов.
 * <p>
 * ⚠️ Свой {@code @TestPropertySource} обязателен: в {@code src/test/resources/application.yml}
 * задача выключена флагом, и без включения здесь весь класс зеленел бы вхолостую — методы
 * возвращались бы на первой же строке, ничего не удаляя.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.scheduler.retention-cleanup-enabled=true",
        "app.retention.device-days=180",
        "app.retention.pending-grant-days=30",
})
class RetentionCleanupServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    RetentionCleanupService retentionCleanupService;

    @Autowired
    PendingGrantService pendingGrantService;

    @Autowired
    PendingGrantRepository pendingGrantRepository;

    @Autowired
    UserDeviceRepository deviceRepository;

    @Autowired
    CustomerRepository userRepository;

    @MockitoBean
    AccessProvider accessProvider;

    @MockitoBean
    com.orchestra.subscriptions.client.YooKassaClient yooKassaClient;


    @BeforeEach
    void setUp() {
        pendingGrantRepository.deleteAll();
        deviceRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ========================
    // Отпечатки устройств
    // ========================

    @Test
    @DisplayName("Чистка удаляет отпечатки старше срока и не трогает свежие")
    void removesOnlyStaleDevices() {
        LocalDateTime now = LocalDateTime.now();
        saveDevice(1L, "hwid-fresh-0001", UserDevice.Status.ACTIVE, now.minusDays(2));
        saveDevice(1L, "hwid-inactive-01", UserDevice.Status.ACTIVE, now.minusDays(179));
        saveDevice(1L, "hwid-stale-0001", UserDevice.Status.ACTIVE, now.minusDays(181));
        saveDevice(2L, "hwid-stale-0002", UserDevice.Status.REVOKED, now.minusDays(400));

        retentionCleanupService.cleanupStaleDevices();

        assertThat(deviceRepository.findAll())
                .extracting(UserDevice::getHwid)
                .containsExactlyInAnyOrder("hwid-fresh-0001", "hwid-inactive-01");
    }

    /**
     * Ключевая гарантия: чистка не имеет права трогать записи внутри окна
     * {@code app.device-inactivity-days} — на них ещё держится лимит устройств.
     * Кривой конфиг должен приводить к «ничего не делаем», а не к тихому удалению.
     */
    @Test
    @DisplayName("Срок хранения не больше окна неактивности — чистка устройств не выполняется")
    void skipsWhenRetentionShorterThanInactivityWindow() {
        LocalDateTime now = LocalDateTime.now();
        saveDevice(1L, "hwid-stale-0003", UserDevice.Status.ACTIVE, now.minusDays(365));

        RetentionCleanupService target = AopTestUtils.getTargetObject(retentionCleanupService);
        ReflectionTestUtils.setField(target, "deviceRetentionDays", 10);
        try {
            retentionCleanupService.cleanupStaleDevices();
            assertThat(deviceRepository.findAll()).hasSize(1);
        } finally {
            ReflectionTestUtils.setField(target, "deviceRetentionDays", 180);
        }
    }

    // ========================
    // Подарочные гранты
    // ========================

    @Test
    @DisplayName("Новому гранту проставляется срок годности из конфига")
    void newGrantGetsExpiry() {
        PendingGrant grant = pendingGrantService.createGrant("winner", 30, 999L);

        assertThat(grant.getExpiresAt()).isNotNull();
        assertThat(grant.getExpiresAt()).isAfter(grant.getGrantedAt().plusDays(29));
        assertThat(grant.getExpiresAt()).isBefore(grant.getGrantedAt().plusDays(31));
    }

    @Test
    @DisplayName("Просроченный грант не применяется и юзера не создаёт")
    void expiredGrantIsNotApplied() {
        expire(pendingGrantService.createGrant("winner", 30, 999L));

        int applied = pendingGrantService.applyIfAny(555L, "winner");

        assertThat(applied).isZero();
        assertThat(userRepository.findByTelegramId(555L)).isEmpty();
        assertThat(pendingGrantRepository.findUnusedByUsername("winner")).isEmpty();
    }

    @Test
    @DisplayName("Чистка удаляет просроченные невостребованные гранты")
    void removesExpiredUnusedGrants() {
        expire(pendingGrantService.createGrant("expired", 30, 999L));
        pendingGrantService.createGrant("alive", 30, 999L);

        retentionCleanupService.cleanupExpiredGrants();

        assertThat(pendingGrantRepository.findAll())
                .extracting(PendingGrant::getUsername)
                .containsExactly("alive");
    }

    /** Использованные гранты — аудит «кому достался приз», их чистка не трогает. */
    @Test
    @DisplayName("Использованный грант остаётся, даже когда его срок вышел")
    void keepsUsedGrants() {
        PendingGrant used = expire(pendingGrantService.createGrant("winner", 30, 999L));
        used.setUsed(true);
        used.setUsedAt(LocalDateTime.now());
        used.setUsedByTelegramId(555L);
        pendingGrantRepository.save(used);

        retentionCleanupService.cleanupExpiredGrants();

        assertThat(pendingGrantRepository.findAll()).hasSize(1);
    }

    // ========================
    // Хелперы
    // ========================

    private void saveDevice(Long telegramId, String hwid, UserDevice.Status status,
                            LocalDateTime lastSeenAt) {
        deviceRepository.save(UserDevice.builder()
                .telegramId(telegramId)
                .hwid(hwid)
                .status(status)
                .firstSeenAt(lastSeenAt.minusDays(1))
                .lastSeenAt(lastSeenAt)
                .build());
    }

    /** Отодвигает срок годности в прошлое — так выглядит грант, за которым не пришли. */
    private PendingGrant expire(PendingGrant grant) {
        grant.setExpiresAt(LocalDateTime.now().minusDays(1));
        return pendingGrantRepository.save(grant);
    }
}
