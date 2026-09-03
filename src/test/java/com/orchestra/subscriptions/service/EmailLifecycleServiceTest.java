package com.orchestra.subscriptions.service;

import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест на {@link EmailLifecycleService#releaseStaleUnverifiedEmails} — без Spring
 * контекста. Проверяем что:
 * <ul>
 *   <li>пустой список кандидатов → no-op (никаких save / DM);</li>
 *   <li>каждый кандидат: email/verifiedAt/consentAt все три зануляются и save'аются;</li>
 *   <li>для каждого кандидата шлётся DM с правильным telegramId;</li>
 *   <li>cutoff передаётся в репозиторий ровно {@code now - 30 дней}.</li>
 * </ul>
 */
class EmailLifecycleServiceTest {

    private CustomerRepository userRepository;
    private NotificationService notificationService;
    private EmailLifecycleService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(CustomerRepository.class);
        notificationService = mock(NotificationService.class);
        service = new EmailLifecycleService(userRepository, notificationService);
    }

    private Customer staleUser(long tgId, String email) {
        return Customer.builder()
                .telegramId(tgId)
                .providerUsername("sub_" + tgId)
                .email(email)
                .emailVerifiedAt(null)
                .emailConsentAt(LocalDateTime.now().minusDays(35))
                .build();
    }

    @Test
    void noopWhenNoCandidates() {
        when(userRepository.findStaleUnverifiedEmails(any())).thenReturn(List.of());

        service.releaseStaleUnverifiedEmails();

        verify(userRepository, never()).save(any());
        verify(notificationService, never()).sendEmailAutoReleasedDm(anyLong());
    }

    @Test
    void zeroesOutAllEmailFieldsForCandidates() {
        Customer a = staleUser(1L, "lost1@example.com");
        Customer b = staleUser(2L, "lost2@example.com");
        when(userRepository.findStaleUnverifiedEmails(any())).thenReturn(List.of(a, b));

        service.releaseStaleUnverifiedEmails();

        // Оба юзера должны быть сохранены с зануленным email/verifiedAt/consentAt
        ArgumentCaptor<Customer> savedCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(userRepository, times(2)).save(savedCaptor.capture());

        List<Customer> saved = savedCaptor.getAllValues();
        assertThat(saved).hasSize(2);
        for (Customer u : saved) {
            assertThat(u.getEmail()).isNull();
            assertThat(u.getEmailVerifiedAt()).isNull();
            assertThat(u.getEmailConsentAt()).isNull();
        }
    }

    @Test
    void sendsDmForEachCandidate() {
        Customer a = staleUser(11L, "a@example.com");
        Customer b = staleUser(22L, "b@example.com");
        Customer c = staleUser(33L, "c@example.com");
        when(userRepository.findStaleUnverifiedEmails(any())).thenReturn(List.of(a, b, c));

        service.releaseStaleUnverifiedEmails();

        ArgumentCaptor<Long> tgIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(notificationService, times(3)).sendEmailAutoReleasedDm(tgIdCaptor.capture());
        assertThat(tgIdCaptor.getAllValues()).containsExactly(11L, 22L, 33L);
    }

    @Test
    void passesCorrectCutoffToRepository() {
        when(userRepository.findStaleUnverifiedEmails(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusDays(30).minusSeconds(2);
        service.releaseStaleUnverifiedEmails();
        LocalDateTime after = LocalDateTime.now().minusDays(30).plusSeconds(2);

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).findStaleUnverifiedEmails(cutoffCaptor.capture());
        LocalDateTime cutoff = cutoffCaptor.getValue();
        // cutoff = now - 30 дней; допуск ±2 сек на дрейф timestamp'а во время вызова
        assertThat(cutoff).isAfter(before).isBefore(after);
    }
}
