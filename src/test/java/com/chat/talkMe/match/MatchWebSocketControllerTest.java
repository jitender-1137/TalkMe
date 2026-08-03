package com.chat.talkMe.match;

import com.chat.talkMe.dto.request.MatchStartRequest;
import com.chat.talkMe.enums.RevealChannel;
import com.chat.talkMe.match.impl.MatchMessageBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MatchWebSocketController} — the STOMP {@code @MessageMapping}
 * entrypoint for the ephemeral matchmaking chat. Each handler is invoked directly (this is NOT an
 * HTTP controller, so no MockMvc) with a mocked {@link Principal} and asserts pure delegation to the
 * match services.
 *
 * <p>Key invariants under test: (1) every handler short-circuits to a no-op when the principal is
 * {@code null} (unauthenticated frame); (2) payload-bearing handlers also no-op on a {@code null}
 * payload; (3) each handler delegates to the correct service method with {@code principal.getName()};
 * (4) {@code /match/start} branches on presence of a filter body (legacy blind vs preference-aware);
 * (5) {@code /match/timed-action} routes each action verb to the right service and ignores unknowns;
 * (6) reveal handlers parse the {@code channel} payload into a {@link RevealChannel}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchWebSocketController (unit)")
class MatchWebSocketControllerTest {

    private static final String USER = "alice";

    @Mock private MatchmakingService matchmakingService;
    @Mock private ChatRoutingService chatRoutingService;
    @Mock private ImagePermissionService imagePermissionService;
    @Mock private MatchConsentService matchConsentService;
    @Mock private MatchMessageBufferService matchMessageBuffer;
    @Mock private RevealService revealService;
    @Mock private MatchTimerService matchTimerService;
    @Mock private Principal principal;

    private MatchWebSocketController controller;

    @BeforeEach
    void setUp() {
        controller = new MatchWebSocketController(matchmakingService, chatRoutingService,
                imagePermissionService, matchConsentService, matchMessageBuffer, revealService,
                matchTimerService);
    }

    /** Wires the mocked principal to return the test username. */
    private void authenticated() {
        when(principal.getName()).thenReturn(USER);
    }

    @Nested
    @DisplayName("startMatching")
    class StartMatching {

        @Test
        @DisplayName("null principal → no-op, matchmaking untouched")
        void nullPrincipal() {
            controller.startMatching(new MatchStartRequest(), null);

            verifyNoInteractions(matchmakingService);
        }

        @Test
        @DisplayName("null filters → legacy blind quick-match (single-arg overload)")
        void nullFiltersUsesBlindOverload() {
            authenticated();

            controller.startMatching(null, principal);

            verify(matchmakingService).startMatching(USER);
            verify(matchmakingService, never()).startMatching(anyString(), any());
        }

        @Test
        @DisplayName("filters present → preference-aware match (two-arg overload)")
        void filtersUsePreferenceOverload() {
            authenticated();
            MatchStartRequest filters = new MatchStartRequest();

            controller.startMatching(filters, principal);

            verify(matchmakingService).startMatching(USER, filters);
            verify(matchmakingService, never()).startMatching(USER);
        }
    }

    @Nested
    @DisplayName("resume")
    class Resume {

        @Test
        @DisplayName("null principal → buffer not flushed")
        void nullPrincipal() {
            controller.resume(null);

            verifyNoInteractions(matchMessageBuffer);
        }

        @Test
        @DisplayName("authenticated → flushes buffered events for the user")
        void flushes() {
            authenticated();

            controller.resume(principal);

            verify(matchMessageBuffer).flush(USER);
        }
    }

    @Nested
    @DisplayName("sendMessage")
    class SendMessage {

        @Test
        @DisplayName("null principal → no relay")
        void nullPrincipal() {
            controller.sendMessage(Map.of("content", "hi"), null);

            verifyNoInteractions(chatRoutingService);
        }

        @Test
        @DisplayName("null payload → no relay")
        void nullPayload() {
            controller.sendMessage(null, principal);

            verify(chatRoutingService, never()).relayMessage(anyString(), any(), any());
        }

        @Test
        @DisplayName("relays content + clientId from the payload")
        void relays() {
            authenticated();
            Map<String, Object> payload = Map.of("content", "hello", "clientId", "c1");

            controller.sendMessage(payload, principal);

            verify(chatRoutingService).relayMessage(USER, "hello", "c1");
        }

        @Test
        @DisplayName("missing content/clientId keys relay as nulls")
        void missingKeysRelayNull() {
            authenticated();

            controller.sendMessage(new HashMap<>(), principal);

            verify(chatRoutingService).relayMessage(USER, null, null);
        }
    }

    @Nested
    @DisplayName("typing")
    class Typing {

        @Test
        @DisplayName("null principal → no relay")
        void nullPrincipal() {
            controller.typing(true, null);

            verifyNoInteractions(chatRoutingService);
        }

        @Test
        @DisplayName("forwards the typing flag to the routing service")
        void forwards() {
            authenticated();

            controller.typing(true, principal);

            verify(chatRoutingService).relayTyping(USER, true);
        }
    }

    @Nested
    @DisplayName("acceptConsent")
    class AcceptConsent {

        @Test
        @DisplayName("null principal → consent service untouched")
        void nullPrincipal() {
            controller.acceptConsent(null);

            verifyNoInteractions(matchConsentService);
        }

        @Test
        @DisplayName("delegates to acceptConsent for the user")
        void delegates() {
            authenticated();

            controller.acceptConsent(principal);

            verify(matchConsentService).acceptConsent(USER);
        }
    }

    @Nested
    @DisplayName("declineConsent")
    class DeclineConsent {

        @Test
        @DisplayName("null principal → consent service untouched")
        void nullPrincipal() {
            controller.declineConsent(null);

            verifyNoInteractions(matchConsentService);
        }

        @Test
        @DisplayName("delegates to declineConsent for the user")
        void delegates() {
            authenticated();

            controller.declineConsent(principal);

            verify(matchConsentService).declineConsent(USER);
        }
    }

    @Nested
    @DisplayName("sendGif")
    class SendGif {

        @Test
        @DisplayName("null principal → no relay")
        void nullPrincipal() {
            controller.sendGif(Map.of("media", Map.of()), null);

            verifyNoInteractions(chatRoutingService);
        }

        @Test
        @DisplayName("null payload → no relay")
        void nullPayload() {
            controller.sendGif(null, principal);

            verify(chatRoutingService, never()).relayGif(anyString(), any());
        }

        @Test
        @DisplayName("extracts the media map and relays it")
        void relaysMedia() {
            authenticated();
            Map<String, Object> media = Map.of("url", "x.mp4");

            controller.sendGif(Map.of("media", media), principal);

            verify(chatRoutingService).relayGif(USER, media);
        }
    }

    @Nested
    @DisplayName("requestImage / acceptImage / declineImage")
    class ImagePermission {

        @Test
        @DisplayName("requestImage null principal → untouched")
        void requestNullPrincipal() {
            controller.requestImage(null);

            verifyNoInteractions(imagePermissionService);
        }

        @Test
        @DisplayName("requestImage delegates")
        void requestDelegates() {
            authenticated();

            controller.requestImage(principal);

            verify(imagePermissionService).requestImage(USER);
        }

        @Test
        @DisplayName("acceptImage null principal → untouched")
        void acceptNullPrincipal() {
            controller.acceptImage(null);

            verifyNoInteractions(imagePermissionService);
        }

        @Test
        @DisplayName("acceptImage delegates")
        void acceptDelegates() {
            authenticated();

            controller.acceptImage(principal);

            verify(imagePermissionService).acceptImageRequest(USER);
        }

        @Test
        @DisplayName("declineImage null principal → untouched")
        void declineNullPrincipal() {
            controller.declineImage(null);

            verifyNoInteractions(imagePermissionService);
        }

        @Test
        @DisplayName("declineImage delegates")
        void declineDelegates() {
            authenticated();

            controller.declineImage(principal);

            verify(imagePermissionService).declineImageRequest(USER);
        }
    }

    @Nested
    @DisplayName("sendImage")
    class SendImage {

        @Test
        @DisplayName("null principal → no relay")
        void nullPrincipal() {
            controller.sendImage(Map.of("media", Map.of()), null);

            verifyNoInteractions(chatRoutingService);
        }

        @Test
        @DisplayName("null payload → no relay")
        void nullPayload() {
            controller.sendImage(null, principal);

            verify(chatRoutingService, never()).relayImage(anyString(), any());
        }

        @Test
        @DisplayName("extracts the media map and relays it")
        void relaysMedia() {
            authenticated();
            Map<String, Object> media = Map.of("url", "x.jpg");

            controller.sendImage(Map.of("media", media), principal);

            verify(chatRoutingService).relayImage(USER, media);
        }
    }

    @Nested
    @DisplayName("revealRequest / revealAccept / revealDecline")
    class Reveal {

        @Test
        @DisplayName("revealRequest null principal → reveal service untouched")
        void requestNullPrincipal() {
            controller.revealRequest(Map.of("channel", "PROFILE"), null);

            verifyNoInteractions(revealService);
        }

        @Test
        @DisplayName("revealRequest null payload → reveal service untouched")
        void requestNullPayload() {
            controller.revealRequest(null, principal);

            verify(revealService, never()).requestReveal(anyString(), any());
        }

        @Test
        @DisplayName("revealRequest parses the channel and delegates")
        void requestDelegates() {
            authenticated();

            controller.revealRequest(Map.of("channel", "PROFILE"), principal);

            verify(revealService).requestReveal(USER, RevealChannel.PROFILE);
        }

        @Test
        @DisplayName("channel parsing is case-insensitive and trimmed")
        void channelParsingLenient() {
            authenticated();

            controller.revealRequest(Map.of("channel", "  photo  "), principal);

            verify(revealService).requestReveal(USER, RevealChannel.PHOTO);
        }

        @Test
        @DisplayName("unknown channel value → IllegalArgumentException")
        void unknownChannelThrows() {
            authenticated();

            assertThatThrownBy(() -> controller.revealRequest(Map.of("channel", "BOGUS"), principal))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("revealAccept parses the channel and delegates")
        void acceptDelegates() {
            authenticated();

            controller.revealAccept(Map.of("channel", "VOICE"), principal);

            verify(revealService).acceptReveal(USER, RevealChannel.VOICE);
        }

        @Test
        @DisplayName("revealAccept null payload → untouched")
        void acceptNullPayload() {
            controller.revealAccept(null, principal);

            verify(revealService, never()).acceptReveal(anyString(), any());
        }

        @Test
        @DisplayName("revealDecline parses the channel and delegates")
        void declineDelegates() {
            authenticated();

            controller.revealDecline(Map.of("channel", "PROFILE"), principal);

            verify(revealService).declineReveal(USER, RevealChannel.PROFILE);
        }

        @Test
        @DisplayName("revealDecline null principal → untouched")
        void declineNullPrincipal() {
            controller.revealDecline(Map.of("channel", "PROFILE"), null);

            verifyNoInteractions(revealService);
        }
    }

    @Nested
    @DisplayName("timedAction")
    class TimedAction {

        @Test
        @DisplayName("null principal → nothing routed")
        void nullPrincipal() {
            controller.timedAction(Map.of("action", "END"), null);

            verifyNoInteractions(matchmakingService, revealService, matchTimerService);
        }

        @Test
        @DisplayName("null payload → nothing routed")
        void nullPayload() {
            controller.timedAction(null, principal);

            verifyNoInteractions(matchmakingService, revealService, matchTimerService);
        }

        @Test
        @DisplayName("END → handleExit")
        void end() {
            authenticated();

            controller.timedAction(Map.of("action", "END"), principal);

            verify(matchmakingService).handleExit(USER);
        }

        @Test
        @DisplayName("REMATCH → handleNewChat")
        void rematch() {
            authenticated();

            controller.timedAction(Map.of("action", "REMATCH"), principal);

            verify(matchmakingService).handleNewChat(USER);
        }

        @Test
        @DisplayName("EXCHANGE_PROFILES → PROFILE reveal request")
        void exchangeProfiles() {
            authenticated();

            controller.timedAction(Map.of("action", "EXCHANGE_PROFILES"), principal);

            verify(revealService).requestReveal(USER, RevealChannel.PROFILE);
        }

        @Test
        @DisplayName("ADD_FRIEND → PROFILE reveal request")
        void addFriend() {
            authenticated();

            controller.timedAction(Map.of("action", "ADD_FRIEND"), principal);

            verify(revealService).requestReveal(USER, RevealChannel.PROFILE);
        }

        @Test
        @DisplayName("CONTINUE → matchTimerService.continueRequest")
        void continueAction() {
            authenticated();

            controller.timedAction(Map.of("action", "CONTINUE"), principal);

            verify(matchTimerService).continueRequest(USER);
        }

        @Test
        @DisplayName("action is normalized (lowercase/whitespace) before switching")
        void normalizesAction() {
            authenticated();

            controller.timedAction(Map.of("action", "  end  "), principal);

            verify(matchmakingService).handleExit(USER);
        }

        @Test
        @DisplayName("unknown action → ignored, no service routed")
        void unknownIgnored() {
            authenticated();

            controller.timedAction(Map.of("action", "FLY_TO_MOON"), principal);

            verifyNoInteractions(matchmakingService, revealService, matchTimerService);
        }
    }

    @Nested
    @DisplayName("exitChat")
    class ExitChat {

        @Test
        @DisplayName("null principal → untouched")
        void nullPrincipal() {
            controller.exitChat(null);

            verifyNoInteractions(matchmakingService);
        }

        @Test
        @DisplayName("delegates to handleExit")
        void delegates() {
            authenticated();

            controller.exitChat(principal);

            verify(matchmakingService).handleExit(USER);
        }
    }

    @Nested
    @DisplayName("newChat")
    class NewChat {

        @Test
        @DisplayName("null principal → untouched")
        void nullPrincipal() {
            controller.newChat(null);

            verifyNoInteractions(matchmakingService);
        }

        @Test
        @DisplayName("delegates to handleNewChat")
        void delegates() {
            authenticated();

            controller.newChat(principal);

            verify(matchmakingService).handleNewChat(USER);
        }
    }
}
