package com.neo.chat.service.impl;

import com.neo.chat.crypto.MessageCryptoService;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatExplicitConsent;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.ChatSettings;
import com.neo.chat.domain.Friend;
import com.neo.chat.domain.Message;
import com.neo.chat.domain.MessageAttachment;
import com.neo.chat.domain.MessageReaction;
import com.neo.chat.domain.MessageReadReceipt;
import com.neo.chat.domain.MessageStar;
import com.neo.chat.domain.OutboxEvent;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSetting;
import com.neo.chat.dto.request.ReactToMessageRequest;
import com.neo.chat.dto.request.SendMessageRequest;
import com.neo.chat.dto.response.MessagePageResponse;
import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.enums.ChatType;
import com.neo.chat.enums.ConsentStatus;
import com.neo.chat.enums.MemberRole;
import com.neo.chat.enums.MessageType;
import com.neo.chat.enums.MessagingPrivacy;
import com.neo.chat.enums.ModerationStatus;
import com.neo.chat.enums.RoomMode;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.mapper.MessageMapper;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.ChatExplicitConsentRepository;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.MessageAttachmentRepository;
import com.neo.chat.repository.MessageReactionRepository;
import com.neo.chat.repository.MessageReadReceiptRepository;
import com.neo.chat.repository.MessageRepository;
import com.neo.chat.repository.MessageStarRepository;
import com.neo.chat.repository.OutboxEventRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.GroupAuthzService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.storage.MediaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MessageServiceImpl} — the core chat message engine
 * (send / system / pin / star / edit / delete / self-destruct / reactions / search / paging).
 *
 * <p>Every public method is enumerated for its positive branches and every {@code TM_###}
 * error path, plus the transactional-outbox failure that must surface as
 * {@link IllegalStateException}. Crypto/moderation/mapper are stubbed as pass-throughs so
 * the branching logic — not the collaborators — is what's under test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageServiceImpl (unit)")
class MessageServiceImplTest {

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageAttachmentRepository messageAttachmentRepository;
    @Mock
    private MessageReadReceiptRepository readReceiptRepository;
    @Mock
    private MessageReactionRepository messageReactionRepository;
    @Mock
    private MessageStarRepository messageStarRepository;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private PresenceService presenceService;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private ChatExplicitConsentRepository consentRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private GroupAuthzService groupAuthzService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageCryptoService messageCryptoService;
    @Mock
    private MediaStorage mediaStorage;

    private MessageServiceImpl service;

    private User currentUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        service = new MessageServiceImpl(
                chatRepository, chatMemberRepository, messageRepository, messageAttachmentRepository,
                readReceiptRepository, messageReactionRepository, messageStarRepository, messageMapper,
                blockUserRepository, eventPublisher, messagingTemplate, outboxEventRepository,
                objectMapper, presenceService, moderationService, consentRepository, friendRepository,
                userSettingRepository, groupAuthzService, userRepository, messageCryptoService, mediaStorage);

        currentUser = user(1L, "alice", "Alice");
        otherUser = user(2L, "bob", "Bob");

        // Shared pass-through stubs (lenient — not every test exercises them).
        lenient().when(messageCryptoService.decrypt(anyLong(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(messageCryptoService.encrypt(anyLong(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(moderationService.moderateText(any())).thenReturn(ModerationResult.clean());
        lenient().when(messageMapper.toMessageResponse(any())).thenAnswer(inv -> responseFor(inv.getArgument(0)));
        lenient().when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messageStarRepository.findStarredMessageIds(anyLong(), any())).thenReturn(List.of());
        try {
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) {
            // writeValueAsString declares a checked exception; stubbing never throws.
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private static User user(long id, String username, String name) {
        User u = User.builder().username(username).name(name).profileImage("/img/" + username + ".png").build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private ChatMember member(Chat chat, User user, MemberRole role) {
        ChatMember m = ChatMember.builder().chat(chat).user(user).build();
        m.setRole(role);
        m.setId(user.getId() * 10);
        return m;
    }

    private Chat chat(long id, ChatType type) {
        Chat chat = Chat.builder().chatType(type).settings(ChatSettings.builder().build()).build();
        chat.setId(id);
        chat.setUuid(UUID.randomUUID());
        chat.setMembers(new ArrayList<>());
        return chat;
    }

    /**
     * A 2-party chat (currentUser + otherUser) of the given type with both members active.
     */
    private Chat twoPartyChat(long id, ChatType type) {
        Chat chat = chat(id, type);
        chat.getMembers().add(member(chat, currentUser, MemberRole.MEMBER));
        chat.getMembers().add(member(chat, otherUser, MemberRole.MEMBER));
        return chat;
    }

    private Message message(long id, Chat chat, User sender, MessageType type) {
        Message m = Message.builder().chat(chat).sender(sender).messageType(type).content("hi").build();
        m.setId(id);
        m.setUuid(UUID.randomUUID());
        return m;
    }

    private MessageResponse responseFor(Message m) {
        return MessageResponse.builder()
                .id(m != null && m.getUuid() != null ? m.getUuid().toString() : "resp-id")
                .sequenceNumber(m != null ? m.getId() : null)
                .build();
    }

    private SendMessageRequest textRequest(String content) {
        SendMessageRequest r = new SendMessageRequest();
        r.setContent(content);
        r.setMessageType("TEXT");
        return r;
    }

    // ── sendMessage ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendMessage")
    class SendMessage {

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            String uuid = UUID.randomUUID().toString();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendMessage(uuid, textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("deleted chat → NotFoundException TM_121")
        void deletedChat() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            chat.setDeleted(true);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("caller not a member → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("former member (leftAt set) → ForbiddenException TM_141")
        void formerMember() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            me.setLeftAt(Instant.now());
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("channel read-only (canSend=false) → ForbiddenException TM_294")
        void channelReadOnly() {
            Chat chat = twoPartyChat(10L, ChatType.CHANNEL);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(false);

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_294"));
        }

        @Test
        @DisplayName("group send blocked (canSend=false) → ForbiddenException TM_295")
        void groupSendBlocked() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(false);

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_295"));
        }

        @Test
        @DisplayName("slow mode active for a non-admin → TooManyRequestsException TM_296")
        void slowMode() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.getSettings().setSlowModeSeconds(30);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            Message last = message(99L, chat, currentUser, MessageType.TEXT);
            last.setCreatedAt(Instant.now()); // within the 30s window
            when(messageRepository.findFirstByChatAndSenderOrderByIdDesc(chat, currentUser))
                    .thenReturn(Optional.of(last));

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(TooManyRequestsException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_296"));
        }

        @Test
        @DisplayName("idempotent retry (same clientId) → returns the existing message, saves nothing")
        void idempotentRetry() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            Message existing = message(50L, chat, currentUser, MessageType.TEXT);
            when(messageRepository.findFirstByChatAndSenderAndClientId(chat, currentUser, "cid-1"))
                    .thenReturn(Optional.of(existing));
            SendMessageRequest req = textRequest("hi");
            req.setClientId("cid-1");

            MessageResponse res = service.sendMessage(chat.getUuid().toString(), req, currentUser);

            assertThat(res.getId()).isEqualTo(existing.getUuid().toString());
            verify(messageRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("sender has blocked the contact → ForbiddenException TM_142")
        void senderBlockedContact() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(blockUserRepository.existsByUserAndBlocked(currentUser, otherUser)).thenReturn(true);

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_142"));
        }

        @Test
        @DisplayName("recipient is FRIENDS_ONLY and sender is not a friend → ForbiddenException TM_143")
        void friendsOnlyNonFriend() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(blockUserRepository.existsByUserAndBlocked(currentUser, otherUser)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(otherUser, currentUser)).thenReturn(false);
            UserSetting setting = UserSetting.builder().user(otherUser).build();
            setting.setMessagingPrivacy(MessagingPrivacy.FRIENDS_ONLY);
            when(userSettingRepository.findByUser(otherUser)).thenReturn(Optional.of(setting));
            when(friendRepository.findByUserAndFriend(currentUser, otherUser)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_143"));
        }

        @Test
        @DisplayName("FRIENDS_ONLY but sender IS a friend → message is delivered")
        void friendsOnlyFriendAllowed() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            UserSetting setting = UserSetting.builder().user(otherUser).build();
            setting.setMessagingPrivacy(MessagingPrivacy.FRIENDS_ONLY);
            when(userSettingRepository.findByUser(otherUser)).thenReturn(Optional.of(setting));
            when(friendRepository.findByUserAndFriend(currentUser, otherUser))
                    .thenReturn(Optional.of(Friend.builder().user(currentUser).friend(otherUser).build()));

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            verify(messageRepository).save(any(Message.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("nominal group text send → persists, self-receipt, touch, outbox + broadcast")
        void nominalGroupSend() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getMessageType()).isEqualTo(MessageType.TEXT);
            assertThat(saved.getValue().getModerationStatus()).isEqualTo(ModerationStatus.CLEAN);
            verify(readReceiptRepository).save(any(MessageReadReceipt.class));
            verify(chatRepository).touchUpdatedAt(eq(10L), any(Instant.class));
            verify(outboxEventRepository).save(any(OutboxEvent.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("recipient has blocked the sender → saved isBlocked, no broadcast, no outbox")
        void recipientBlockedSender() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(blockUserRepository.existsByUserAndBlocked(currentUser, otherUser)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(otherUser, currentUser)).thenReturn(true);

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().isBlocked()).isTrue();
            verify(outboxEventRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("explicit text in a 1:1 without consent → saved BLOCKED_PENDING_CONSENT, not broadcast")
        void explicitPrivateHeld() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(moderationService.moderateText(any()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.SEXUAL, 0.9, List.of()));
            when(consentRepository.findByChat(chat)).thenReturn(Optional.empty());

            service.sendMessage(chat.getUuid().toString(), textRequest("dirty"), currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getModerationStatus()).isEqualTo(ModerationStatus.BLOCKED_PENDING_CONSENT);
            verify(outboxEventRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("explicit text in a 1:1 WITH granted consent → CLEAN and delivered")
        void explicitPrivateGranted() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(moderationService.moderateText(any()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.SEXUAL, 0.9, List.of()));
            ChatExplicitConsent consent = ChatExplicitConsent.builder().chat(chat).build();
            consent.setStatus(ConsentStatus.GRANTED);
            when(consentRepository.findByChat(chat)).thenReturn(Optional.of(consent));

            service.sendMessage(chat.getUuid().toString(), textRequest("dirty"), currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getModerationStatus()).isEqualTo(ModerationStatus.CLEAN);
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("explicit text in a clean group → ContentModerationException (TM_490)")
        void explicitGroupHardBlocked() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.setAllowExplicitContent(false);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            when(moderationService.moderateText(any()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.ABUSE, 0.9, List.of()));

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("dirty"), currentUser))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_490"));
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit text in an 18+ group (allowExplicit) → allowed and delivered")
        void explicitGroupAllowed() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.setAllowExplicitContent(true);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            when(moderationService.moderateText(any()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.ABUSE, 0.9, List.of()));

            service.sendMessage(chat.getUuid().toString(), textRequest("dirty"), currentUser);

            verify(messageRepository).save(any(Message.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("media send → attachment persisted alongside the message")
        void mediaSendPersistsAttachment() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(mediaStorage.localCopy(any())).thenReturn(Optional.empty());
            SendMessageRequest req = new SendMessageRequest();
            req.setMessageType("IMAGE");
            req.setContent("caption");
            req.setFileUrl("/media/x.jpg");
            req.setFileName("x.jpg");
            req.setFileSize(123L);
            req.setSelfDestructSeconds(10);

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            verify(messageAttachmentRepository).save(any(MessageAttachment.class));
            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getSelfDestructSeconds()).isEqualTo(10); // media + 1:1 arms self-destruct
        }

        @Test
        @DisplayName("ephemeral room → live broadcast only, nothing persisted")
        void ephemeralRoomNotPersisted() {
            Chat chat = twoPartyChat(10L, ChatType.ROOM);
            chat.setRoomMode(RoomMode.SLEEP_COMPANION);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);

            MessageResponse res = service.sendMessage(chat.getUuid().toString(), textRequest("night"), currentUser);

            assertThat(res).isNotNull();
            verify(messageRepository, never()).save(any());
            verify(outboxEventRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
            // Delivered live to the room topic + the peer's personal queue.
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), anyString(), any());
        }

        @Test
        @DisplayName("outbox persistence failure → IllegalStateException (send is not acknowledged)")
        void outboxFailurePropagates() throws Exception {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to persist outbox event");
        }
    }

    // ── sendSystemMessage ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendSystemMessage")
    class SendSystemMessage {

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendSystemMessage(UUID.randomUUID().toString(),
                    currentUser, "{\"e\":1}", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("nominal → persists SYSTEM message, touches chat, outbox + broadcast")
        void nominal() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));

            service.sendSystemMessage(chat.getUuid().toString(), currentUser, "{\"e\":1}", currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getMessageType()).isEqualTo(MessageType.SYSTEM);
            assertThat(saved.getValue().getContent()).isEqualTo("{\"e\":1}");
            verify(chatRepository).touchUpdatedAt(eq(10L), any(Instant.class));
            verify(outboxEventRepository).save(any(OutboxEvent.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("null currentUser → every member (incl. actor) is a recipient")
        void nullCurrentUserBroadcastsToAll() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));

            service.sendSystemMessage(chat.getUuid().toString(), currentUser, "{\"e\":1}", null);

            verify(messageRepository).save(any(Message.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }
    }

    // ── setMessagePinned ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setMessagePinned")
    class SetMessagePinned {

        @Test
        @DisplayName("pin in a 1:1 → sets pin metadata, saves, broadcasts message_pinned")
        void pinPrivate() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.setMessagePinned(chat.getUuid().toString(), msg.getUuid().toString(), true, currentUser);

            assertThat(msg.isPinned()).isTrue();
            assertThat(msg.getPinnedBy()).isEqualTo(currentUser.getId());
            assertThat(msg.getPinnedAt()).isNotNull();
            verify(messageRepository).save(msg);
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("unpin → clears pin metadata")
        void unpin() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            msg.setPinned(true);
            msg.setPinnedBy(currentUser.getId());
            msg.setPinnedAt(Instant.now());
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.setMessagePinned(chat.getUuid().toString(), msg.getUuid().toString(), false, currentUser);

            assertThat(msg.isPinned()).isFalse();
            assertThat(msg.getPinnedBy()).isNull();
            assertThat(msg.getPinnedAt()).isNull();
        }

        @Test
        @DisplayName("group member below the who-can-pin role → ForbiddenException TM_291")
        void groupInsufficientRole() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.getSettings().setWhoCanPin(MemberRole.ADMIN);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            assertThatThrownBy(() -> service.setMessagePinned(chat.getUuid().toString(),
                    msg.getUuid().toString(), true, currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_291"));
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("group admin with sufficient role → pins successfully")
        void groupAdminPins() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.getSettings().setWhoCanPin(MemberRole.ADMIN);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            ChatMember me = member(chat, currentUser, MemberRole.ADMIN);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.setMessagePinned(chat.getUuid().toString(), msg.getUuid().toString(), true, currentUser);

            assertThat(msg.isPinned()).isTrue();
            verify(messageRepository).save(msg);
        }

        @Test
        @DisplayName("message from another chat → ForbiddenException TM_162")
        void wrongChat() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Chat otherChat = chat(11L, ChatType.PRIVATE);
            Message msg = message(50L, otherChat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            assertThatThrownBy(() -> service.setMessagePinned(chat.getUuid().toString(),
                    msg.getUuid().toString(), true, currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_162"));
        }
    }

    // ── setMessageStarred ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setMessageStarred")
    class SetMessageStarred {

        @Test
        @DisplayName("star a not-yet-starred message → persists a star row")
        void starNew() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(messageStarRepository.findByMessageAndUser(msg, currentUser)).thenReturn(Optional.empty());

            service.setMessageStarred(chat.getUuid().toString(), msg.getUuid().toString(), true, currentUser);

            verify(messageStarRepository).save(any(MessageStar.class));
        }

        @Test
        @DisplayName("star an already-starred message → idempotent, no duplicate row")
        void starIdempotent() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(messageStarRepository.findByMessageAndUser(msg, currentUser))
                    .thenReturn(Optional.of(MessageStar.builder().message(msg).user(currentUser).build()));

            service.setMessageStarred(chat.getUuid().toString(), msg.getUuid().toString(), true, currentUser);

            verify(messageStarRepository, never()).save(any());
        }

        @Test
        @DisplayName("unstar → deletes the star row")
        void unstar() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.setMessageStarred(chat.getUuid().toString(), msg.getUuid().toString(), false, currentUser);

            verify(messageStarRepository).deleteByMessageAndUser(msg, currentUser);
            verify(messageStarRepository, never()).save(any());
        }

        @Test
        @DisplayName("not a member of the chat → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setMessageStarred(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), true, currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ── getStarredMessages ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStarredMessages")
    class GetStarredMessages {

        @Test
        @DisplayName("returns each response flagged starred; default limit when <= 0")
        void nominalDefaultLimit() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message m = message(50L, chat, currentUser, MessageType.TEXT);
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(messageStarRepository.findStarredMessages(eq(currentUser.getId()), pageable.capture()))
                    .thenReturn(List.of(m));

            List<MessageResponse> res = service.getStarredMessages(currentUser, 0);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).isStarred()).isTrue();
            assertThat(pageable.getValue().getPageSize()).isEqualTo(100); // limit<=0 → 100
        }

        @Test
        @DisplayName("caps the limit at 200")
        void capsLimit() {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(messageStarRepository.findStarredMessages(eq(currentUser.getId()), pageable.capture()))
                    .thenReturn(List.of());

            List<MessageResponse> res = service.getStarredMessages(currentUser, 500);

            assertThat(res).isEmpty();
            assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
        }
    }

    // ── getMessages (cursor paging) ───────────────────────────────────────────────

    @Nested
    @DisplayName("getMessages")
    class GetMessages {

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getMessages(UUID.randomUUID().toString(), null, 30, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("not a member → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getMessages(chat.getUuid().toString(), null, 30, currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("nominal page → items mapped, hasMore false, nextCursor = oldest sequence")
        void nominalPage() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            Message newer = message(100L, chat, currentUser, MessageType.TEXT);
            Message older = message(99L, chat, otherUser, MessageType.TEXT);
            when(messageRepository.findMessagesBeforeCursor(any(), anyLong(), any(), any(), any(), any()))
                    .thenReturn(new ArrayList<>(List.of(newer, older)));

            MessagePageResponse res = service.getMessages(chat.getUuid().toString(), null, 30, currentUser);

            assertThat(res.getItems()).hasSize(2);
            assertThat(res.isHasMore()).isFalse();
            assertThat(res.getNextCursor()).isEqualTo(99L);
        }

        @Test
        @DisplayName("more rows than the limit → hasMore true and page is trimmed")
        void hasMoreTrims() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            Message a = message(100L, chat, currentUser, MessageType.TEXT);
            Message b = message(99L, chat, otherUser, MessageType.TEXT);
            // limit=1 → safeLimit 1 → fetch 2 rows to detect "more".
            when(messageRepository.findMessagesBeforeCursor(any(), anyLong(), any(), any(), any(), any()))
                    .thenReturn(new ArrayList<>(List.of(a, b)));

            MessagePageResponse res = service.getMessages(chat.getUuid().toString(), null, 1, currentUser);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.isHasMore()).isTrue();
            assertThat(res.getNextCursor()).isEqualTo(100L);
        }

        @Test
        @DisplayName("empty chat → no items, nextCursor null, no NPE")
        void emptyPage() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findMessagesBeforeCursor(any(), anyLong(), any(), any(), any(), any()))
                    .thenReturn(new ArrayList<>());

            MessagePageResponse res = service.getMessages(chat.getUuid().toString(), null, 30, currentUser);

            assertThat(res.getItems()).isEmpty();
            assertThat(res.getNextCursor()).isNull();
            assertThat(res.isHasMore()).isFalse();
        }
    }

    // ── getMessagesAfter ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMessagesAfter")
    class GetMessagesAfter {

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getMessagesAfter(UUID.randomUUID().toString(), 5L, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("not a member → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getMessagesAfter(chat.getUuid().toString(), 5L, currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("nominal → returns mapped messages after the sequence")
        void nominal() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findMessagesAfter(any(), anyLong(), any(), any(), eq(5L)))
                    .thenReturn(new ArrayList<>(List.of(message(6L, chat, otherUser, MessageType.TEXT))));

            List<MessageResponse> res = service.getMessagesAfter(chat.getUuid().toString(), 5L, currentUser);

            assertThat(res).hasSize(1);
        }
    }

    // ── searchMessages ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("searchMessages")
    class SearchMessages {

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.searchMessages(UUID.randomUUID().toString(),
                    "hi", PageRequest.of(0, 20), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("not a member → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.searchMessages(chat.getUuid().toString(),
                    "hi", PageRequest.of(0, 20), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("nominal → maps the matching page")
        void nominal() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            Message m = message(50L, chat, otherUser, MessageType.TEXT);
            Page<Message> page = new PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
            when(messageRepository.searchMessagesInChat(any(), eq("hi"), anyLong(), any(), any()))
                    .thenReturn(page);

            Page<MessageResponse> res = service.searchMessages(chat.getUuid().toString(),
                    "hi", PageRequest.of(0, 20), currentUser);

            assertThat(res.getTotalElements()).isEqualTo(1);
            assertThat(res.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("ghost recipient → sender-visible status capped at SENT")
        void ghostStatusCapped() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            Message m = message(50L, chat, currentUser, MessageType.TEXT);
            MessageReadReceipt r = MessageReadReceipt.builder().message(m).user(otherUser).status("READ").build();
            m.getReadReceipts().add(r);
            Page<Message> page = new PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
            when(messageRepository.searchMessagesInChat(any(), eq("hi"), anyLong(), any(), any()))
                    .thenReturn(page);
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of(otherUser.getId()));

            Page<MessageResponse> res = service.searchMessages(chat.getUuid().toString(),
                    "hi", PageRequest.of(0, 20), currentUser);

            assertThat(res.getContent().get(0).getStatus()).isEqualTo("SENT");
        }
    }

    // ── deleteMessage ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteMessage")
    class DeleteMessage {

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteMessage(UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("not a member → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteMessage(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("message not found → NotFoundException TM_161")
        void messageNotFound() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteMessage(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_161"));
        }

        @Test
        @DisplayName("message from a different chat → ForbiddenException TM_162")
        void wrongChat() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Chat otherChat = chat(11L, ChatType.PRIVATE);
            Message msg = message(50L, otherChat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            assertThatThrownBy(() -> service.deleteMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_162"));
        }

        @Test
        @DisplayName("sender deletes → delete-for-everyone tombstone + broadcast; offline peers hidden")
        void senderDeletesForEveryone() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(presenceService.isUserOnline(otherUser)).thenReturn(false); // offline → hide entirely

            service.deleteMessage(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.isDeleted()).isTrue();
            assertThat(msg.getDeletedForUserIds()).contains(otherUser.getId());
            verify(messageRepository).save(msg);
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("group admin deletes another's message → delete-for-everyone")
        void groupAdminDeletesForEveryone() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            Message msg = message(50L, chat, otherUser, MessageType.TEXT); // sent by someone else
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.ADMIN)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(presenceService.isUserOnline(otherUser)).thenReturn(true); // online → keep tombstone

            service.deleteMessage(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.isDeleted()).isTrue();
            assertThat(msg.getDeletedForUserIds()).doesNotContain(otherUser.getId());
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("recipient deletes another's message → delete-for-me only, no broadcast")
        void recipientDeletesForMe() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, otherUser, MessageType.TEXT); // not the caller's message
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.deleteMessage(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.isDeleted()).isFalse();
            assertThat(msg.getDeletedForUserIds()).containsExactly(currentUser.getId());
            verify(messageRepository).save(msg);
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ── revealSelfDestruct ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("revealSelfDestruct")
    class RevealSelfDestruct {

        @Test
        @DisplayName("sender opening their own self-destruct → ForbiddenException TM_165")
        void senderCannotOpen() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(10);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            assertThatThrownBy(() -> service.revealSelfDestruct(chat.getUuid().toString(),
                    msg.getUuid().toString(), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_165"));
        }

        @Test
        @DisplayName("receiver opens an unarmed timed media → arms the timer and saves")
        void receiverArmsTimer() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, otherUser, MessageType.IMAGE); // sent by the OTHER user
            msg.setSelfDestructSeconds(10);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.revealSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.getSelfDestructArmedAt()).isNotNull();
            verify(messageRepository).save(msg);
        }

        @Test
        @DisplayName("already-armed media → no re-arming, no save")
        void alreadyArmedNoOp() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, otherUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(10);
            Instant armed = Instant.now().minusSeconds(2);
            msg.setSelfDestructArmedAt(armed);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.revealSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.getSelfDestructArmedAt()).isEqualTo(armed);
            verify(messageRepository, never()).save(any());
        }
    }

    // ── consumeSelfDestruct ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("consumeSelfDestruct")
    class ConsumeSelfDestruct {

        @Test
        @DisplayName("sender consuming → silent no-op")
        void senderNoOp() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(10);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-self-destruct message → no-op")
        void notSelfDestructNoOp() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, otherUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(null);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("receiver consumes → destroys media, clears content, broadcasts media_expired")
        void receiverConsumesDestroys() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, otherUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(10);
            MessageAttachment att = MessageAttachment.builder()
                    .message(msg).fileUrl("/media/x.jpg").thumbnailUrl("/media/x_thumb.jpg")
                    .fileName("x.jpg").fileSize(10L).build();
            msg.getAttachments().add(att);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.isSelfDestructExpired()).isTrue();
            assertThat(msg.getContent()).isNull();
            assertThat(msg.getAttachments()).isEmpty();
            verify(mediaStorage).delete("/media/x.jpg");
            verify(mediaStorage).delete("/media/x_thumb.jpg");
            verify(messageRepository).save(msg);
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ── reapExpiredSelfDestruct ───────────────────────────────────────────────────

    @Nested
    @DisplayName("reapExpiredSelfDestruct")
    class ReapExpiredSelfDestruct {

        @Test
        @DisplayName("no armed messages → returns 0")
        void nothingToDo() {
            when(messageRepository.findBySelfDestructArmedAtIsNotNullAndSelfDestructExpiredFalse())
                    .thenReturn(List.of());

            int reaped = service.reapExpiredSelfDestruct(Instant.now());

            assertThat(reaped).isZero();
        }

        @Test
        @DisplayName("only past-deadline messages are reaped; still-counting-down survive")
        void reapsOnlyExpired() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Instant now = Instant.now();

            Message expired = message(50L, chat, otherUser, MessageType.IMAGE);
            expired.setSelfDestructSeconds(10);
            expired.setSelfDestructArmedAt(now.minusSeconds(60)); // past deadline

            Message live = message(51L, chat, otherUser, MessageType.IMAGE);
            live.setSelfDestructSeconds(30);
            live.setSelfDestructArmedAt(now.minusSeconds(1)); // still counting down

            when(messageRepository.findBySelfDestructArmedAtIsNotNullAndSelfDestructExpiredFalse())
                    .thenReturn(new ArrayList<>(List.of(expired, live)));

            int reaped = service.reapExpiredSelfDestruct(now);

            assertThat(reaped).isEqualTo(1);
            assertThat(expired.isSelfDestructExpired()).isTrue();
            assertThat(live.isSelfDestructExpired()).isFalse();
        }

        @Test
        @DisplayName("view-once (0s) message uses the grace window before reaping")
        void viewOnceGrace() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Instant now = Instant.now();
            Message viewOnce = message(50L, chat, otherUser, MessageType.IMAGE);
            viewOnce.setSelfDestructSeconds(0); // view-once → 120s grace
            viewOnce.setSelfDestructArmedAt(now.minusSeconds(200)); // beyond the grace
            when(messageRepository.findBySelfDestructArmedAtIsNotNullAndSelfDestructExpiredFalse())
                    .thenReturn(new ArrayList<>(List.of(viewOnce)));

            int reaped = service.reapExpiredSelfDestruct(now);

            assertThat(reaped).isEqualTo(1);
            assertThat(viewOnce.isSelfDestructExpired()).isTrue();
        }
    }

    // ── editMessage ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("editMessage")
    class EditMessage {

        private Chat chat;
        private Message msg;

        @BeforeEach
        void arrange() {
            chat = twoPartyChat(10L, ChatType.PRIVATE);
            msg = message(50L, chat, currentUser, MessageType.TEXT);
            lenient().when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            lenient().when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            lenient().when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
        }

        @Test
        @DisplayName("editing another user's message → ForbiddenException TM_163")
        void notSender() {
            msg.setSender(otherUser);
            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), "new", currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_163"));
        }

        @Test
        @DisplayName("editing a deleted message → BadRequestException TM_164")
        void deletedMessage() {
            msg.setDeleted(true);
            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), "new", currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_164"));
        }

        @Test
        @DisplayName("editing a non-text message → BadRequestException TM_165")
        void nonTextMessage() {
            msg.setMessageType(MessageType.IMAGE);
            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), "new", currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_165"));
        }

        @Test
        @DisplayName("blank content → BadRequestException TM_166")
        void blankContent() {
            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), "   ", currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_166"));
        }

        @Test
        @DisplayName("explicit edit in a clean 1:1 → ContentModerationException")
        void explicitEditBlocked() {
            when(moderationService.moderateText(any()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.SEXUAL, 0.9, List.of()));
            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), "dirty", currentUser))
                    .isInstanceOf(ContentModerationException.class);
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("nominal edit → updates content, marks edited, saves, broadcasts message_edited")
        void nominalEdit() {
            service.editMessage(chat.getUuid().toString(), msg.getUuid().toString(), "updated", currentUser);

            assertThat(msg.getContent()).isEqualTo("updated");
            assertThat(msg.isEdited()).isTrue();
            verify(messageRepository).save(msg);
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ── getAttachment ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAttachment")
    class GetAttachment {

        @Test
        @DisplayName("found → returns the attachment")
        void found() {
            MessageAttachment att = MessageAttachment.builder().fileUrl("/x").fileName("x").fileSize(1L).build();
            when(messageAttachmentRepository.findByUuid(any())).thenReturn(Optional.of(att));

            assertThat(service.getAttachment(UUID.randomUUID().toString())).isSameAs(att);
        }

        @Test
        @DisplayName("missing → NotFoundException TM_169")
        void notFound() {
            when(messageAttachmentRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getAttachment(UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_169"));
        }
    }

    // ── reactToMessage ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reactToMessage")
    class ReactToMessage {

        private ReactToMessageRequest reactReq(String emoji) {
            ReactToMessageRequest r = new ReactToMessageRequest();
            r.setEmoji(emoji);
            return r;
        }

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.reactToMessage(UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), reactReq("👍"), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("not a member → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.reactToMessage(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), reactReq("👍"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("message not found → NotFoundException TM_161")
        void messageNotFound() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.reactToMessage(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), reactReq("👍"), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_161"));
        }

        @Test
        @DisplayName("message from a different chat → ForbiddenException TM_103")
        void wrongChat() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat(11L, ChatType.PRIVATE), currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            assertThatThrownBy(() -> service.reactToMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), reactReq("👍"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("new reaction → persisted and broadcast")
        void newReaction() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(messageReactionRepository.findByMessageAndUserAndEmoji(msg, currentUser, "👍"))
                    .thenReturn(Optional.empty());

            service.reactToMessage(chat.getUuid().toString(), msg.getUuid().toString(), reactReq("👍"), currentUser);

            verify(messageReactionRepository).save(any(MessageReaction.class));
            assertThat(msg.getReactions()).hasSize(1);
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("duplicate reaction → idempotent, no new row, still broadcasts")
        void duplicateReaction() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(messageReactionRepository.findByMessageAndUserAndEmoji(msg, currentUser, "👍"))
                    .thenReturn(Optional.of(MessageReaction.builder().message(msg).user(currentUser).emoji("👍").build()));

            service.reactToMessage(chat.getUuid().toString(), msg.getUuid().toString(), reactReq("👍"), currentUser);

            verify(messageReactionRepository, never()).save(any());
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ── removeReaction ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeReaction")
    class RemoveReaction {

        @Test
        @DisplayName("message from a different chat → ForbiddenException TM_103")
        void wrongChat() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat(11L, ChatType.PRIVATE), currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            assertThatThrownBy(() -> service.removeReaction(chat.getUuid().toString(),
                    msg.getUuid().toString(), "👍", currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("existing reaction → deleted and broadcast")
        void removesExisting() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            MessageReaction reaction = MessageReaction.builder().message(msg).user(currentUser).emoji("👍").build();
            msg.getReactions().add(reaction);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(messageReactionRepository.findByMessageAndUserAndEmoji(msg, currentUser, "👍"))
                    .thenReturn(Optional.of(reaction));

            service.removeReaction(chat.getUuid().toString(), msg.getUuid().toString(), "👍", currentUser);

            verify(messageReactionRepository).delete(reaction);
            assertThat(msg.getReactions()).isEmpty();
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("no matching reaction → nothing deleted, still broadcasts")
        void nothingToRemove() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(messageReactionRepository.findByMessageAndUserAndEmoji(msg, currentUser, "👍"))
                    .thenReturn(Optional.empty());

            service.removeReaction(chat.getUuid().toString(), msg.getUuid().toString(), "👍", currentUser);

            verify(messageReactionRepository, never()).delete(any());
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("chat not found → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.removeReaction(UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), "👍", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("not a member → ForbiddenException TM_141")
        void notMember() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.removeReaction(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), "👍", currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("message not found → NotFoundException TM_161")
        void messageNotFound() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.removeReaction(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), "👍", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_161"));
        }
    }

    // ── releaseHeldMessages ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("releaseHeldMessages")
    class ReleaseHeldMessages {

        @Test
        @DisplayName("nothing held → no-op (no save, no broadcast)")
        void nothingHeld() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(messageRepository.findHeldForConsent(chat)).thenReturn(List.of());

            service.releaseHeldMessages(chat);

            verify(messageRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("held messages → flipped RELEASED, outbox row + broadcast per message")
        void releasesHeld() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message held = message(50L, chat, currentUser, MessageType.TEXT);
            held.setModerationStatus(ModerationStatus.BLOCKED_PENDING_CONSENT);
            when(messageRepository.findHeldForConsent(chat)).thenReturn(new ArrayList<>(List.of(held)));

            service.releaseHeldMessages(chat);

            assertThat(held.getModerationStatus()).isEqualTo(ModerationStatus.RELEASED);
            verify(messageRepository).save(held);
            verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
            verify(eventPublisher, times(1)).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("release recipient filter skips left members and null users")
        void releaseSkipsLeftAndNullMembers() {
            Chat chat = chat(10L, ChatType.GROUP);
            chat.getMembers().add(member(chat, currentUser, MemberRole.MEMBER)); // sender
            ChatMember leftMember = member(chat, otherUser, MemberRole.MEMBER);
            leftMember.setLeftAt(Instant.now());                                 // former member (leftAt != null)
            chat.getMembers().add(leftMember);
            ChatMember nullUserMember = ChatMember.builder().chat(chat).user(null).build();
            nullUserMember.setRole(MemberRole.MEMBER);
            chat.getMembers().add(nullUserMember);                               // null user
            Message held = message(50L, chat, currentUser, MessageType.TEXT);
            held.setModerationStatus(ModerationStatus.BLOCKED_PENDING_CONSENT);
            when(messageRepository.findHeldForConsent(chat)).thenReturn(new ArrayList<>(List.of(held)));

            service.releaseHeldMessages(chat);

            assertThat(held.getModerationStatus()).isEqualTo(ModerationStatus.RELEASED);
            verify(eventPublisher, times(1)).publishEvent(any(Object.class));
        }
    }

    // ── Branch-coverage backfill: negative / edge paths ───────────────────────────

    @Nested
    @DisplayName("sendMessage — edge branches")
    class SendMessageEdges {

        private Chat privateChat() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            return chat;
        }

        private Chat groupChat(ChatMember me) {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            return chat;
        }

        @Test
        @DisplayName("member row is soft-deleted → filtered out → ForbiddenException TM_141")
        void deletedMemberFilteredOut() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            me.setDeleted(true); // filter(m -> !m.isDeleted()) drops it
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("slow mode active but caller is an ADMIN → bypasses slow mode")
        void slowModeAdminBypass() {
            ChatMember admin = member(twoPartyChat(10L, ChatType.GROUP), currentUser, MemberRole.ADMIN);
            Chat chat = groupChat(admin);
            chat.getSettings().setSlowModeSeconds(30);

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            // Admin never consults the last-message window, and the send proceeds.
            verify(messageRepository, never()).findFirstByChatAndSenderOrderByIdDesc(any(), any());
            verify(messageRepository).save(any(Message.class));
        }

        @Test
        @DisplayName("slow mode with no previous message → send proceeds")
        void slowModeNoPreviousMessage() {
            ChatMember me = member(twoPartyChat(10L, ChatType.GROUP), currentUser, MemberRole.MEMBER);
            Chat chat = groupChat(me);
            chat.getSettings().setSlowModeSeconds(30);
            when(messageRepository.findFirstByChatAndSenderOrderByIdDesc(chat, currentUser))
                    .thenReturn(Optional.empty());

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            verify(messageRepository).save(any(Message.class));
        }

        @Test
        @DisplayName("slow mode but last message is outside the window → send proceeds")
        void slowModeOutsideWindow() {
            ChatMember me = member(twoPartyChat(10L, ChatType.GROUP), currentUser, MemberRole.MEMBER);
            Chat chat = groupChat(me);
            chat.getSettings().setSlowModeSeconds(30);
            Message last = message(99L, chat, currentUser, MessageType.TEXT);
            last.setCreatedAt(Instant.now().minusSeconds(120)); // well outside the 30s window
            when(messageRepository.findFirstByChatAndSenderOrderByIdDesc(chat, currentUser))
                    .thenReturn(Optional.of(last));

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            verify(messageRepository).save(any(Message.class));
        }

        @Test
        @DisplayName("multi-party chat with null settings → slow mode treated as 0")
        void nullSettingsMeansNoSlowMode() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.setSettings(null); // getSettings() != null ? ... : 0 → 0
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            verify(messageRepository).save(any(Message.class));
        }

        @Test
        @DisplayName("clientId present but no prior message → proceeds and stores the clientId")
        void clientIdPresentNoExisting() {
            ChatMember me = member(twoPartyChat(10L, ChatType.GROUP), currentUser, MemberRole.MEMBER);
            Chat chat = groupChat(me);
            when(messageRepository.findFirstByChatAndSenderAndClientId(chat, currentUser, "cid-9"))
                    .thenReturn(Optional.empty());
            SendMessageRequest req = textRequest("hi");
            req.setClientId("cid-9");

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getClientId()).isEqualTo("cid-9");
        }

        @Test
        @DisplayName("invalid messageType string → falls back to TEXT")
        void invalidMessageTypeFallsBackToText() {
            Chat chat = privateChat();
            SendMessageRequest req = new SendMessageRequest();
            req.setContent("hi");
            req.setMessageType("NOT_A_TYPE");

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getMessageType()).isEqualTo(MessageType.TEXT);
        }

        @Test
        @DisplayName("null messageType → defaults to TEXT (valueOf never attempted)")
        void nullMessageTypeDefaultsToText() {
            Chat chat = privateChat();
            SendMessageRequest req = new SendMessageRequest();
            req.setContent("hi");
            req.setMessageType(null);

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getMessageType()).isEqualTo(MessageType.TEXT);
        }

        @Test
        @DisplayName("parentMessageId set → resolves and attaches the parent")
        void withParentMessage() {
            Chat chat = privateChat();
            Message parent = message(77L, chat, otherUser, MessageType.TEXT);
            SendMessageRequest req = textRequest("reply");
            req.setParentMessageId(parent.getUuid().toString());
            when(messageRepository.findByUuid(UUID.fromString(req.getParentMessageId())))
                    .thenReturn(Optional.of(parent));

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getParentMessage()).isSameAs(parent);
        }

        @Test
        @DisplayName("STRANGER chat → skips the friends-only messaging-privacy check")
        void strangerSkipsFriendsCheck() {
            Chat chat = twoPartyChat(10L, ChatType.STRANGER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            verify(userSettingRepository, never()).findByUser(any());
            verify(messageRepository).save(any(Message.class));
        }

        @Test
        @DisplayName("FRIENDS_ONLY and the only friend row is soft-deleted → treated as non-friend TM_143")
        void friendRowDeletedTreatedAsNonFriend() {
            Chat chat = privateChat();
            UserSetting setting = UserSetting.builder().user(otherUser).build();
            setting.setMessagingPrivacy(MessagingPrivacy.FRIENDS_ONLY);
            when(userSettingRepository.findByUser(otherUser)).thenReturn(Optional.of(setting));
            Friend f = Friend.builder().user(currentUser).friend(otherUser).build();
            f.setDeleted(true); // map(f -> !f.isDeleted()) → false → not a friend
            when(friendRepository.findByUserAndFriend(currentUser, otherUser)).thenReturn(Optional.of(f));

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_143"));
        }

        @Test
        @DisplayName("media message → NSFW moderator flags the local copy → hard-blocked in a clean group")
        void mediaModerationExplicitHardBlockedInGroup() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.setAllowExplicitContent(false);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            MediaStorage.LocalFile local = mock(MediaStorage.LocalFile.class);
            when(local.path()).thenReturn(Path.of("/tmp/x.jpg"));
            when(mediaStorage.localCopy(any())).thenReturn(Optional.of(local));
            when(moderationService.moderateMedia(any(), eq(MessageType.IMAGE)))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.NSFW_IMAGE, 0.95, List.of()));
            SendMessageRequest req = new SendMessageRequest();
            req.setMessageType("IMAGE");
            req.setFileUrl("/media/x.jpg");

            assertThatThrownBy(() -> service.sendMessage(chat.getUuid().toString(), req, currentUser))
                    .isInstanceOf(ContentModerationException.class);
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("@mentions in a group → valid uuids resolved; null/blank/malformed skipped")
        void mentionsResolvedAndSkipped() {
            ChatMember me = member(twoPartyChat(10L, ChatType.GROUP), currentUser, MemberRole.MEMBER);
            Chat chat = groupChat(me);
            when(userRepository.findByUuid(otherUser.getUuid())).thenReturn(Optional.of(otherUser));
            SendMessageRequest req = textRequest("hey @bob");
            req.setMentionedUserIds(Arrays.asList(
                    otherUser.getUuid().toString(), // valid → resolved
                    null,                            // null → skipped
                    "   ",                           // blank → skipped
                    "not-a-uuid"));                  // malformed → IllegalArgumentException swallowed

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getMentionedUserIds()).containsExactly(otherUser.getId());
        }

        @Test
        @DisplayName("@mentions all unresolvable → mentionedUserIds stays empty (never set)")
        void mentionsAllUnresolvableNotSet() {
            ChatMember me = member(twoPartyChat(10L, ChatType.GROUP), currentUser, MemberRole.MEMBER);
            Chat chat = groupChat(me);
            SendMessageRequest req = textRequest("hey");
            req.setMentionedUserIds(Arrays.asList(null, "   ", "bad-uuid"));

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().getMentionedUserIds()).isEmpty();
        }

        @Test
        @DisplayName("media send → attachment defaults filename to 'file' and size to 0; allowDownload honored")
        void mediaSendAttachmentDefaults() {
            Chat chat = privateChat();
            when(mediaStorage.localCopy(any())).thenReturn(Optional.empty());
            SendMessageRequest req = new SendMessageRequest();
            req.setMessageType("IMAGE");
            req.setFileUrl("/media/x.jpg");
            req.setFileName(null);   // → default "file"
            req.setFileSize(null);   // → default 0L
            req.setAllowDownload(true);

            service.sendMessage(chat.getUuid().toString(), req, currentUser);

            ArgumentCaptor<MessageAttachment> att = ArgumentCaptor.forClass(MessageAttachment.class);
            verify(messageAttachmentRepository).save(att.capture());
            assertThat(att.getValue().getFileName()).isEqualTo("file");
            assertThat(att.getValue().getFileSize()).isZero();
            ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(saved.capture());
            assertThat(saved.getValue().isAllowDownload()).isTrue();
        }

        @Test
        @DisplayName("recipient fan-out skips former (leftAt) and null-user members")
        void fanOutSkipsLeftAndNullRecipients() {
            Chat chat = chat(10L, ChatType.GROUP);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            chat.getMembers().add(me);
            ChatMember leftMember = member(chat, otherUser, MemberRole.MEMBER);
            leftMember.setLeftAt(Instant.now());
            chat.getMembers().add(leftMember);
            ChatMember nullUserMember = ChatMember.builder().chat(chat).user(null).build();
            nullUserMember.setRole(MemberRole.MEMBER);
            chat.getMembers().add(nullUserMember);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);

            service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            verify(messageRepository).save(any(Message.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }
    }

    @Nested
    @DisplayName("sendEphemeralRoomMessage — edge branches")
    class SendEphemeralRoomEdges {

        @Test
        @DisplayName("ephemeral room media → attachment built transiently, broadcast, nothing persisted")
        void ephemeralMediaBroadcast() {
            Chat chat = twoPartyChat(10L, ChatType.ROOM);
            chat.setRoomMode(RoomMode.LISTENING);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            when(mediaStorage.localCopy(any())).thenReturn(Optional.empty());
            SendMessageRequest req = new SendMessageRequest();
            req.setMessageType("IMAGE");
            req.setFileUrl("/media/x.jpg");
            req.setFileName(null); // default "file"
            req.setFileSize(null); // default 0L

            MessageResponse res = service.sendMessage(chat.getUuid().toString(), req, currentUser);

            assertThat(res).isNotNull();
            verify(messageRepository, never()).save(any());
            verify(messageAttachmentRepository, never()).save(any());
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), anyString(), any());
        }

        @Test
        @DisplayName("ephemeral room but recipient blocked sender → no live broadcast")
        void ephemeralBlockedNoBroadcast() {
            // STRANGER goes through 1:1 block logic yet can carry an ephemeral room mode.
            Chat chat = twoPartyChat(10L, ChatType.STRANGER);
            chat.setRoomMode(RoomMode.SLEEP_COMPANION);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(blockUserRepository.existsByUserAndBlocked(currentUser, otherUser)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(otherUser, currentUser)).thenReturn(true); // isBlocked

            MessageResponse res = service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            assertThat(res).isNotNull();
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("ephemeral live broadcast throws → swallowed (best-effort)")
        void ephemeralBroadcastFailureSwallowed() {
            Chat chat = twoPartyChat(10L, ChatType.ROOM);
            chat.setRoomMode(RoomMode.SLEEP_COMPANION);
            ChatMember me = member(chat, currentUser, MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser)).thenReturn(Optional.of(me));
            when(groupAuthzService.canSend(chat, me)).thenReturn(true);
            Mockito.doThrow(new RuntimeException("ws down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            MessageResponse res = service.sendMessage(chat.getUuid().toString(), textRequest("hi"), currentUser);

            assertThat(res).isNotNull(); // exception is caught, sender still gets a response
        }
    }

    @Nested
    @DisplayName("sendSystemMessage — edge branches")
    class SendSystemMessageEdges {

        @Test
        @DisplayName("recipient filter skips left members and null users")
        void systemSkipsLeftAndNullMembers() {
            Chat chat = chat(10L, ChatType.GROUP);
            chat.getMembers().add(member(chat, currentUser, MemberRole.MEMBER));
            ChatMember leftMember = member(chat, otherUser, MemberRole.MEMBER);
            leftMember.setLeftAt(Instant.now());
            chat.getMembers().add(leftMember);
            ChatMember nullUserMember = ChatMember.builder().chat(chat).user(null).build();
            nullUserMember.setRole(MemberRole.MEMBER);
            chat.getMembers().add(nullUserMember);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));

            service.sendSystemMessage(chat.getUuid().toString(), currentUser, "{\"e\":1}", currentUser);

            verify(messageRepository).save(any(Message.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }
    }

    @Nested
    @DisplayName("setMessagePinned — edge branches")
    class SetMessagePinnedEdges {

        @Test
        @DisplayName("group membership re-check fails after load → ForbiddenException TM_141")
        void groupMemberVanished() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            chat.getSettings().setWhoCanPin(MemberRole.MEMBER);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            // loadChatMessage sees the member; the pin re-lookup finds nothing.
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)), Optional.empty());
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            assertThatThrownBy(() -> service.setMessagePinned(chat.getUuid().toString(),
                    msg.getUuid().toString(), true, currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
            verify(messageRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getMessages — limit boundaries and starred flags")
    class GetMessagesEdges {

        private void arrange(Chat chat) {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
        }

        @Test
        @DisplayName("limit <= 0 → defaults to 30 (fetches 31 to detect more)")
        void limitZeroDefaults() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            arrange(chat);
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(messageRepository.findMessagesBeforeCursor(any(), anyLong(), any(), any(), any(), pageable.capture()))
                    .thenReturn(new ArrayList<>());

            service.getMessages(chat.getUuid().toString(), null, 0, currentUser);

            assertThat(pageable.getValue().getPageSize()).isEqualTo(31); // safeLimit 30 + 1
        }

        @Test
        @DisplayName("limit above 100 → capped at 100 (fetches 101)")
        void limitCappedAt100() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            arrange(chat);
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(messageRepository.findMessagesBeforeCursor(any(), anyLong(), any(), any(), any(), pageable.capture()))
                    .thenReturn(new ArrayList<>());

            service.getMessages(chat.getUuid().toString(), null, 500, currentUser);

            assertThat(pageable.getValue().getPageSize()).isEqualTo(101); // safeLimit 100 + 1
        }

        @Test
        @DisplayName("starred rows → only matching responses flagged starred")
        void starredFlagsApplied() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            arrange(chat);
            Message starredMsg = message(100L, chat, currentUser, MessageType.TEXT);
            Message plainMsg = message(99L, chat, otherUser, MessageType.TEXT);
            when(messageRepository.findMessagesBeforeCursor(any(), anyLong(), any(), any(), any(), any()))
                    .thenReturn(new ArrayList<>(List.of(starredMsg, plainMsg)));
            when(messageStarRepository.findStarredMessageIds(currentUser.getId(), List.of(100L, 99L)))
                    .thenReturn(List.of(100L));

            MessagePageResponse res = service.getMessages(chat.getUuid().toString(), null, 30, currentUser);

            assertThat(res.getItems()).hasSize(2);
            assertThat(res.getItems().get(0).isStarred()).isTrue();  // id 100 starred
            assertThat(res.getItems().get(1).isStarred()).isFalse(); // id 99 not starred
        }
    }

    @Nested
    @DisplayName("ghost-aware status resolution — edge branches")
    class GhostStatusEdges {

        private void arrange(Chat chat, Message m) {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            Page<Message> page = new PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
            when(messageRepository.searchMessagesInChat(any(), eq("q"), anyLong(), any(), any())).thenReturn(page);
        }

        private MessageReadReceipt receipt(Message m, User u, String status) {
            return MessageReadReceipt.builder().message(m).user(u).status(status).build();
        }

        @Test
        @DisplayName("non-ghost recipient DELIVERED (sender + ghost receipts ignored) → DELIVERED")
        void nonGhostDeliveredStatus() {
            User ghost = user(3L, "ghosty", "Ghosty");
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message m = message(50L, chat, currentUser, MessageType.TEXT);
            m.getReadReceipts().add(receipt(m, currentUser, "READ"));   // sender → skipped
            m.getReadReceipts().add(receipt(m, otherUser, "DELIVERED")); // non-ghost → delivered
            m.getReadReceipts().add(receipt(m, ghost, "READ"));          // ghost → skipped
            arrange(chat, m);
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of(ghost.getId()));

            Page<MessageResponse> res = service.searchMessages(chat.getUuid().toString(),
                    "q", PageRequest.of(0, 20), currentUser);

            assertThat(res.getContent().get(0).getStatus()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("non-ghost recipient READ → READ (short-circuits DELIVERED tracking)")
        void nonGhostReadStatus() {
            User ghost = user(3L, "ghosty", "Ghosty");
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message m = message(50L, chat, currentUser, MessageType.TEXT);
            m.getReadReceipts().add(receipt(m, otherUser, "READ"));  // non-ghost READ
            m.getReadReceipts().add(receipt(m, ghost, "DELIVERED")); // ghost → skipped
            arrange(chat, m);
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of(ghost.getId()));

            Page<MessageResponse> res = service.searchMessages(chat.getUuid().toString(),
                    "q", PageRequest.of(0, 20), currentUser);

            assertThat(res.getContent().get(0).getStatus()).isEqualTo("READ");
        }

        @Test
        @DisplayName("null receipts list, null-user and viewer receipts are all skipped in ghost scan")
        void nullAndSelfReceiptsSkipped() {
            // No ghosts present → getGhostUserIds empty → resolveStatus is never invoked, so the
            // scan itself is what exercises the null-list / null-user / viewer skip branches.
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message m = message(50L, chat, currentUser, MessageType.TEXT);
            m.getReadReceipts().add(receipt(m, null, "READ"));        // null user → skipped (597)
            m.getReadReceipts().add(receipt(m, currentUser, "READ")); // viewer → skipped (597)
            m.getReadReceipts().add(receipt(m, otherUser, "READ"));   // real other → users non-empty
            Message noReceipts = message(49L, chat, currentUser, MessageType.TEXT);
            noReceipts.setReadReceipts(null);                         // null list → skipped (594)
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            Page<Message> page = new PageImpl<>(List.of(m, noReceipts), PageRequest.of(0, 20), 2);
            when(messageRepository.searchMessagesInChat(any(), eq("q"), anyLong(), any(), any())).thenReturn(page);
            when(presenceService.getGhostUserIds(any())).thenReturn(Collections.emptySet());

            Page<MessageResponse> res = service.searchMessages(chat.getUuid().toString(),
                    "q", PageRequest.of(0, 20), currentUser);

            // ghostIds empty → status left untouched by ghost logic (mapper leaves it null).
            assertThat(res.getContent()).hasSize(2);
            assertThat(res.getContent().get(0).getStatus()).isNull();
        }
    }

    @Nested
    @DisplayName("deleteMessage — edge branches")
    class DeleteMessageEdges {

        @Test
        @DisplayName("group non-admin deletes another's message → delete-for-me only, no broadcast")
        void groupNonAdminDeletesForMe() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            Message msg = message(50L, chat, otherUser, MessageType.TEXT); // not the caller's
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER))); // non-admin
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            service.deleteMessage(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.isDeleted()).isFalse();
            assertThat(msg.getDeletedForUserIds()).containsExactly(currentUser.getId());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("sender delete-for-everyone skips null-user members in the offline loop")
        void nullUserMemberSkippedInOfflineLoop() {
            Chat chat = twoPartyChat(10L, ChatType.GROUP);
            ChatMember nullUserMember = ChatMember.builder().chat(chat).user(null).build();
            nullUserMember.setRole(MemberRole.MEMBER);
            chat.getMembers().add(nullUserMember); // u == null → continue
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(presenceService.isUserOnline(otherUser)).thenReturn(false); // offline → hidden

            service.deleteMessage(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.isDeleted()).isTrue();
            assertThat(msg.getDeletedForUserIds()).contains(otherUser.getId());
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }
    }

    @Nested
    @DisplayName("revealSelfDestruct / consumeSelfDestruct — edge branches")
    class SelfDestructStateEdges {

        private void arrange(Chat chat, Message msg) {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
        }

        @Test
        @DisplayName("reveal on a non-self-destruct message → no arming, no save")
        void revealNonSelfDestructNoArm() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, otherUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(null); // not a self-destruct message
            arrange(chat, msg);

            MessageResponse res = service.revealSelfDestruct(chat.getUuid().toString(),
                    msg.getUuid().toString(), currentUser);

            assertThat(res).isNotNull();
            assertThat(msg.getSelfDestructArmedAt()).isNull();
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("consume on an already-expired message → no-op")
        void consumeAlreadyExpiredNoOp() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, otherUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(10);
            msg.setSelfDestructExpired(true); // already gone
            arrange(chat, msg);

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            verify(messageRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    @Nested
    @DisplayName("expireSelfDestruct media cleanup — edge branches")
    class ExpireSelfDestructMediaEdges {

        private void arrangeReceiverConsume(Chat chat, Message msg) {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
        }

        private Message armedMediaMessage(Chat chat, MessageAttachment att) {
            Message msg = message(50L, chat, otherUser, MessageType.IMAGE);
            msg.setSelfDestructSeconds(10);
            msg.getAttachments().add(att);
            return msg;
        }

        @Test
        @DisplayName("attachment with fileUrl but no thumbnail → only the file is deleted")
        void onlyFileUrlNoThumbnail() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            MessageAttachment att = MessageAttachment.builder()
                    .fileUrl("/media/f.jpg").thumbnailUrl(null).fileName("f").fileSize(1L).build();
            Message msg = armedMediaMessage(chat, att);
            arrangeReceiverConsume(chat, msg);

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            verify(mediaStorage).delete("/media/f.jpg");
            verify(mediaStorage, times(1)).delete(anyString()); // thumbnail (null) not deleted
            assertThat(msg.isSelfDestructExpired()).isTrue();
        }

        @Test
        @DisplayName("attachment with only a thumbnail → file delete skipped, thumbnail deleted")
        void nullFileUrlOnlyThumbnail() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            MessageAttachment att = MessageAttachment.builder()
                    .fileUrl(null).thumbnailUrl("/media/t.jpg").fileName("t").fileSize(1L).build();
            Message msg = armedMediaMessage(chat, att);
            arrangeReceiverConsume(chat, msg);

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            verify(mediaStorage).delete("/media/t.jpg");
            assertThat(msg.isSelfDestructExpired()).isTrue();
        }

        @Test
        @DisplayName("blank fileUrl → deleteMediaFileQuietly is a no-op")
        void blankFileUrlSkipsDelete() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            MessageAttachment att = MessageAttachment.builder()
                    .fileUrl("").thumbnailUrl(null).fileName("f").fileSize(1L).build();
            Message msg = armedMediaMessage(chat, att);
            arrangeReceiverConsume(chat, msg);

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            verify(mediaStorage, never()).delete(anyString());
            assertThat(msg.isSelfDestructExpired()).isTrue();
        }

        @Test
        @DisplayName("mediaStorage.delete throws → swallowed, message still expires")
        void deleteFailureSwallowed() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            MessageAttachment att = MessageAttachment.builder()
                    .fileUrl("/media/f.jpg").thumbnailUrl(null).fileName("f").fileSize(1L).build();
            Message msg = armedMediaMessage(chat, att);
            arrangeReceiverConsume(chat, msg);
            Mockito.doThrow(new RuntimeException("storage down"))
                    .when(mediaStorage).delete("/media/f.jpg");

            service.consumeSelfDestruct(chat.getUuid().toString(), msg.getUuid().toString(), currentUser);

            assertThat(msg.isSelfDestructExpired()).isTrue();
            verify(messageRepository).save(msg);
        }
    }

    @Nested
    @DisplayName("reapExpiredSelfDestruct — edge branches")
    class ReapEdges {

        @Test
        @DisplayName("armed message with null selfDestructSeconds → uses the view-once grace window")
        void nullSecondsUsesGrace() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Instant now = Instant.now();
            Message m = message(50L, chat, otherUser, MessageType.IMAGE);
            m.setSelfDestructSeconds(null); // secs → 0 → grace (120s)
            m.setSelfDestructArmedAt(now.minusSeconds(200)); // beyond grace
            when(messageRepository.findBySelfDestructArmedAtIsNotNullAndSelfDestructExpiredFalse())
                    .thenReturn(new ArrayList<>(List.of(m)));

            int reaped = service.reapExpiredSelfDestruct(now);

            assertThat(reaped).isEqualTo(1);
            assertThat(m.isSelfDestructExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("editMessage — edge branches")
    class EditMessageEdges {

        @Test
        @DisplayName("null sender on the stored message → ForbiddenException TM_163")
        void nullSender() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            msg.setSender(null); // getSender() == null → first operand true
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), "new", currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_163"));
        }

        @Test
        @DisplayName("null content → BadRequestException TM_166")
        void nullContent() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            Message msg = message(50L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));

            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    msg.getUuid().toString(), null, currentUser))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_166"));
        }

        @Test
        @DisplayName("explicit edit in an 18+ group (allowExplicit) → allowed and saved")
        void explicitEditAllowedInGroup() {
            Chat chat = twoPartyChat(20L, ChatType.GROUP);
            chat.setAllowExplicitContent(true);
            Message msg = message(60L, chat, currentUser, MessageType.TEXT);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.of(msg));
            when(moderationService.moderateText(any()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.ABUSE, 0.9, List.of()));

            service.editMessage(chat.getUuid().toString(), msg.getUuid().toString(), "dirty", currentUser);

            assertThat(msg.getContent()).isEqualTo("dirty");
            assertThat(msg.isEdited()).isTrue();
            verify(messageRepository).save(msg);
        }

        @Test
        @DisplayName("chat not found (via loadChatMessage) → NotFoundException TM_121")
        void chatNotFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.editMessage(UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), "new", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("message not found (via loadChatMessage) → NotFoundException TM_161")
        void messageNotFound() {
            Chat chat = twoPartyChat(10L, ChatType.PRIVATE);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, currentUser))
                    .thenReturn(Optional.of(member(chat, currentUser, MemberRole.MEMBER)));
            when(messageRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.editMessage(chat.getUuid().toString(),
                    UUID.randomUUID().toString(), "new", currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_161"));
        }
    }
}
