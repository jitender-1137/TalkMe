package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatExplicitConsent;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStateResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.ConsentStatus;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.MessageMapper;
import com.chat.talkMe.repository.ChatExplicitConsentRepository;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ChatConsentServiceImpl} — per-chat mutual consent for
 * exchanging explicit content in a 1:1 chat.
 *
 * <p>Invariants exercised: membership + 1:1 gates (TM_121 / TM_141 / TM_494); the request state
 * machine (NONE/DECLINED → PENDING, idempotent on PENDING/GRANTED, capped after MAX_DECLINES);
 * accept releases held messages and clears the decline cap; decline drops held messages and counts
 * consecutive declines; only the OTHER party may accept/decline (TM_493); every mutation broadcasts
 * over STOMP and every transition asserts the exact TM_### on failure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatConsentServiceImpl (unit)")
class ChatConsentServiceImplTest {

    private static final String CHAT_UUID = UUID.randomUUID().toString();

    @Mock private ChatRepository chatRepository;
    @Mock private ChatMemberRepository chatMemberRepository;
    @Mock private ChatExplicitConsentRepository consentRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageMapper messageMapper;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private MessageService messageService;

    private ChatConsentServiceImpl service;

    private User me;
    private User other;
    private Chat chat;

    @BeforeEach
    void setUp() {
        service = new ChatConsentServiceImpl(chatRepository, chatMemberRepository, consentRepository,
                messageRepository, messageMapper, messagingTemplate, messageService);

        me = user(1L);
        other = user(2L);
        chat = Chat.builder().chatType(ChatType.PRIVATE).build();
        chat.setUuid(UUID.fromString(CHAT_UUID));

        // toResponse always counts held messages — default to none.
        lenient().when(messageRepository.findHeldForConsent(any(Chat.class))).thenReturn(List.of());
        lenient().when(consentRepository.save(any(ChatExplicitConsent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private User user(long id) {
        User u = User.builder().username("u" + id).name("U" + id).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    /** Wire a valid, member 1:1 chat lookup for {@code currentUser}. */
    private void memberChat(User currentUser) {
        when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
        when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                .thenReturn(Optional.of(mock(ChatMember.class)));
    }

    private ChatExplicitConsent consent(ConsentStatus status) {
        return ChatExplicitConsent.builder().chat(chat).status(status).build();
    }

    @Nested
    @DisplayName("loadMemberChat guard (shared by all methods)")
    class LoadMemberChat {

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getState(CHAT_UUID, me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("not a member → ForbiddenException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getState(CHAT_UUID, me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    @Nested
    @DisplayName("require1to1 guard (mutating methods)")
    class Require1to1 {

        @Test
        @DisplayName("group chat → ForbiddenException TM_494 on requestConsent")
        void groupRejected() {
            chat.setChatType(ChatType.GROUP);
            memberChat(me);

            assertThatThrownBy(() -> service.requestConsent(CHAT_UUID, me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_494"));

            verify(consentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getState")
    class GetState {

        @Test
        @DisplayName("no consent row → NONE, canRequest true, nothing held")
        void noConsentRow() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());

            ConsentStateResponse res = service.getState(CHAT_UUID, me);

            assertThat(res.getChatId()).isEqualTo(CHAT_UUID);
            assertThat(res.getStatus()).isEqualTo("NONE");
            assertThat(res.isCanRequest()).isTrue();
            assertThat(res.isCanRevoke()).isFalse();
            assertThat(res.isRequester()).isFalse();
            assertThat(res.isAwaitingMyAccept()).isFalse();
            assertThat(res.getDeclineCount()).isZero();
            assertThat(res.getHeldMessageCount()).isZero();
        }

        @Test
        @DisplayName("PENDING for the other party → awaitingMyAccept true, canRequest false")
        void pendingForOtherParty() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(other);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.getState(CHAT_UUID, me);

            assertThat(res.getStatus()).isEqualTo("PENDING");
            assertThat(res.isRequester()).isFalse();
            assertThat(res.isAwaitingMyAccept()).isTrue();
            assertThat(res.isCanRequest()).isFalse();
        }

        @Test
        @DisplayName("PENDING requested by me → isRequester true, not awaiting my accept")
        void pendingRequestedByMe() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.getState(CHAT_UUID, me);

            assertThat(res.isRequester()).isTrue();
            assertThat(res.isAwaitingMyAccept()).isFalse();
        }

        @Test
        @DisplayName("GRANTED → canRevoke true, canRequest false")
        void granted() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(consent(ConsentStatus.GRANTED)));

            ConsentStateResponse res = service.getState(CHAT_UUID, me);

            assertThat(res.getStatus()).isEqualTo("GRANTED");
            assertThat(res.isCanRevoke()).isTrue();
            assertThat(res.isCanRequest()).isFalse();
        }

        @Test
        @DisplayName("DECLINED under the cap → canRequest true")
        void declinedUnderCap() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.DECLINED);
            c.setDeclineCount(1);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.getState(CHAT_UUID, me);

            assertThat(res.isCanRequest()).isTrue();
            assertThat(res.getDeclineCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("DECLINED at the cap → canRequest false")
        void declinedAtCap() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.DECLINED);
            c.setDeclineCount(3);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.getState(CHAT_UUID, me);

            assertThat(res.isCanRequest()).isFalse();
        }

        @Test
        @DisplayName("held message count reflects only the current user's held messages")
        void heldCountForMe() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());
            Message mine = mock(Message.class);
            when(mine.getSender()).thenReturn(me);
            Message theirs = mock(Message.class);
            when(theirs.getSender()).thenReturn(other);
            when(messageRepository.findHeldForConsent(chat)).thenReturn(List.of(mine, theirs));

            ConsentStateResponse res = service.getState(CHAT_UUID, me);

            assertThat(res.getHeldMessageCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("requestConsent")
    class RequestConsent {

        @Test
        @DisplayName("from NONE → PENDING, requester recorded, broadcasts consent_requested")
        void fromNone() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());

            ConsentStateResponse res = service.requestConsent(CHAT_UUID, me);

            ArgumentCaptor<ChatExplicitConsent> cap = ArgumentCaptor.forClass(ChatExplicitConsent.class);
            verify(consentRepository).save(cap.capture());
            ChatExplicitConsent saved = cap.getValue();
            assertThat(saved.getStatus()).isEqualTo(ConsentStatus.PENDING);
            assertThat(saved.getRequestedBy()).isEqualTo(me);
            assertThat(saved.getRequestedAt()).isNotNull();
            assertThat(saved.getRespondedBy()).isNull();
            assertThat(saved.getRevokedBy()).isNull();
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID + "/messages"), any(Object.class));
            assertThat(res.getStatus()).isEqualTo("PENDING");
            assertThat(res.isRequester()).isTrue();
        }

        @Test
        @DisplayName("from DECLINED under the cap → re-request allowed → PENDING")
        void fromDeclinedUnderCap() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.DECLINED);
            c.setDeclineCount(2);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            service.requestConsent(CHAT_UUID, me);

            assertThat(c.getStatus()).isEqualTo(ConsentStatus.PENDING);
            verify(consentRepository).save(c);
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("from DECLINED at the cap → refused, no state change, no broadcast")
        void fromDeclinedAtCap() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.DECLINED);
            c.setDeclineCount(3);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.requestConsent(CHAT_UUID, me);

            assertThat(c.getStatus()).isEqualTo(ConsentStatus.DECLINED);
            verify(consentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
            assertThat(res.isCanRequest()).isFalse();
        }

        @Test
        @DisplayName("already PENDING → idempotent, no re-notify")
        void alreadyPending() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            service.requestConsent(CHAT_UUID, me);

            verify(consentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("already GRANTED → idempotent, no re-notify")
        void alreadyGranted() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(consent(ConsentStatus.GRANTED)));

            service.requestConsent(CHAT_UUID, me);

            verify(consentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("WS broadcast failure is swallowed — request still persists")
        void broadcastFailureSwallowed() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());
            doThrow(new RuntimeException("stomp down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            ConsentStateResponse res = service.requestConsent(CHAT_UUID, me);

            assertThat(res.getStatus()).isEqualTo("PENDING");
            verify(consentRepository).save(any());
        }
    }

    @Nested
    @DisplayName("revokeConsent")
    class RevokeConsent {

        @Test
        @DisplayName("no consent row → nothing to revoke, no save/broadcast")
        void noRow() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());

            ConsentStateResponse res = service.revokeConsent(CHAT_UUID, me);

            assertThat(res.getStatus()).isEqualTo("NONE");
            verify(consentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("not GRANTED (PENDING) → nothing to revoke")
        void notGranted() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(consent(ConsentStatus.PENDING)));

            service.revokeConsent(CHAT_UUID, me);

            verify(consentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("GRANTED → reset to NONE, revoker recorded, broadcasts consent_revoked")
        void grantedRevoked() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.GRANTED);
            c.setRequestedBy(other);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.revokeConsent(CHAT_UUID, me);

            assertThat(c.getStatus()).isEqualTo(ConsentStatus.NONE);
            assertThat(c.getRevokedBy()).isEqualTo(me);
            assertThat(c.getRevokedAt()).isNotNull();
            assertThat(c.getRequestedBy()).isNull();
            assertThat(c.getRespondedBy()).isNull();
            verify(consentRepository).save(c);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID + "/messages"), any(Object.class));
            assertThat(res.getStatus()).isEqualTo("NONE");
        }
    }

    @Nested
    @DisplayName("acceptConsent")
    class AcceptConsent {

        @Test
        @DisplayName("no consent row → NotFoundException TM_491")
        void noRow() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptConsent(CHAT_UUID, me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_491"));
        }

        @Test
        @DisplayName("already GRANTED → idempotent, no re-release, no broadcast")
        void alreadyGranted() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(consent(ConsentStatus.GRANTED)));

            ConsentStateResponse res = service.acceptConsent(CHAT_UUID, me);

            assertThat(res.getStatus()).isEqualTo("GRANTED");
            verify(consentRepository, never()).save(any());
            verify(messageService, never()).releaseHeldMessages(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("not PENDING (DECLINED) → ForbiddenException TM_492")
        void notPending() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(consent(ConsentStatus.DECLINED)));

            assertThatThrownBy(() -> service.acceptConsent(CHAT_UUID, me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_492"));
        }

        @Test
        @DisplayName("requester tries to self-accept → ForbiddenException TM_493")
        void selfAccept() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.acceptConsent(CHAT_UUID, me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_493"));

            verify(messageService, never()).releaseHeldMessages(any());
        }

        @Test
        @DisplayName("other party accepts → GRANTED, decline cap cleared, releases held, broadcasts")
        void otherPartyAccepts() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(other);
            c.setDeclineCount(2);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.acceptConsent(CHAT_UUID, me);

            assertThat(c.getStatus()).isEqualTo(ConsentStatus.GRANTED);
            assertThat(c.getRespondedBy()).isEqualTo(me);
            assertThat(c.getRespondedAt()).isNotNull();
            assertThat(c.getRevokedBy()).isNull();
            assertThat(c.getDeclineCount()).isZero();
            verify(consentRepository).save(c);
            verify(messageService).releaseHeldMessages(chat);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID + "/messages"), any(Object.class));
            assertThat(res.getStatus()).isEqualTo("GRANTED");
        }
    }

    @Nested
    @DisplayName("declineConsent")
    class DeclineConsent {

        @Test
        @DisplayName("no consent row → NotFoundException TM_491")
        void noRow() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.declineConsent(CHAT_UUID, me))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_491"));
        }

        @Test
        @DisplayName("already DECLINED → idempotent, no save/broadcast")
        void alreadyDeclined() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.DECLINED);
            c.setDeclineCount(1);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            ConsentStateResponse res = service.declineConsent(CHAT_UUID, me);

            assertThat(res.getStatus()).isEqualTo("DECLINED");
            verify(consentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("not PENDING (NONE) → ForbiddenException TM_492")
        void notPending() {
            memberChat(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(consent(ConsentStatus.NONE)));

            assertThatThrownBy(() -> service.declineConsent(CHAT_UUID, me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_492"));
        }

        @Test
        @DisplayName("requester tries to self-decline → ForbiddenException TM_493")
        void selfDecline() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(me);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.declineConsent(CHAT_UUID, me))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_493"));
        }

        @Test
        @DisplayName("other party declines with held messages → DECLINED, count++, drops held, broadcasts")
        void otherPartyDeclinesDropsHeld() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(other);
            c.setDeclineCount(1);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));
            Message held = mock(Message.class);
            lenient().when(held.getSender()).thenReturn(other);
            when(messageRepository.findHeldForConsent(chat)).thenReturn(List.of(held));

            ConsentStateResponse res = service.declineConsent(CHAT_UUID, me);

            assertThat(c.getStatus()).isEqualTo(ConsentStatus.DECLINED);
            assertThat(c.getRespondedBy()).isEqualTo(me);
            assertThat(c.getDeclineCount()).isEqualTo(2);
            verify(consentRepository).save(c);
            verify(messageRepository).deleteAll(List.of(held));
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID + "/messages"), any(Object.class));
            assertThat(res.getStatus()).isEqualTo("DECLINED");
        }

        @Test
        @DisplayName("decline with no held messages → no deleteAll call")
        void declineNoHeld() {
            memberChat(me);
            ChatExplicitConsent c = consent(ConsentStatus.PENDING);
            c.setRequestedBy(other);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(c));
            when(messageRepository.findHeldForConsent(chat)).thenReturn(List.of());

            service.declineConsent(CHAT_UUID, me);

            assertThat(c.getDeclineCount()).isEqualTo(1);
            verify(messageRepository, never()).deleteAll(any());
        }
    }
}
