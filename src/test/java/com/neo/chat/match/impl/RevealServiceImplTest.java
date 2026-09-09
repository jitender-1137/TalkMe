package com.neo.chat.match.impl;

import com.neo.chat.domain.User;
import com.neo.chat.enums.RevealChannel;
import com.neo.chat.enums.RevealState;
import com.neo.chat.match.MatchServerEvent;
import com.neo.chat.match.MatchSession;
import com.neo.chat.match.SessionService;
import com.neo.chat.repository.UserRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link RevealServiceImpl} — the mutual reveal handshake over
 * anonymous match sessions. A channel only flips to REVEALED (and payloads are exchanged)
 * once BOTH peers request/accept; PROFILE is the single consent-gated identity exposure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RevealServiceImpl (unit)")
class RevealServiceImplTest {

    private static final String A = "alice";
    private static final String B = "bob";

    @Mock
    private SessionService sessionService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RevealServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RevealServiceImpl(sessionService, userRepository, messagingTemplate, false);
    }

    private MatchSession session() {
        return MatchSession.builder().id("sess-1").userA(A).userB(B).build();
    }

    private User userNamed(String username) {
        User u = new User();
        u.setUuid(UUID.randomUUID());
        u.setUsername(username);
        u.setName(username.toUpperCase());
        u.setProfileImage(username + ".jpg");
        u.setAge(27);
        u.setGender("female");
        u.setCountry("US");
        u.setCity("NYC");
        u.setVoiceIntroUrl(username + ".mp3");
        u.setVoiceIntroDurationMs(4200);
        return u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayloadTo(String user, String expectedEvent) {
        ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq(user), eq("/queue/match"), ev.capture());
        assertThat(ev.getValue().getEvent()).isEqualTo(expectedEvent);
        return (Map<String, Object>) ev.getValue().getPayload();
    }

    @Nested
    @DisplayName("requestReveal")
    class RequestReveal {

        @Test
        @DisplayName("no active session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestReveal(A, RevealChannel.PROFILE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active session for user");
        }

        @Test
        @DisplayName("channel already exchanged → terminal no-op")
        void alreadyExchanged() {
            MatchSession s = session();
            s.getRevealExchanged().add(RevealChannel.PROFILE);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.requestReveal(A, RevealChannel.PROFILE);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("first request, peer not yet revealed → peer gets REVEAL_REQUEST_RECEIVED, state recorded")
        void firstRequestNotifiesPeer() {
            MatchSession s = session();
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.requestReveal(A, RevealChannel.PROFILE);

            Map<String, Object> payload = capturePayloadTo(B, "REVEAL_REQUEST_RECEIVED");
            assertThat(payload).containsEntry("channel", "PROFILE");
            assertThat(s.getRevealA()).containsEntry(RevealChannel.PROFILE, RevealState.REVEALED);
            assertThat(s.getRevealRequestedBy()).containsEntry(RevealChannel.PROFILE, A);
        }

        @Test
        @DisplayName("peer already REVEALED → mutual PROFILE exchange, each peer gets the other's identity")
        void mutualProfileExchange() {
            MatchSession s = session();
            s.getRevealB().put(RevealChannel.PROFILE, RevealState.REVEALED);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));
            User a = userNamed(A);
            User b = userNamed(B);
            when(userRepository.findByUsername(A)).thenReturn(Optional.of(a));
            when(userRepository.findByUsername(B)).thenReturn(Optional.of(b));

            service.requestReveal(A, RevealChannel.PROFILE);

            assertThat(s.getRevealExchanged()).contains(RevealChannel.PROFILE);
            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("REVEAL_GRANTED");
            @SuppressWarnings("unchecked")
            Map<String, Object> toA = (Map<String, Object>) ev.getValue().getPayload();
            // userA receives userB's identity.
            assertThat(toA).containsEntry("username", B)
                    .containsEntry("name", B.toUpperCase())
                    .containsEntry("id", b.getUuid().toString());
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
            verify(sessionService, never()).grantImagePermission(anyString());
        }

        @Test
        @DisplayName("PHOTO mutual exchange → image permission granted and avatars sent")
        void mutualPhotoGrantsImagePermission() {
            MatchSession s = session();
            s.getRevealB().put(RevealChannel.PHOTO, RevealState.REVEALED);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(A)).thenReturn(Optional.of(userNamed(A)));
            when(userRepository.findByUsername(B)).thenReturn(Optional.of(userNamed(B)));

            service.requestReveal(A, RevealChannel.PHOTO);

            verify(sessionService).grantImagePermission("sess-1");
            Map<String, Object> toA = capturePayloadTo(A, "REVEAL_GRANTED");
            assertThat(toA).containsEntry("avatar", B + ".jpg");
        }

        @Test
        @DisplayName("exchange with a missing user → payload carries only the channel (no NPE)")
        void exchangeWithMissingUser() {
            MatchSession s = session();
            s.getRevealB().put(RevealChannel.VOICE, RevealState.REVEALED);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(A)).thenReturn(Optional.of(userNamed(A)));
            when(userRepository.findByUsername(B)).thenReturn(Optional.empty());

            service.requestReveal(A, RevealChannel.VOICE);

            // userA would receive userB's voice payload, but B is missing → channel only.
            Map<String, Object> toA = capturePayloadTo(A, "REVEAL_GRANTED");
            assertThat(toA).containsOnlyKeys("channel");
        }

        @Test
        @DisplayName("decline cap reached → requester told REVEAL_DECLINED limitReached, peer not pinged")
        void declineCapBlocksRequest() {
            MatchSession s = session();
            s.getRevealDeclineCount().put(RevealChannel.PROFILE, 3);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.requestReveal(A, RevealChannel.PROFILE);

            Map<String, Object> payload = capturePayloadTo(A, "REVEAL_DECLINED");
            assertThat(payload).containsEntry("limitReached", true).containsEntry("channel", "PROFILE");
        }

        @Test
        @DisplayName("voice-before-photo enabled and VOICE not yet revealed → PHOTO request blocked")
        void voiceBeforePhotoBlocksPhoto() {
            service = new RevealServiceImpl(sessionService, userRepository, messagingTemplate, true);
            MatchSession s = session();
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.requestReveal(A, RevealChannel.PHOTO);

            Map<String, Object> payload = capturePayloadTo(A, "REVEAL_BLOCKED");
            assertThat(payload).containsEntry("channel", "PHOTO").containsEntry("requires", "VOICE");
            assertThat(s.getRevealA()).doesNotContainKey(RevealChannel.PHOTO);
        }

        @Test
        @DisplayName("voice-before-photo enabled but VOICE already exchanged → PHOTO request proceeds")
        void voiceBeforePhotoAllowsWhenVoiceExchanged() {
            service = new RevealServiceImpl(sessionService, userRepository, messagingTemplate, true);
            MatchSession s = session();
            s.getRevealExchanged().add(RevealChannel.VOICE);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.requestReveal(A, RevealChannel.PHOTO);

            capturePayloadTo(B, "REVEAL_REQUEST_RECEIVED");
        }

        @Test
        @DisplayName("recipient resolution is symmetric — userB requesting pings userA")
        void symmetricRequester() {
            MatchSession s = session();
            when(sessionService.getSessionByUser(B)).thenReturn(Optional.of(s));

            service.requestReveal(B, RevealChannel.PROFILE);

            capturePayloadTo(A, "REVEAL_REQUEST_RECEIVED");
            assertThat(s.getRevealB()).containsEntry(RevealChannel.PROFILE, RevealState.REVEALED);
        }
    }

    @Nested
    @DisplayName("acceptReveal")
    class AcceptReveal {

        @Test
        @DisplayName("no active session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptReveal(A, RevealChannel.PROFILE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("channel already exchanged → terminal no-op")
        void alreadyExchanged() {
            MatchSession s = session();
            s.getRevealExchanged().add(RevealChannel.PROFILE);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.acceptReveal(A, RevealChannel.PROFILE);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("accept while peer not yet revealed → records state, no exchange yet")
        void acceptWithoutPeerNoExchange() {
            MatchSession s = session();
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.acceptReveal(A, RevealChannel.PROFILE);

            assertThat(s.getRevealA()).containsEntry(RevealChannel.PROFILE, RevealState.REVEALED);
            assertThat(s.getRevealExchanged()).doesNotContain(RevealChannel.PROFILE);
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("accept when peer already revealed → both revealed → exchange fires")
        void acceptCompletesExchange() {
            MatchSession s = session();
            s.getRevealB().put(RevealChannel.PROFILE, RevealState.REVEALED);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(A)).thenReturn(Optional.of(userNamed(A)));
            when(userRepository.findByUsername(B)).thenReturn(Optional.of(userNamed(B)));

            service.acceptReveal(A, RevealChannel.PROFILE);

            assertThat(s.getRevealExchanged()).contains(RevealChannel.PROFILE);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), any());
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("voice-before-photo enabled → PHOTO accept blocked")
        void voiceBeforePhotoBlocksAccept() {
            service = new RevealServiceImpl(sessionService, userRepository, messagingTemplate, true);
            MatchSession s = session();
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.acceptReveal(A, RevealChannel.PHOTO);

            Map<String, Object> payload = capturePayloadTo(A, "REVEAL_BLOCKED");
            assertThat(payload).containsEntry("requires", "VOICE");
            assertThat(s.getRevealA()).doesNotContainKey(RevealChannel.PHOTO);
        }
    }

    @Nested
    @DisplayName("declineReveal")
    class DeclineReveal {

        @Test
        @DisplayName("no active session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.declineReveal(A, RevealChannel.PROFILE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("channel already exchanged → terminal no-op")
        void alreadyExchanged() {
            MatchSession s = session();
            s.getRevealExchanged().add(RevealChannel.PROFILE);
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.declineReveal(A, RevealChannel.PROFILE);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("decline resets the requester's side and notifies both, under cap")
        void declineResetsRequesterAndNotifiesBoth() {
            MatchSession s = session();
            // userA had requested; userB declines.
            s.getRevealRequestedBy().put(RevealChannel.PROFILE, A);
            s.getRevealA().put(RevealChannel.PROFILE, RevealState.REVEALED);
            when(sessionService.getSessionByUser(B)).thenReturn(Optional.of(s));

            service.declineReveal(B, RevealChannel.PROFILE);

            assertThat(s.getRevealB()).containsEntry(RevealChannel.PROFILE, RevealState.DECLINED);
            assertThat(s.getRevealA()).containsEntry(RevealChannel.PROFILE, RevealState.HIDDEN);
            assertThat(s.getRevealDeclineCount()).containsEntry(RevealChannel.PROFILE, 1);

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("REVEAL_DECLINED");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) ev.getValue().getPayload();
            assertThat(payload).containsEntry("limitReached", false).containsEntry("channel", "PROFILE");
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("decline reaching the cap → limitReached true")
        void declineReachesCap() {
            MatchSession s = session();
            s.getRevealDeclineCount().put(RevealChannel.PROFILE, 2);
            when(sessionService.getSessionByUser(B)).thenReturn(Optional.of(s));

            service.declineReveal(B, RevealChannel.PROFILE);

            assertThat(s.getRevealDeclineCount()).containsEntry(RevealChannel.PROFILE, 3);
            Map<String, Object> payload = capturePayloadTo(A, "REVEAL_DECLINED");
            assertThat(payload).containsEntry("limitReached", true);
        }

        @Test
        @DisplayName("decline with no prior requester → still notifies both, no NPE")
        void declineWithoutRequester() {
            MatchSession s = session();
            when(sessionService.getSessionByUser(A)).thenReturn(Optional.of(s));

            service.declineReveal(A, RevealChannel.VOICE);

            assertThat(s.getRevealA()).containsEntry(RevealChannel.VOICE, RevealState.DECLINED);
            verify(messagingTemplate).convertAndSendToUser(eq(A), eq("/queue/match"), any());
            verify(messagingTemplate).convertAndSendToUser(eq(B), eq("/queue/match"), any());
        }
    }
}
