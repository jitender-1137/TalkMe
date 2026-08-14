package com.neo.chat.schedule;

import com.neo.chat.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AccountPurgeReaper} — the daily @Scheduled job that delegates to
 * {@link AuthService#purgeExpiredDeletedAccounts()} to irreversibly anonymize soft-deleted
 * accounts past their recovery window.
 *
 * <p>Reaper checklist: nothing-to-do no-op (0 purged), positive batch (N purged), and
 * downstream-failure isolation — a thrown exception is swallowed so the schedule survives.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPurgeReaper (unit)")
class AccountPurgeReaperTest {

    @Mock
    private AuthService authService;

    private AccountPurgeReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new AccountPurgeReaper(authService);
    }

    @Nested
    @DisplayName("purgeExpiredDeletedAccounts")
    class Purge {

        @Test
        @DisplayName("nothing to do: 0 expired accounts is a clean no-op, still delegates once")
        void shouldNoOpWhenNothingExpired() {
            when(authService.purgeExpiredDeletedAccounts()).thenReturn(0);

            assertThatCode(() -> reaper.purgeExpiredDeletedAccounts()).doesNotThrowAnyException();

            verify(authService, times(1)).purgeExpiredDeletedAccounts();
        }

        @Test
        @DisplayName("positive batch: delegates and completes when accounts are purged")
        void shouldDelegateWhenAccountsPurged() {
            when(authService.purgeExpiredDeletedAccounts()).thenReturn(7);

            assertThatCode(() -> reaper.purgeExpiredDeletedAccounts()).doesNotThrowAnyException();

            verify(authService).purgeExpiredDeletedAccounts();
        }

        @Test
        @DisplayName("downstream failure isolation: service exception is swallowed, never propagates")
        void shouldSwallowDownstreamFailure() {
            when(authService.purgeExpiredDeletedAccounts())
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> reaper.purgeExpiredDeletedAccounts()).doesNotThrowAnyException();

            verify(authService).purgeExpiredDeletedAccounts();
        }
    }
}
