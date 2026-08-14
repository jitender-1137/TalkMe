package com.neo.chat.match.impl;

import com.neo.chat.match.MatchServerEvent;
import com.neo.chat.match.MatchSession;
import com.neo.chat.match.SessionService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ImagePermissionServiceImpl} — brokers the anonymous image-permission
 * handshake over STOMP.
 *
 * <p>Key invariants: (1) no active session → {@link IllegalArgumentException}; (2) a REQUEST is
 * forwarded ONLY to the peer (never echoed to the requester) with an empty, identity-free payload;
 * (3) ACCEPT grants the session permission AND notifies BOTH users; (4) DECLINE notifies both but
 * grants nothing; (5) recipient resolution is symmetric (requester may be userA or userB).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImagePermissionServiceImpl (unit)")
class ImagePermissionServiceImplTest {

    private static final String USER_A = "alice";
    private static final String USER_B = "bob";
    private static final String QUEUE = "/queue/match";

    @Mock
    private SessionService sessionService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ImagePermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ImagePermissionServiceImpl(sessionService, messagingTemplate);
    }

    private MatchSession session() {
        return MatchSession.builder().id("sess-1").userA(USER_A).userB(USER_B).build();
    }

    @Nested
    @DisplayName("requestImage")
    class RequestImage {

        @Test
        @DisplayName("no active session → IllegalArgumentException, nothing sent")
        void noSession() {
            when(sessionService.getSessionByUser(USER_A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestImage(USER_A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active session found for user: " + USER_A);

            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("requester=userA → IMAGE_REQUEST_RECEIVED forwarded to userB only, empty payload")
        void forwardsToPeerFromA() {
            when(sessionService.getSessionByUser(USER_A)).thenReturn(Optional.of(session()));

            service.requestImage(USER_A);

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(USER_B), eq(QUEUE), ev.capture());
            verify(messagingTemplate, never()).convertAndSendToUser(eq(USER_A), anyString(), any());
            assertThat(ev.getValue().getEvent()).isEqualTo("IMAGE_REQUEST_RECEIVED");
            assertThat(ev.getValue().getPayload()).isEqualTo(Map.of());
            verify(sessionService, never()).grantImagePermission(anyString());
        }

        @Test
        @DisplayName("requester=userB → forwarded to userA (symmetric resolution)")
        void forwardsToPeerFromB() {
            when(sessionService.getSessionByUser(USER_B)).thenReturn(Optional.of(session()));

            service.requestImage(USER_B);

            verify(messagingTemplate).convertAndSendToUser(eq(USER_A), eq(QUEUE), any(MatchServerEvent.class));
            verify(messagingTemplate, never()).convertAndSendToUser(eq(USER_B), anyString(), any());
        }
    }

    @Nested
    @DisplayName("acceptImageRequest")
    class AcceptImageRequest {

        @Test
        @DisplayName("no active session → IllegalArgumentException, nothing granted or sent")
        void noSession() {
            when(sessionService.getSessionByUser(USER_A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptImageRequest(USER_A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active session found for user: " + USER_A);

            verify(sessionService, never()).grantImagePermission(anyString());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("grants session permission and notifies BOTH users with IMAGE_REQUEST_ACCEPTED")
        void grantsAndNotifiesBoth() {
            when(sessionService.getSessionByUser(USER_A)).thenReturn(Optional.of(session()));

            service.acceptImageRequest(USER_A);

            verify(sessionService).grantImagePermission("sess-1");

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(USER_A), eq(QUEUE), ev.capture());
            verify(messagingTemplate).convertAndSendToUser(eq(USER_B), eq(QUEUE), any(MatchServerEvent.class));
            assertThat(ev.getValue().getEvent()).isEqualTo("IMAGE_REQUEST_ACCEPTED");
            assertThat(ev.getValue().getPayload()).isEqualTo(Map.of());
        }
    }

    @Nested
    @DisplayName("declineImageRequest")
    class DeclineImageRequest {

        @Test
        @DisplayName("no active session → IllegalArgumentException, nothing sent")
        void noSession() {
            when(sessionService.getSessionByUser(USER_B)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.declineImageRequest(USER_B))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No active session found for user: " + USER_B);

            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("notifies BOTH users with IMAGE_REQUEST_DECLINED and grants nothing")
        void notifiesBothNoGrant() {
            when(sessionService.getSessionByUser(USER_B)).thenReturn(Optional.of(session()));

            service.declineImageRequest(USER_B);

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(USER_A), eq(QUEUE), ev.capture());
            verify(messagingTemplate).convertAndSendToUser(eq(USER_B), eq(QUEUE), any(MatchServerEvent.class));
            assertThat(ev.getValue().getEvent()).isEqualTo("IMAGE_REQUEST_DECLINED");
            assertThat(ev.getValue().getPayload()).isEqualTo(Map.of());
            verify(sessionService, never()).grantImagePermission(anyString());
        }
    }
}
