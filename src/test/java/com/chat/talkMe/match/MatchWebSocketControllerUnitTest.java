package com.chat.talkMe.match;

import com.chat.talkMe.dto.request.MatchStartRequest;
import com.chat.talkMe.enums.RevealChannel;
import com.chat.talkMe.match.impl.MatchMessageBufferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pure unit test for the STOMP {@link MatchWebSocketController}. There is no MockMvc for
 * {@code @MessageMapping} handlers, so we invoke each handler method directly with mocked services
 * and a {@link Principal}, asserting the delegation (or the null-principal no-op).
 *
 * <p><b>Scope boundary:</b> STOMP routing, subscription authz, and payload (de)serialization are the
 * broker/framework's job and are out of scope here — this verifies the handler logic: null guards,
 * payload field extraction, enum parsing, and the {@code timed-action} dispatch switch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchWebSocketController (unit)")
class MatchWebSocketControllerUnitTest {

    @Mock private MatchmakingService matchmakingService;
    @Mock private ChatRoutingService chatRoutingService;
    @Mock private ImagePermissionService imagePermissionService;
    @Mock private MatchConsentService matchConsentService;
    @Mock private MatchMessageBufferService matchMessageBuffer;
    @Mock private RevealService revealService;
    @Mock private MatchTimerService matchTimerService;

    @InjectMocks
    private MatchWebSocketController controller;

    private static final Principal ALICE = () -> "alice";

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void verifyNoServiceInteractions() {
        verifyNoInteractions(matchmakingService, chatRoutingService, imagePermissionService,
                matchConsentService, matchMessageBuffer, revealService, matchTimerService);
    }

    // ── /match/start ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("/match/start")
    class Start {
        @Test
        void shouldStartBlindMatchWhenNoFilters() {
            controller.startMatching(null, ALICE);
            verify(matchmakingService).startMatching("alice");
        }

        @Test
        void shouldStartPreferenceMatchWhenFiltersPresent() {
            MatchStartRequest filters = new MatchStartRequest();
            controller.startMatching(filters, ALICE);
            verify(matchmakingService).startMatching("alice", filters);
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.startMatching(new MatchStartRequest(), null);
            verifyNoServiceInteractions();
        }
    }

    // ── /match/resume ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("/match/resume")
    class Resume {
        @Test
        void shouldFlushBuffer() {
            controller.resume(ALICE);
            verify(matchMessageBuffer).flush("alice");
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.resume(null);
            verifyNoServiceInteractions();
        }
    }

    // ── /match/message ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("/match/message")
    class SendMessage {
        @Test
        void shouldRelayContentAndClientId() {
            controller.sendMessage(map("content", "hi", "clientId", "c-1"), ALICE);
            verify(chatRoutingService).relayMessage("alice", "hi", "c-1");
        }

        @Test
        void shouldRelayWithNullFieldsWhenAbsent() {
            controller.sendMessage(map(), ALICE);
            verify(chatRoutingService).relayMessage("alice", null, null);
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.sendMessage(map("content", "hi"), null);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldNoOpWhenPayloadNull() {
            controller.sendMessage(null, ALICE);
            verifyNoServiceInteractions();
        }
    }

    // ── /match/typing ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("/match/typing")
    class Typing {
        @Test
        void shouldRelayTypingTrue() {
            controller.typing(true, ALICE);
            verify(chatRoutingService).relayTyping("alice", true);
        }

        @Test
        void shouldRelayTypingFalse() {
            controller.typing(false, ALICE);
            verify(chatRoutingService).relayTyping("alice", false);
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.typing(true, null);
            verifyNoServiceInteractions();
        }
    }

    // ── consent ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("consent accept/decline")
    class Consent {
        @Test
        void shouldAcceptConsent() {
            controller.acceptConsent(ALICE);
            verify(matchConsentService).acceptConsent("alice");
        }

        @Test
        void shouldDeclineConsent() {
            controller.declineConsent(ALICE);
            verify(matchConsentService).declineConsent("alice");
        }

        @Test
        void shouldNoOpAcceptWhenPrincipalNull() {
            controller.acceptConsent(null);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldNoOpDeclineWhenPrincipalNull() {
            controller.declineConsent(null);
            verifyNoServiceInteractions();
        }
    }

    // ── gif / images ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("gif & image relay/permission")
    class Media {
        @Test
        void shouldRelayGifMedia() {
            Map<String, Object> media = map("url", "g.gif");
            controller.sendGif(map("media", media), ALICE);
            verify(chatRoutingService).relayGif(eq("alice"), eq(media));
        }

        @Test
        void shouldRelayGifWithNullMediaWhenAbsent() {
            controller.sendGif(map(), ALICE);
            verify(chatRoutingService).relayGif("alice", null);
        }

        @Test
        void shouldNoOpGifWhenPayloadNull() {
            controller.sendGif(null, ALICE);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldRequestImage() {
            controller.requestImage(ALICE);
            verify(imagePermissionService).requestImage("alice");
        }

        @Test
        void shouldAcceptImage() {
            controller.acceptImage(ALICE);
            verify(imagePermissionService).acceptImageRequest("alice");
        }

        @Test
        void shouldDeclineImage() {
            controller.declineImage(ALICE);
            verify(imagePermissionService).declineImageRequest("alice");
        }

        @Test
        void shouldSendImageMedia() {
            Map<String, Object> media = map("url", "p.png");
            controller.sendImage(map("media", media), ALICE);
            verify(chatRoutingService).relayImage(eq("alice"), eq(media));
        }

        @Test
        void shouldNoOpRequestImageWhenPrincipalNull() {
            controller.requestImage(null);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldNoOpSendImageWhenPayloadNull() {
            controller.sendImage(null, ALICE);
            verifyNoServiceInteractions();
        }
    }

    // ── reveal handshake (enum parsing) ─────────────────────────────────────────

    @Nested
    @DisplayName("reveal handshake")
    class Reveal {
        @Test
        void shouldRequestRevealWithParsedChannel() {
            controller.revealRequest(map("channel", "photo"), ALICE); // lowercase → PHOTO
            verify(revealService).requestReveal("alice", RevealChannel.PHOTO);
        }

        @Test
        void shouldAcceptRevealWithParsedChannel() {
            controller.revealAccept(map("channel", "PROFILE"), ALICE);
            verify(revealService).acceptReveal("alice", RevealChannel.PROFILE);
        }

        @Test
        void shouldDeclineRevealWithParsedChannel() {
            controller.revealDecline(map("channel", "voice"), ALICE);
            verify(revealService).declineReveal("alice", RevealChannel.VOICE);
        }

        @Test
        void shouldThrowWhenChannelInvalid() {
            // parseChannel does RevealChannel.valueOf(...) with no catch → IllegalArgumentException.
            assertThatThrownBy(() -> controller.revealRequest(map("channel", "telepathy"), ALICE))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(revealService);
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.revealRequest(map("channel", "photo"), null);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldNoOpWhenPayloadNull() {
            controller.revealAccept(null, ALICE);
            verifyNoServiceInteractions();
        }
    }

    // ── /match/timed-action (dispatch switch) ───────────────────────────────────

    @Nested
    @DisplayName("/match/timed-action")
    class TimedAction {
        @Test
        void shouldEndOnEnd() {
            controller.timedAction(map("action", "END"), ALICE);
            verify(matchmakingService).handleExit("alice");
        }

        @Test
        void shouldRematchOnRematch() {
            controller.timedAction(map("action", "rematch"), ALICE); // case-insensitive
            verify(matchmakingService).handleNewChat("alice");
        }

        @Test
        void shouldRevealProfileOnExchangeProfiles() {
            controller.timedAction(map("action", "EXCHANGE_PROFILES"), ALICE);
            verify(revealService).requestReveal("alice", RevealChannel.PROFILE);
        }

        @Test
        void shouldRevealProfileOnAddFriend() {
            controller.timedAction(map("action", "ADD_FRIEND"), ALICE);
            verify(revealService).requestReveal("alice", RevealChannel.PROFILE);
        }

        @Test
        void shouldContinueOnContinue() {
            controller.timedAction(map("action", "CONTINUE"), ALICE);
            verify(matchTimerService).continueRequest("alice");
        }

        @Test
        void shouldIgnoreUnknownAction() {
            controller.timedAction(map("action", "TELEPORT"), ALICE);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldIgnoreNullActionWithoutThrowing() {
            // String.valueOf(null) → "NULL" → default branch, no NPE.
            controller.timedAction(map(), ALICE);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.timedAction(map("action", "END"), null);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldNoOpWhenPayloadNull() {
            controller.timedAction(null, ALICE);
            verifyNoServiceInteractions();
        }
    }

    // ── exit / new-chat ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("exit & new-chat")
    class ExitNewChat {
        @Test
        void shouldExitChat() {
            controller.exitChat(ALICE);
            verify(matchmakingService).handleExit("alice");
        }

        @Test
        void shouldStartNewChat() {
            controller.newChat(ALICE);
            verify(matchmakingService).handleNewChat("alice");
        }

        @Test
        void shouldNoOpExitWhenPrincipalNull() {
            controller.exitChat(null);
            verifyNoServiceInteractions();
        }

        @Test
        void shouldNoOpNewChatWhenPrincipalNull() {
            controller.newChat(null);
            verifyNoServiceInteractions();
        }
    }
}
