package com.chat.talkMe.match.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ConsentStatus;
import com.chat.talkMe.match.MatchConsentService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.moderation.ModerationResult;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ChatRoutingServiceImpl} — relays ephemeral match messages
 * to the correct peer over STOMP, and (when the peer is offline) buffers + push-notifies.
 *
 * <p>Key invariants under test: (1) no session → {@link IllegalArgumentException}; (2) explicit
 * text without GRANTED consent is HELD, never relayed; (3) images require session permission;
 * (4) recipient resolution is symmetric (sender may be userA or userB); (5) the offline path
 * buffers + fires an ANONYMOUS push, the online path does neither; (6) background-delivery
 * failures are swallowed so relay never breaks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoutingServiceImpl (unit)")
class ChatRoutingServiceImplTest {

    private static final String SENDER = "alice";
    private static final String PEER = "bob";

    @Mock
    private SessionService sessionService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private MatchConsentService matchConsentService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private MatchMessageBufferService matchMessageBuffer;
    @Mock
    private NotificationDispatchService notificationDispatchService;

    private ChatRoutingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatRoutingServiceImpl(sessionService, messagingTemplate, moderationService,
                matchConsentService, userRepository, redisTemplate, notificationDispatchService,
                matchMessageBuffer);
    }

    private MatchSession session(ConsentStatus consent, boolean imagePermission) {
        return MatchSession.builder()
                .id("sess-1").userA(SENDER).userB(PEER)
                .consentStatus(consent).imagePermissionStatus(imagePermission)
                .build();
    }

    /**
     * Make the recipient look ONLINE (a live socket in the presence set).
     */
    private void recipientOnline() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.size(anyString())).thenReturn(1L);
    }

    /**
     * Make the recipient look OFFLINE (no live socket).
     */
    private void recipientOffline() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.size(anyString())).thenReturn(0L);
    }

    @Nested
    @DisplayName("relayMessage")
    class RelayMessage {

        @Test
        @DisplayName("no active session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(SENDER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.relayMessage(SENDER, "hi", "c1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active session");
        }

        @Test
        @DisplayName("clean text is relayed to the peer")
        void relaysCleanText() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));
            when(moderationService.moderateText("hi")).thenReturn(ModerationResult.clean());
            recipientOnline();

            service.relayMessage(SENDER, "hi", "c1");

            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), any());
            verify(matchConsentService, never()).handleHeldExplicit(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("explicit text without GRANTED consent is HELD, not relayed")
        void holdsExplicitWithoutConsent() {
            MatchSession s = session(ConsentStatus.NONE, false);
            when(sessionService.getSessionByUser(SENDER)).thenReturn(Optional.of(s));
            when(moderationService.moderateText("dirty"))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.SEXUAL, 0.9, List.of()));

            service.relayMessage(SENDER, "dirty", "c1");

            verify(matchConsentService).handleHeldExplicit(SENDER, "c1", s);
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("explicit text WITH GRANTED consent is relayed")
        void relaysExplicitWhenGranted() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.GRANTED, false)));
            when(moderationService.moderateText("dirty"))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.SEXUAL, 0.9, List.of()));
            recipientOnline();

            service.relayMessage(SENDER, "dirty", "c1");

            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), any());
            verify(matchConsentService, never()).handleHeldExplicit(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("recipient resolution is symmetric — userB sending routes to userA")
        void symmetricRecipient() {
            MatchSession s = MatchSession.builder().id("s").userA(SENDER).userB(PEER)
                    .consentStatus(ConsentStatus.NONE).build();
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.of(s));
            when(moderationService.moderateText("yo")).thenReturn(ModerationResult.clean());
            recipientOnline();

            service.relayMessage(PEER, "yo", "c1");

            verify(messagingTemplate).convertAndSendToUser(eq(SENDER), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("offline peer → message buffered AND an anonymous push is dispatched")
        void offlinePeerBuffersAndPushes() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));
            when(moderationService.moderateText("hi")).thenReturn(ModerationResult.clean());
            recipientOffline();
            User peer = new User();
            peer.setId(77L);
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));

            service.relayMessage(SENDER, "hi", "c1");

            verify(matchMessageBuffer).buffer(eq(PEER), any(MatchServerEvent.class));
            verify(notificationDispatchService).onEphemeralMessage(eq(77L), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("online peer → no buffering, no push")
        void onlinePeerNoBufferNoPush() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));
            when(moderationService.moderateText("hi")).thenReturn(ModerationResult.clean());
            recipientOnline();

            service.relayMessage(SENDER, "hi", "c1");

            verify(matchMessageBuffer, never()).buffer(anyString(), any());
            verify(notificationDispatchService, never()).onEphemeralMessage(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("background-delivery failure is swallowed — relay still succeeds")
        void backgroundFailureSwallowed() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));
            when(moderationService.moderateText("hi")).thenReturn(ModerationResult.clean());
            when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("redis down"));

            service.relayMessage(SENDER, "hi", "c1");

            // The relay frame still went out despite the background handler blowing up.
            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), any());
        }
    }

    @Nested
    @DisplayName("relayGif")
    class RelayGif {

        @Test
        @DisplayName("no session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(SENDER)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.relayGif(SENDER, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("relays the gif to the peer")
        void relays() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));
            recipientOnline();

            service.relayGif(SENDER, Map.of("url", "x.mp4"));

            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), any());
        }
    }

    @Nested
    @DisplayName("relayImage")
    class RelayImage {

        @Test
        @DisplayName("no session → IllegalArgumentException")
        void noSession() {
            when(sessionService.getSessionByUser(SENDER)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.relayImage(SENDER, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("image permission not granted → IllegalStateException, nothing relayed")
        void permissionRequired() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));

            assertThatThrownBy(() -> service.relayImage(SENDER, Map.of("url", "x.jpg")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not approved");
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("permission granted → relays the image to the peer")
        void relaysWhenPermitted() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, true)));
            recipientOnline();

            service.relayImage(SENDER, Map.of("url", "x.jpg"));

            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), any());
        }
    }

    @Nested
    @DisplayName("relayTyping")
    class RelayTyping {

        @Test
        @DisplayName("no session → silent no-op (no exception, no send)")
        void noSessionNoop() {
            when(sessionService.getSessionByUser(SENDER)).thenReturn(Optional.empty());

            service.relayTyping(SENDER, true);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("active session → forwards the typing flag to the peer")
        void forwardsTyping() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));

            service.relayTyping(SENDER, true);

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("STRANGER_TYPING");
        }

        @Test
        @DisplayName("recipient resolution symmetric — userB typing routes to userA")
        void forwardsTypingSymmetric() {
            when(sessionService.getSessionByUser(PEER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));

            service.relayTyping(PEER, false);

            verify(messagingTemplate).convertAndSendToUser(eq(SENDER), eq("/queue/match"), any());
        }
    }

    // ── Recipient-resolution & offline-detection branch backfill ─────────────────

    @Nested
    @DisplayName("branch backfill")
    class BranchBackfill {

        @Test
        @DisplayName("relayGif recipient resolution symmetric — userB routes to userA")
        void relaysGifSymmetric() {
            when(sessionService.getSessionByUser(PEER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));
            recipientOnline();

            service.relayGif(PEER, Map.of("url", "x.mp4"));

            verify(messagingTemplate).convertAndSendToUser(eq(SENDER), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("relayImage recipient resolution symmetric — userB routes to userA")
        void relaysImageSymmetric() {
            when(sessionService.getSessionByUser(PEER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, true)));
            recipientOnline();

            service.relayImage(PEER, Map.of("url", "x.jpg"));

            verify(messagingTemplate).convertAndSendToUser(eq(SENDER), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("null socket-set size → treated as offline: buffers and pushes")
        void nullSizeTreatedOffline() {
            when(sessionService.getSessionByUser(SENDER))
                    .thenReturn(Optional.of(session(ConsentStatus.NONE, false)));
            when(moderationService.moderateText("hi")).thenReturn(ModerationResult.clean());
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size(anyString())).thenReturn(null); // live == null branch
            User peer = new User();
            peer.setId(88L);
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));

            service.relayMessage(SENDER, "hi", "c1");

            verify(matchMessageBuffer).buffer(eq(PEER), any(MatchServerEvent.class));
            verify(notificationDispatchService)
                    .onEphemeralMessage(eq(88L), anyString(), anyString(), anyString());
        }
    }
}
