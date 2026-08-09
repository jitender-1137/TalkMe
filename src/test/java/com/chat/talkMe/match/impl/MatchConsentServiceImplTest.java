package com.chat.talkMe.match.impl;

import com.chat.talkMe.enums.ConsentStatus;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MatchConsentServiceImpl} — per-session 18+ (explicit
 * text) consent handshake over anonymous match sessions. All peer signals are anonymous
 * (event only, no identity). MAX_DECLINES = 3.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchConsentServiceImpl (unit)")
class MatchConsentServiceImplTest {

    private static final String A = "alice";
    private static final String B = "bob";

    @Mock
    private SessionService sessionService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private MatchConsentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MatchConsentServiceImpl(sessionService, messagingTemplate);
    }

    private MatchSession session(ConsentStatus status, int declineCount) {
        return MatchSession.builder().id("sess-1").userA(A).userB(B)
                .consentStatus(status).consentDeclineCount(declineCount).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(MatchServerEvent event) {
        return (Map<String, Object>) event.getPayload();
    }

    @Nested
    @DisplayName("handleHeldExplicit")
    class HandleHeldExplicit {

        @Test
        @DisplayName("fresh session (NONE) → auto-asks peer and flags the sender's message as held")
        void freshSessionAutoAsksPeer() {
            MatchSession s = session(ConsentStatus.NONE, 0);

            service.handleHeldExplicit(A, "client-1", s);

            assertThat(s.getConsentStatus()).isEqualTo(ConsentStatus.PENDING);
            assertThat(s.getConsentRequestedBy()).isEqualTo(A);

            ArgumentCaptor<MatchServerEvent> toPeer = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), toPeer.capture());
            assertThat(toPeer.getValue().getEvent()).isEqualTo("CONSENT_REQUEST_RECEIVED");
            assertThat(payloadOf(toPeer.getValue())).isEmpty();

            ArgumentCaptor<MatchServerEvent> toSender = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), toSender.capture());
            assertThat(toSender.getValue().getEvent()).isEqualTo("EXPLICIT_HELD");
            Map<String, Object> heldPayload = payloadOf(toSender.getValue());
            assertThat(heldPayload).containsEntry("clientId", "client-1")
                    .containsEntry("status", "PENDING")
                    .containsEntry("declineCount", 0)
                    .containsEntry("limitReached", false);
        }

        @Test
        @DisplayName("prior decline under cap (DECLINED, count 1) → re-asks the peer")
        void priorDeclineUnderCapReAsks() {
            MatchSession s = session(ConsentStatus.DECLINED, 1);

            service.handleHeldExplicit(A, "c2", s);

            assertThat(s.getConsentStatus()).isEqualTo(ConsentStatus.PENDING);
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("decline cap reached (DECLINED, count 3) → does NOT re-ask peer, only flags sender")
        void declineCapNoReAsk() {
            MatchSession s = session(ConsentStatus.DECLINED, 3);

            service.handleHeldExplicit(A, "c3", s);

            assertThat(s.getConsentStatus()).isEqualTo(ConsentStatus.DECLINED);
            verify(messagingTemplate, never()).convertAndSendToUser(eq(B), eq("/queue/match"), any());
            ArgumentCaptor<MatchServerEvent> toSender = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), toSender.capture());
            assertThat(payloadOf(toSender.getValue())).containsEntry("limitReached", true)
                    .containsEntry("status", "DECLINED");
        }

        @Test
        @DisplayName("already PENDING → holds silently (no re-ping), only flags sender")
        void pendingHoldsSilently() {
            MatchSession s = session(ConsentStatus.PENDING, 0);

            service.handleHeldExplicit(A, "c4", s);

            verify(messagingTemplate, never()).convertAndSendToUser(eq(B), eq("/queue/match"), any());
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("already GRANTED → no re-ask (defensive), only flags sender")
        void grantedNoReAsk() {
            MatchSession s = session(ConsentStatus.GRANTED, 0);

            service.handleHeldExplicit(A, "c5", s);

            verify(messagingTemplate, never()).convertAndSendToUser(eq(B), eq("/queue/match"), any());
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("null clientId → serialized as empty string in the held payload")
        void nullClientId() {
            MatchSession s = session(ConsentStatus.NONE, 0);

            service.handleHeldExplicit(A, null, s);

            ArgumentCaptor<MatchServerEvent> toSender = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), toSender.capture());
            assertThat(payloadOf(toSender.getValue())).containsEntry("clientId", "");
        }

        @Test
        @DisplayName("recipient resolution is symmetric — userB sender asks userA")
        void symmetricRecipient() {
            MatchSession s = session(ConsentStatus.NONE, 0);

            service.handleHeldExplicit(B, "c6", s);

            assertThat(s.getConsentRequestedBy()).isEqualTo(B);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"),
                    any(MatchServerEvent.class));
        }
    }

    @Nested
    @DisplayName("acceptConsent")
    class AcceptConsent {

        @Test
        @DisplayName("no active session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptConsent(A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active session found for user");
        }

        @Test
        @DisplayName("grants consent, resets decline count, and signals both peers CONSENT_GRANTED")
        void grantsAndSignalsBoth() {
            MatchSession s = session(ConsentStatus.PENDING, 2);
            s.setConsentRequestedBy(A);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.acceptConsent(A);

            assertThat(s.getConsentStatus()).isEqualTo(ConsentStatus.GRANTED);
            assertThat(s.getConsentDeclineCount()).isZero();
            assertThat(s.getConsentRequestedBy()).isNull();

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
            assertThat(ev.getValue().getEvent()).isEqualTo("CONSENT_GRANTED");
            assertThat(payloadOf(ev.getValue())).isEmpty();
        }
    }

    @Nested
    @DisplayName("declineConsent")
    class DeclineConsent {

        @Test
        @DisplayName("no active session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.declineConsent(A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active session found for user");
        }

        @Test
        @DisplayName("declines under cap → increments count, signals both CONSENT_DECLINED limitReached=false")
        void declineUnderCap() {
            MatchSession s = session(ConsentStatus.PENDING, 0);
            s.setConsentRequestedBy(B);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.declineConsent(A);

            assertThat(s.getConsentStatus()).isEqualTo(ConsentStatus.DECLINED);
            assertThat(s.getConsentDeclineCount()).isEqualTo(1);
            assertThat(s.getConsentRequestedBy()).isNull();

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
            assertThat(ev.getValue().getEvent()).isEqualTo("CONSENT_DECLINED");
            assertThat(payloadOf(ev.getValue())).containsEntry("limitReached", false);
        }

        @Test
        @DisplayName("declining reaches the cap → limitReached=true")
        void declineReachesCap() {
            MatchSession s = session(ConsentStatus.PENDING, 2);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.declineConsent(A);

            assertThat(s.getConsentDeclineCount()).isEqualTo(3);
            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            assertThat(payloadOf(ev.getValue())).containsEntry("limitReached", true);
        }
    }
}
