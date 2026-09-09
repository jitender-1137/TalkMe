package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.FlirtModeResponse;
import com.neo.chat.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for the {@link FlirtModeServiceImpl} FACADE (feature FLIRT_MODE).
 *
 * <p>After BootUI ARCH-SPRING-004, the transactional consent/row logic lives in the collaborator
 * {@link FlirtModeConsentTx} (unit-tested separately in {@code FlirtModeConsentTxTest}). This suite
 * mocks that collaborator and verifies the facade's own responsibilities:
 * <ul>
 *   <li>{@code setConsentWithRetry} — the optimistic-lock retry loop that drives
 *       {@code consentTx.applyConsentTx} <b>cross-bean</b> (fresh committed tx per attempt) and
 *       delivers the two after-commit WS pushes ONLY on a successful commit;</li>
 *   <li>{@code getState}/{@code sendKiss} delegation — plus the ephemeral {@code flirt_kiss}
 *       envelope the facade builds after the collaborator resolves the partner.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlirtModeServiceImpl facade (unit)")
class FlirtModeServiceImplTest {

    private static final String CHAT_UUID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private FlirtModeConsentTx consentTx;

    private FlirtModeServiceImpl service;

    private User lowUser;   // id 10, "low_user"

    @BeforeEach
    void setUp() {
        service = new FlirtModeServiceImpl(messagingTemplate, consentTx);
        lowUser = userWithId(10L, "low_user");
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private static User userWithId(long id, String username) {
        User u = User.builder().username(username).email(username + "@e.com").name(username).build();
        u.setId(id);
        return u;
    }

    private static FlirtModeResponse resp(boolean myEnabled, boolean otherEnabled, boolean active) {
        return FlirtModeResponse.builder()
                .chatUuid(CHAT_UUID)
                .myEnabled(myEnabled)
                .otherEnabled(otherEnabled)
                .active(active)
                .build();
    }

    /**
     * A committed-mutation result addressed to low_user (me) + high_user (other).
     */
    private static FlirtModeConsentTx.ConsentResult result(boolean myEnabled, boolean otherEnabled, boolean active) {
        FlirtModeResponse mine = resp(myEnabled, otherEnabled, active);
        FlirtModeConsentTx.Push mePush = new FlirtModeConsentTx.Push("low_user", mine);
        FlirtModeConsentTx.Push otherPush =
                new FlirtModeConsentTx.Push("high_user", resp(otherEnabled, myEnabled, active));
        return new FlirtModeConsentTx.ConsentResult(mine, mePush, otherPush);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  getState — delegation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getState delegates to the collaborator and returns its response")
    void getStateDelegatesToConsentTx() {
        FlirtModeResponse expected = resp(true, false, false);
        when(consentTx.getState(lowUser, CHAT_UUID)).thenReturn(expected);

        FlirtModeResponse res = service.getState(lowUser, CHAT_UUID);

        assertThat(res).isSameAs(expected);
        verify(consentTx).getState(lowUser, CHAT_UUID);
        // The facade never touches the broker on a read.
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  enable / disable → setConsentWithRetry (retry loop + after-commit pushes)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("enable/disable — setConsentWithRetry + WS delivery")
    class SetConsentWithRetry {

        @Test
        void enableShouldApplyConsentAndPushToBothParticipantsAfterCommit() {
            // The collaborator commits and returns the two viewer-relative pushes; the facade must
            // then deliver them per-user on /queue/flirt-mode.
            when(consentTx.applyConsentTx(lowUser, CHAT_UUID, true))
                    .thenReturn(result(true, false, false));

            FlirtModeResponse res = service.enable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isTrue();
            assertThat(res.isOtherEnabled()).isFalse();
            verify(messagingTemplate).convertAndSendToUser(
                    eq("low_user"), eq("/queue/flirt-mode"), any());
            verify(messagingTemplate).convertAndSendToUser(
                    eq("high_user"), eq("/queue/flirt-mode"), any());
        }

        @Test
        void disableShouldApplyConsentAndDeliverPushes() {
            when(consentTx.applyConsentTx(lowUser, CHAT_UUID, false))
                    .thenReturn(result(false, true, false));

            FlirtModeResponse res = service.disable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isFalse();  // low just disabled
            assertThat(res.isActive()).isFalse();
            verify(messagingTemplate, times(2))
                    .convertAndSendToUser(anyString(), eq("/queue/flirt-mode"), any());
        }

        @Test
        void shouldRetryOnOptimisticLockThenSucceed() {
            // First attempt loses the version race at the proxy boundary; the loop re-invokes the
            // collaborator (a fresh committed tx) and re-applies, so the caller's toggle still lands.
            when(consentTx.applyConsentTx(lowUser, CHAT_UUID, true))
                    .thenThrow(new ObjectOptimisticLockingFailureException("race", null))
                    .thenReturn(result(true, false, false));

            FlirtModeResponse res = service.enable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isTrue();
            verify(consentTx, times(2)).applyConsentTx(lowUser, CHAT_UUID, true);
            // Pushes only fire on the successful commit (once → two participants), never on the
            // rolled-back attempt.
            verify(messagingTemplate, times(2))
                    .convertAndSendToUser(anyString(), eq("/queue/flirt-mode"), any());
        }

        @Test
        void shouldRethrowAfterExhaustingRetryAttempts() {
            // Every attempt loses the race → after MAX_TOGGLE_ATTEMPTS the last exception surfaces.
            when(consentTx.applyConsentTx(lowUser, CHAT_UUID, true))
                    .thenThrow(new ObjectOptimisticLockingFailureException("race", null));

            assertThatThrownBy(() -> service.enable(lowUser, CHAT_UUID))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);

            verify(consentTx, times(3)).applyConsentTx(lowUser, CHAT_UUID, true);
            // A never-committed mutation must never push cached state to either client.
            verify(messagingTemplate, never())
                    .convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void pushDeliveryFailureShouldNotFailTheToggle() {
            // WS delivery is best-effort/fail-open: a broker blip must not fail a committed toggle.
            when(consentTx.applyConsentTx(lowUser, CHAT_UUID, true))
                    .thenReturn(result(true, false, false));
            doThrow(new RuntimeException("stomp down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            FlirtModeResponse res = service.enable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  sendKiss — live "blow a kiss" (delegates gate to collaborator, delivers nudge)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendKiss")
    class SendKiss {

        @Test
        @DisplayName("resolved partner → pushes a flirt_kiss envelope to the OTHER participant")
        void pushesKissWhenActive() {
            when(consentTx.resolveKissPartnerOrThrow(lowUser, CHAT_UUID)).thenReturn("high_user");

            service.sendKiss(lowUser, CHAT_UUID);

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSendToUser(
                    eq("high_user"), eq("/queue/flirt-mode"), payload.capture());
            assertThat(payload.getValue()).isInstanceOfSatisfying(Map.class, envelope -> {
                assertThat(envelope.get("event")).isEqualTo("flirt_kiss");
                assertThat(envelope.get("payload")).isInstanceOfSatisfying(Map.class, p -> {
                    assertThat(p.get("chatId")).isEqualTo(CHAT_UUID);
                    assertThat(p.get("fromName")).isEqualTo("low_user");
                });
            });
        }

        @Test
        @DisplayName("null partner (no reachable other) → no-op, nothing pushed")
        void noopWhenPartnerNull() {
            when(consentTx.resolveKissPartnerOrThrow(lowUser, CHAT_UUID)).thenReturn(null);

            service.sendKiss(lowUser, CHAT_UUID);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("collaborator gate throws (TM_834) → propagates, nothing pushed")
        void propagatesKissRejection() {
            when(consentTx.resolveKissPartnerOrThrow(lowUser, CHAT_UUID))
                    .thenThrow(new BadRequestException(
                            "Flirt Mode must be active for both of you to blow a kiss", "TM_834"));

            assertThatThrownBy(() -> service.sendKiss(lowUser, CHAT_UUID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_834"));
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }
    }
}
