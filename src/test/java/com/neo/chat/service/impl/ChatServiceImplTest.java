package com.neo.chat.service.impl;

import com.neo.chat.cache.BlockCache;
import com.neo.chat.cache.MemberCountCache;
import com.neo.chat.cache.UserSettingsCache;
import com.neo.chat.crypto.ChatKeyService;
import com.neo.chat.crypto.MessageCryptoService;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.Friend;
import com.neo.chat.domain.Message;
import com.neo.chat.domain.MessageReadReceipt;
import com.neo.chat.domain.OutboxEvent;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.CreateChatRequest;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.ChatKeyResponse;
import com.neo.chat.dto.response.ChatResponse;
import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.enums.ChatType;
import com.neo.chat.enums.Interest;
import com.neo.chat.enums.MemberRole;
import com.neo.chat.enums.PresenceStatus;
import com.neo.chat.event.StatusUpdateEvent;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.mapper.ChatMapper;
import com.neo.chat.mapper.MessageMapper;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.MessageReadReceiptRepository;
import com.neo.chat.repository.MessageRepository;
import com.neo.chat.repository.OutboxEventRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.PresenceService;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ChatServiceImpl} — the per-user chat lifecycle
 * (create / read / archive / mute / pin / clear / delete) and the read/delivered
 * receipt engine (mark-read / mark-delivered / mark-all-delivered) with its
 * transactional-outbox side effects.
 *
 * <p>Invariants under test: (1) every mutating op guards membership → {@code TM_141};
 * (2) an unknown chat → {@code TM_121}; (3) an unknown recipient on create → {@code TM_064};
 * (4) only the OWNER may delete a multi-party chat → {@code TM_291}; (5) a status change
 * with real updates persists an outbox row AND publishes an event, with none when there is
 * nothing to update; (6) an outbox serialize failure surfaces as {@link IllegalStateException}
 * with no event published; (7) Ghost recipients still record delivery but never broadcast it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatServiceImpl (unit)")
class ChatServiceImplTest {

    private static final String CHAT_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String CURRENT_UUID = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_UUID = "33333333-3333-3333-3333-333333333333";

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;
    @Mock
    private MemberCountCache memberCountCache;
    @Mock
    private UserSettingsCache userSettingsCache;
    @Mock
    private BlockCache blockCache;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageReadReceiptRepository readReceiptRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ChatMapper chatMapper;
    @Mock
    private PresenceService presenceService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ChatKeyService chatKeyService;
    @Mock
    private MessageCryptoService messageCryptoService;

    private ChatServiceImpl service;

    private User currentUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        service = new ChatServiceImpl(
                chatRepository, chatMemberRepository, memberCountCache, userSettingsCache,
                blockCache, userRepository, messageRepository, readReceiptRepository,
                userMapper, messageMapper, chatMapper, presenceService, messagingTemplate,
                friendRepository, applicationEventPublisher, objectMapper, outboxEventRepository,
                chatKeyService, messageCryptoService);

        currentUser = user(1L, CURRENT_UUID, "alice", "Alice");
        otherUser = user(2L, OTHER_UUID, "bob", "Bob");

        // ensureManagedUser: every public method reloads the current user by id.
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));

        // mapToChatResponse collaborators — object returns that would otherwise NPE.
        lenient().when(chatMapper.toChatResponse(any())).thenAnswer(inv -> new ChatResponse());
        lenient().when(userMapper.toAuthUserResponse(any()))
                .thenAnswer(inv -> AuthUserResponse.builder().build());
        lenient().when(presenceService.getStatus(any())).thenReturn(PresenceStatus.ONLINE);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private static User user(long id, String uuid, String username, String name) {
        User u = User.builder().username(username).name(name).build();
        u.setId(id);
        u.setUuid(UUID.fromString(uuid));
        return u;
    }

    private ChatMember member(Chat chat, User user, MemberRole role) {
        ChatMember m = ChatMember.builder().chat(chat).user(user).build();
        m.setRole(role);
        m.setId(user.getId() * 100);
        return m;
    }

    /**
     * A 1:1 chat with the current user + the other user as its two members.
     */
    private Chat privateChat() {
        Chat chat = Chat.builder().chatType(ChatType.PRIVATE).build();
        chat.setId(10L);
        chat.setUuid(UUID.fromString(CHAT_UUID));
        chat.getMembers().add(member(chat, currentUser, MemberRole.MEMBER));
        chat.getMembers().add(member(chat, otherUser, MemberRole.MEMBER));
        return chat;
    }

    /**
     * A multi-party GROUP chat with the current user's role as given.
     */
    private Chat groupChat(MemberRole selfRole) {
        Chat chat = Chat.builder().chatType(ChatType.GROUP).name("Team").build();
        chat.setId(20L);
        chat.setUuid(UUID.fromString(CHAT_UUID));
        chat.getMembers().add(member(chat, currentUser, selfRole));
        chat.getMembers().add(member(chat, otherUser, MemberRole.MEMBER));
        return chat;
    }

    private ChatMember selfMemberOf(Chat chat) {
        return chat.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(currentUser.getId()))
                .findFirst().orElseThrow();
    }

    private Message messageFrom(User sender, String createdAtIso) {
        Message m = new Message();
        m.setId(500L);
        m.setSender(sender);
        m.setCreatedAt(Instant.parse(createdAtIso));
        return m;
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("createChat")
    class CreateChat {

        private CreateChatRequest privateRequest() {
            CreateChatRequest r = new CreateChatRequest();
            r.setRecipientId(OTHER_UUID);
            return r;
        }

        @Test
        @DisplayName("new 1:1 → mints chat + two members and notifies the recipient")
        void createsNewPrivateChat() {
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(otherUser));
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());
            when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> {
                Chat c = inv.getArgument(0);
                c.setId(10L);
                c.setUuid(UUID.fromString(CHAT_UUID));
                return c;
            });
            when(chatMemberRepository.save(any(ChatMember.class))).thenAnswer(inv -> inv.getArgument(0));

            ChatResponse resp = service.createChat(privateRequest(), currentUser);

            assertThat(resp).isNotNull();
            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().getChatType()).isEqualTo(ChatType.PRIVATE);
            verify(chatMemberRepository, times(2)).save(any(ChatMember.class));
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/chats"), any());
        }

        @Test
        @DisplayName("existing ACTIVE 1:1 is reused untouched — no new chat/member saved")
        void reusesActivePrivateChat() {
            Chat existing = privateChat();
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(otherUser));
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(existing));

            ChatResponse resp = service.createChat(privateRequest(), currentUser);

            assertThat(resp).isNotNull();
            verify(chatRepository, never()).save(any());
            verify(chatMemberRepository, never()).save(any());
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/chats"), any());
        }

        @Test
        @DisplayName("previously-deleted 1:1 is reopened — chat undeleted, members reset + cleared")
        void reopensDeletedPrivateChat() {
            Chat deleted = privateChat();
            deleted.setDeleted(true);
            deleted.getMembers().forEach(m -> {
                m.setDeleted(true);
                m.setPinned(true);
                m.setArchived(true);
            });
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(otherUser));
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(deleted));
            when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> inv.getArgument(0));
            when(chatMemberRepository.save(any(ChatMember.class))).thenAnswer(inv -> inv.getArgument(0));

            service.createChat(privateRequest(), currentUser);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().isDeleted()).isFalse();

            ArgumentCaptor<ChatMember> mCap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository, times(2)).save(mCap.capture());
            assertThat(mCap.getAllValues()).allSatisfy(m -> {
                assertThat(m.isDeleted()).isFalse();
                assertThat(m.isPinned()).isFalse();
                assertThat(m.isArchived()).isFalse();
                assertThat(m.getClearedAt()).isNotNull();
            });
        }

        @Test
        @DisplayName("recipient UUID not found → NotFoundException TM_064, nothing created")
        void recipientNotFound() {
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createChat(privateRequest(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
            verify(chatRepository, never()).save(any());
        }

        @Test
        @DisplayName("group create (no recipient) → GROUP chat, OWNER admin member + listed members")
        void createsGroupChatWithMembers() {
            CreateChatRequest r = new CreateChatRequest();
            r.setName("Team");
            r.setMemberIds(List.of(OTHER_UUID));
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(otherUser));
            when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> {
                Chat c = inv.getArgument(0);
                c.setId(20L);
                c.setUuid(UUID.fromString(CHAT_UUID));
                return c;
            });
            when(chatMemberRepository.save(any(ChatMember.class))).thenAnswer(inv -> inv.getArgument(0));

            ChatResponse resp = service.createChat(r, currentUser);

            assertThat(resp).isNotNull();
            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().getChatType()).isEqualTo(ChatType.GROUP);
            assertThat(chatCap.getValue().getOwnerId()).isEqualTo(1L);
            // admin (owner) member + one listed member.
            verify(chatMemberRepository, times(2)).save(any(ChatMember.class));
        }

        @Test
        @DisplayName("group create with null memberIds → only the owner member is added")
        void createsGroupChatNoMembers() {
            CreateChatRequest r = new CreateChatRequest();
            r.setName("Solo");
            when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> {
                Chat c = inv.getArgument(0);
                c.setId(20L);
                c.setUuid(UUID.fromString(CHAT_UUID));
                return c;
            });
            when(chatMemberRepository.save(any(ChatMember.class))).thenAnswer(inv -> inv.getArgument(0));

            service.createChat(r, currentUser);

            verify(chatMemberRepository, times(1)).save(any(ChatMember.class));
        }

        @Test
        @DisplayName("group create skips self + unknown listed members")
        void groupSkipsSelfAndUnknown() {
            CreateChatRequest r = new CreateChatRequest();
            r.setName("Team");
            r.setMemberIds(List.of(CURRENT_UUID, OTHER_UUID, "44444444-4444-4444-4444-444444444444"));
            when(userRepository.findByUuid(UUID.fromString(CURRENT_UUID))).thenReturn(Optional.of(currentUser));
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(otherUser));
            when(userRepository.findByUuid(UUID.fromString("44444444-4444-4444-4444-444444444444")))
                    .thenReturn(Optional.empty());
            when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> {
                Chat c = inv.getArgument(0);
                c.setId(20L);
                c.setUuid(UUID.fromString(CHAT_UUID));
                return c;
            });
            when(chatMemberRepository.save(any(ChatMember.class))).thenAnswer(inv -> inv.getArgument(0));

            service.createChat(r, currentUser);

            // owner member + otherUser only (self + unknown skipped).
            verify(chatMemberRepository, times(2)).save(any(ChatMember.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChats")
    class GetChats {

        @Test
        @DisplayName("no chats → empty list")
        void empty() {
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of());

            assertThat(service.getChats(currentUser)).isEmpty();
        }

        @Test
        @DisplayName("multi-party chat is always included even with no last message")
        void includesMultiPartyWithoutMessages() {
            Chat group = groupChat(MemberRole.MEMBER);
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(group));

            List<ChatResponse> result = service.getChats(currentUser);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("1:1 chat with no messages is filtered out")
        void filtersEmptyPrivateChat() {
            Chat priv = privateChat();
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(priv));
            // findLastVisibleMessage default (empty) → no last message.

            assertThat(service.getChats(currentUser)).isEmpty();
        }

        @Test
        @DisplayName("duplicate 1:1 chats to the same peer collapse to the newest by last-message time")
        void dedupesPrivateChatsKeepingNewest() {
            Chat older = privateChat();
            older.setId(10L);
            Chat newer = privateChat();
            newer.setId(11L);
            newer.setUuid(UUID.fromString(OTHER_UUID));

            Message msgOld = messageFrom(otherUser, "2026-01-01T00:00:00Z");
            Message msgNew = messageFrom(otherUser, "2026-02-01T00:00:00Z");
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(older, newer));
            when(messageRepository.findLastVisibleMessage(eq(older), any(), any(), any()))
                    .thenReturn(List.of(msgOld));
            when(messageRepository.findLastVisibleMessage(eq(newer), any(), any(), any()))
                    .thenReturn(List.of(msgNew));
            when(messageMapper.toMessageResponse(msgOld))
                    .thenReturn(MessageResponse.builder().createdAt("2026-01-01T00:00:00Z").build());
            when(messageMapper.toMessageResponse(msgNew))
                    .thenReturn(MessageResponse.builder().createdAt("2026-02-01T00:00:00Z").build());

            List<ChatResponse> result = service.getChats(currentUser);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLastMessage().getCreatedAt()).isEqualTo("2026-02-01T00:00:00Z");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChatByUuid")
    class GetChatByUuid {

        @Test
        @DisplayName("participant reads the chat → mapped response")
        void returnsForMember() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));

            assertThat(service.getChatByUuid(CHAT_UUID, currentUser)).isNotNull();
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChatByUuid(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChatByUuid(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChatKey")
    class GetChatKey {

        @Test
        @DisplayName("encryption enabled → returns the raw base64 key + metadata")
        void enabled() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(messageCryptoService.isEnabled()).thenReturn(true);
            when(chatKeyService.getRawKeyBase64(10L)).thenReturn("BASE64KEY");

            ChatKeyResponse resp = service.getChatKey(CHAT_UUID, currentUser);

            assertThat(resp.isEnabled()).isTrue();
            assertThat(resp.getKey()).isEqualTo("BASE64KEY");
            assertThat(resp.getAlgo()).isEqualTo("AES-256-GCM");
            assertThat(resp.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("encryption disabled → enabled=false, no key")
        void disabled() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(messageCryptoService.isEnabled()).thenReturn(false);

            ChatKeyResponse resp = service.getChatKey(CHAT_UUID, currentUser);

            assertThat(resp.isEnabled()).isFalse();
            assertThat(resp.getKey()).isNull();
            verify(chatKeyService, never()).getRawKeyBase64(anyLong());
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChatKey(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChatKey(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("archiveChat")
    class ArchiveChat {

        @Test
        @DisplayName("archive=true → member persisted archived")
        void archives() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.archiveChat(CHAT_UUID, currentUser, true);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isArchived()).isTrue();
        }

        @Test
        @DisplayName("archive=false → member persisted un-archived")
        void unarchives() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            self.setArchived(true);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.archiveChat(CHAT_UUID, currentUser, false);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isArchived()).isFalse();
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.archiveChat(CHAT_UUID, currentUser, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.archiveChat(CHAT_UUID, currentUser, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("muteChat")
    class MuteChat {

        @Test
        @DisplayName("mute=true → member persisted muted")
        void mutes() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.muteChat(CHAT_UUID, currentUser, true);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isMuted()).isTrue();
        }

        @Test
        @DisplayName("mute=false → member persisted un-muted")
        void unmutes() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            self.setMuted(true);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.muteChat(CHAT_UUID, currentUser, false);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isMuted()).isFalse();
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.muteChat(CHAT_UUID, currentUser, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.muteChat(CHAT_UUID, currentUser, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("pinChat")
    class PinChat {

        @Test
        @DisplayName("pin=true → member persisted pinned")
        void pins() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.pinChat(CHAT_UUID, currentUser, true);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isPinned()).isTrue();
        }

        @Test
        @DisplayName("pin=false → member persisted un-pinned")
        void unpins() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            self.setPinned(true);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.pinChat(CHAT_UUID, currentUser, false);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isPinned()).isFalse();
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.pinChat(CHAT_UUID, currentUser, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.pinChat(CHAT_UUID, currentUser, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("clearChat")
    class ClearChat {

        @Test
        @DisplayName("clears history for the member → stamps clearedAt")
        void clears() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.clearChat(CHAT_UUID, currentUser);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().getClearedAt()).isNotNull();
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.clearChat(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.clearChat(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("deleteChat")
    class DeleteChat {

        @Test
        @DisplayName("1:1 delete → messages removed, chat + members soft-deleted, WS broadcast")
        void deletesPrivateChat() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(messageRepository.findByChat(chat)).thenReturn(List.of());

            service.deleteChat(CHAT_UUID, currentUser);

            verify(messageRepository).deleteAll(any());
            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().isDeleted()).isTrue();
            ArgumentCaptor<ChatMember> mCap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository, times(2)).save(mCap.capture());
            assertThat(mCap.getAllValues()).allSatisfy(m -> {
                assertThat(m.isDeleted()).isTrue();
                assertThat(m.isPinned()).isFalse();
                assertThat(m.isArchived()).isFalse();
            });
            // topic broadcast + a personal queue notify to the OTHER member only.
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + CHAT_UUID + "/messages"), any(Object.class));
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/chats"), any());
            verify(messagingTemplate, never()).convertAndSendToUser(eq("alice"), anyString(), any());
        }

        @Test
        @DisplayName("group OWNER may delete the whole group")
        void ownerDeletesGroup() {
            Chat chat = groupChat(MemberRole.OWNER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(messageRepository.findByChat(chat)).thenReturn(List.of());

            service.deleteChat(CHAT_UUID, currentUser);

            verify(chatRepository).save(any(Chat.class));
            verify(messageRepository).deleteAll(any());
        }

        @Test
        @DisplayName("non-owner deleting a group → ForbiddenException TM_291, nothing deleted")
        void nonOwnerCannotDeleteGroup() {
            Chat chat = groupChat(MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));

            assertThatThrownBy(() -> service.deleteChat(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_291"));
            verify(messageRepository, never()).deleteAll(any());
            verify(chatRepository, never()).save(any());
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteChat(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteChat(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("WS broadcast failure is swallowed — deletion still commits")
        void broadcastFailureSwallowed() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(messageRepository.findByChat(chat)).thenReturn(List.of());
            doThrowOnBroadcast();

            service.deleteChat(CHAT_UUID, currentUser);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().isDeleted()).isTrue();
        }

        private void doThrowOnBroadcast() {
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("markUnread")
    class MarkUnread {

        @Test
        @DisplayName("not already flagged → sets manuallyUnread and saves")
        void marksUnread() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.markUnread(CHAT_UUID, currentUser);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isManuallyUnread()).isTrue();
        }

        @Test
        @DisplayName("already flagged → idempotent, no save")
        void idempotentWhenAlreadyUnread() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            self.setManuallyUnread(true);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.markUnread(CHAT_UUID, currentUser);

            verify(chatMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.markUnread(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.markUnread(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("markRead")
    class MarkRead {

        @Test
        @DisplayName("1:1 with receipt updates → persists a READ outbox row + publishes the event")
        void marksRead1to1WithUpdates() throws Exception {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(readReceiptRepository.bulkMarkAsRead(eq(chat), eq(1L), any())).thenReturn(2);
            when(objectMapper.writeValueAsString(any())).thenReturn("{json}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            service.markRead(CHAT_UUID, currentUser);

            ArgumentCaptor<OutboxEvent> outboxCap = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(outboxCap.capture());
            assertThat(outboxCap.getValue().getEventType()).isEqualTo(StatusUpdateEvent.EVENT_TYPE);
            assertThat(outboxCap.getValue().getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(outboxCap.getValue().getPayload()).isEqualTo("{json}");

            ArgumentCaptor<StatusUpdateEvent> evCap = ArgumentCaptor.forClass(StatusUpdateEvent.class);
            verify(applicationEventPublisher).publishEvent(evCap.capture());
            assertThat(evCap.getValue().getEventName()).isEqualTo(StatusUpdateEvent.READ);
            assertThat(evCap.getValue().getChatUuid()).isEqualTo(CHAT_UUID);
            assertThat(evCap.getValue().getActorUserId()).isEqualTo(1L);
            assertThat(evCap.getValue().getActorUuid()).isEqualTo(CURRENT_UUID);
        }

        @Test
        @DisplayName("multi-party → advances the read watermark, no receipts/outbox")
        void marksReadGroupWatermark() {
            Chat chat = groupChat(MemberRole.MEMBER);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(messageRepository.findMaxMessageId(chat)).thenReturn(7L);

            service.markRead(CHAT_UUID, currentUser);

            verify(chatMemberRepository).advanceReadWatermark(eq(chat), any(), eq(7L));
            verify(readReceiptRepository, never()).bulkMarkAsRead(any(), anyLong(), any());
            verify(outboxEventRepository, never()).save(any());
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("opening the chat clears a sticky manuallyUnread flag")
        void clearsManuallyUnread() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            self.setManuallyUnread(true);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            service.markRead(CHAT_UUID, currentUser);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().isManuallyUnread()).isFalse();
        }

        @Test
        @DisplayName("no receipt updates → no outbox, no event")
        void noUpdatesNoOutbox() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            // bulkMarkAsRead + insertMissingReceipts default to 0.

            service.markRead(CHAT_UUID, currentUser);

            verify(outboxEventRepository, never()).save(any());
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("outbox serialize failure → IllegalStateException, event NOT published")
        void outboxFailurePropagates() throws Exception {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(readReceiptRepository.bulkMarkAsRead(eq(chat), eq(1L), any())).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));

            assertThatThrownBy(() -> service.markRead(CHAT_UUID, currentUser))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to persist status outbox event");
            verify(outboxEventRepository, never()).save(any());
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.markRead(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.markRead(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("markDelivered")
    class MarkDelivered {

        @Test
        @DisplayName("with receipt updates → persists a DELIVERED outbox row + publishes the event")
        void marksDeliveredWithUpdates() throws Exception {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(readReceiptRepository.bulkMarkAsDelivered(eq(chat), eq(1L), any())).thenReturn(3);
            when(objectMapper.writeValueAsString(any())).thenReturn("{json}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            service.markDelivered(CHAT_UUID, currentUser);

            verify(outboxEventRepository).save(any(OutboxEvent.class));
            ArgumentCaptor<StatusUpdateEvent> evCap = ArgumentCaptor.forClass(StatusUpdateEvent.class);
            verify(applicationEventPublisher).publishEvent(evCap.capture());
            assertThat(evCap.getValue().getEventName()).isEqualTo(StatusUpdateEvent.DELIVERED);
            assertThat(evCap.getValue().getActorUserId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("no receipt updates → no outbox, no event")
        void noUpdatesNoOutbox() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));

            service.markDelivered(CHAT_UUID, currentUser);

            verify(outboxEventRepository, never()).save(any());
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("outbox serialize failure → IllegalStateException, event NOT published")
        void outboxFailurePropagates() throws Exception {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(readReceiptRepository.bulkMarkAsDelivered(eq(chat), eq(1L), any())).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));

            assertThatThrownBy(() -> service.markDelivered(CHAT_UUID, currentUser))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to persist status outbox event");
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("unknown chat → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.markDelivered(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("non-participant → NotFoundException TM_141")
        void notMember() {
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(privateChat()));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.markDelivered(CHAT_UUID, currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("markAllChatsDelivered")
    class MarkAllChatsDelivered {

        @Test
        @DisplayName("non-ghost with updates → broadcasts messages_delivered per chat")
        void broadcastsWhenNotGhost() {
            Chat chat = privateChat();
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(chat));
            when(presenceService.isGhost(currentUser)).thenReturn(false);
            when(readReceiptRepository.bulkMarkAsDelivered(eq(chat), eq(1L), any())).thenReturn(2);

            service.markAllChatsDelivered(currentUser);

            verify(messagingTemplate)
                    .convertAndSend(eq("/topic/chat/" + CHAT_UUID + "/messages"), any(Object.class));
        }

        @Test
        @DisplayName("ghost recipient → delivery recorded but broadcast suppressed")
        void ghostSuppressesBroadcast() {
            Chat chat = privateChat();
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(chat));
            when(presenceService.isGhost(currentUser)).thenReturn(true);
            when(readReceiptRepository.bulkMarkAsDelivered(eq(chat), eq(1L), any())).thenReturn(2);

            service.markAllChatsDelivered(currentUser);

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("no receipt updates → no broadcast")
        void noUpdatesNoBroadcast() {
            Chat chat = privateChat();
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(chat));
            when(presenceService.isGhost(currentUser)).thenReturn(false);
            // bulkMarkAsDelivered + insertMissingReceipts default to 0.

            service.markAllChatsDelivered(currentUser);

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("no chats → no-op")
        void noChats() {
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of());
            when(presenceService.isGhost(currentUser)).thenReturn(false);

            service.markAllChatsDelivered(currentUser);

            verify(readReceiptRepository, never()).bulkMarkAsDelivered(any(), anyLong(), any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("broadcast throw for one chat is swallowed — loop still completes")
        void broadcastFailureSwallowed() {
            Chat chat = privateChat();
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(chat));
            when(presenceService.isGhost(currentUser)).thenReturn(false);
            when(readReceiptRepository.bulkMarkAsDelivered(eq(chat), eq(1L), any())).thenReturn(1);
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            // Must not propagate — the catch at the broadcast site swallows it.
            service.markAllChatsDelivered(currentUser);

            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("null PresenceService → treated as non-ghost, still broadcasts")
        void nullPresenceServiceTreatedAsNonGhost() {
            ChatServiceImpl noPresence = new ChatServiceImpl(
                    chatRepository, chatMemberRepository, memberCountCache, userSettingsCache,
                    blockCache, userRepository, messageRepository, readReceiptRepository,
                    userMapper, messageMapper, chatMapper, /* presenceService */ null, messagingTemplate,
                    friendRepository, applicationEventPublisher, objectMapper, outboxEventRepository,
                    chatKeyService, messageCryptoService);
            Chat chat = privateChat();
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(chat));
            when(readReceiptRepository.bulkMarkAsDelivered(eq(chat), eq(1L), any())).thenReturn(2);

            noPresence.markAllChatsDelivered(currentUser);

            verify(messagingTemplate)
                    .convertAndSend(eq("/topic/chat/" + CHAT_UUID + "/messages"), any(Object.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ensureManagedUser (via getChats)")
    class EnsureManagedUser {

        @Test
        @DisplayName("null current user → returns null (no reload), empty result")
        void nullCurrentUser() {
            // ensureManagedUser short-circuits to null; findChatsByUser is called with null.
            when(chatRepository.findChatsByUser(any())).thenReturn(List.of());

            assertThat(service.getChats(null)).isEmpty();
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("transient user (null id) → returned as-is, never reloaded")
        void transientUserNotReloaded() {
            User transientUser = User.builder().username("ghost").name("Ghost").build(); // id == null
            when(chatRepository.findChatsByUser(transientUser)).thenReturn(List.of());

            assertThat(service.getChats(transientUser)).isEmpty();
            verify(userRepository, never()).findById(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("createChat — WS notify failure paths")
    class CreateChatWsFailures {

        private CreateChatRequest privateRequest() {
            CreateChatRequest r = new CreateChatRequest();
            r.setRecipientId(OTHER_UUID);
            return r;
        }

        @Test
        @DisplayName("new 1:1 → recipient WS notify throwing is swallowed, chat still returned")
        void newChatNotifyFailureSwallowed() {
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(otherUser));
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());
            when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> {
                Chat c = inv.getArgument(0);
                c.setId(10L);
                c.setUuid(UUID.fromString(CHAT_UUID));
                return c;
            });
            when(chatMemberRepository.save(any(ChatMember.class))).thenAnswer(inv -> inv.getArgument(0));
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            ChatResponse resp = service.createChat(privateRequest(), currentUser);

            assertThat(resp).isNotNull();
            verify(chatMemberRepository, times(2)).save(any(ChatMember.class));
        }

        @Test
        @DisplayName("reused ACTIVE 1:1 → recipient WS notify throwing is swallowed, chat still returned")
        void reusedChatNotifyFailureSwallowed() {
            Chat existing = privateChat();
            when(userRepository.findByUuid(UUID.fromString(OTHER_UUID))).thenReturn(Optional.of(otherUser));
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(existing));
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            ChatResponse resp = service.createChat(privateRequest(), currentUser);

            assertThat(resp).isNotNull();
            verify(chatRepository, never()).save(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChats — dedup/filter/sort edge branches")
    class GetChatsEdges {

        @Test
        @DisplayName("1:1 with a message but NO other member → added directly (memberOther null)")
        void privateChatWithoutOtherMemberIncluded() {
            Chat lonely = Chat.builder().chatType(ChatType.PRIVATE).build();
            lonely.setId(12L);
            lonely.setUuid(UUID.fromString(CHAT_UUID));
            lonely.getMembers().add(member(lonely, currentUser, MemberRole.MEMBER));
            Message own = messageFrom(currentUser, "2026-01-01T00:00:00Z");
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(lonely));
            when(messageRepository.findLastVisibleMessage(eq(lonely), any(), any(), any()))
                    .thenReturn(List.of(own));
            when(messageMapper.toMessageResponse(own))
                    .thenReturn(MessageResponse.builder().createdAt("2026-01-01T00:00:00Z").build());

            List<ChatResponse> result = service.getChats(currentUser);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("duplicate 1:1 with NEWER processed first → keeps existing (older does not replace)")
        void dedupeKeepsExistingWhenOlderSecond() {
            Chat newer = privateChat();
            newer.setId(11L);
            newer.setUuid(UUID.fromString(OTHER_UUID));
            Chat older = privateChat();
            older.setId(10L);

            Message msgNew = messageFrom(otherUser, "2026-02-01T00:00:00Z");
            Message msgOld = messageFrom(otherUser, "2026-01-01T00:00:00Z");
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(newer, older));
            when(messageRepository.findLastVisibleMessage(eq(newer), any(), any(), any()))
                    .thenReturn(List.of(msgNew));
            when(messageRepository.findLastVisibleMessage(eq(older), any(), any(), any()))
                    .thenReturn(List.of(msgOld));
            when(messageMapper.toMessageResponse(msgNew))
                    .thenReturn(MessageResponse.builder().createdAt("2026-02-01T00:00:00Z").build());
            when(messageMapper.toMessageResponse(msgOld))
                    .thenReturn(MessageResponse.builder().createdAt("2026-01-01T00:00:00Z").build());

            List<ChatResponse> result = service.getChats(currentUser);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLastMessage().getCreatedAt()).isEqualTo("2026-02-01T00:00:00Z");
        }

        @Test
        @DisplayName("mixed list (group w/o message + 1:1 with message) exercises the final sort comparator")
        void sortsMixedList() {
            Chat group = groupChat(MemberRole.MEMBER);
            group.setUuid(UUID.fromString(OTHER_UUID));
            Chat priv = privateChat();
            Message own = messageFrom(otherUser, "2026-05-01T00:00:00Z");
            when(chatRepository.findChatsByUser(currentUser)).thenReturn(List.of(group, priv));
            when(messageRepository.findLastVisibleMessage(eq(priv), any(), any(), any()))
                    .thenReturn(List.of(own));
            // group's findLastVisibleMessage → empty → null last message (the "" side).
            when(messageRepository.findLastVisibleMessage(eq(group), any(), any(), any()))
                    .thenReturn(List.of());
            when(messageMapper.toMessageResponse(own))
                    .thenReturn(MessageResponse.builder().createdAt("2026-05-01T00:00:00Z").build());

            List<ChatResponse> result = service.getChats(currentUser);

            assertThat(result).hasSize(2);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("markRead / markDelivered — inserted-only update branch")
    class InsertedOnlyUpdates {

        @Test
        @DisplayName("markRead: only inserted receipts (bulk=0) still counts as updates → outbox + event")
        void markReadInsertedOnly() throws Exception {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            // bulkMarkAsRead defaults to 0; the insert path supplies the update.
            when(readReceiptRepository.insertMissingReceipts(eq(10L), eq(1L), eq("READ"), any(), any(), any()))
                    .thenReturn(2);
            when(objectMapper.writeValueAsString(any())).thenReturn("{json}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            service.markRead(CHAT_UUID, currentUser);

            verify(outboxEventRepository).save(any(OutboxEvent.class));
            verify(applicationEventPublisher).publishEvent(any(StatusUpdateEvent.class));
        }

        @Test
        @DisplayName("markDelivered: only inserted receipts (bulk=0) still counts as updates → outbox + event")
        void markDeliveredInsertedOnly() throws Exception {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(readReceiptRepository.insertMissingReceipts(eq(10L), eq(1L), eq("DELIVERED"), any(), any(), any()))
                    .thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{json}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            service.markDelivered(CHAT_UUID, currentUser);

            verify(outboxEventRepository).save(any(OutboxEvent.class));
            verify(applicationEventPublisher).publishEvent(any(StatusUpdateEvent.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("deleteChat — WS fan-out member iteration")
    class DeleteChatFanOut {

        @Test
        @DisplayName("group delete skips a member row whose user is null during WS fan-out")
        void skipsNullUserMemberOnFanOut() {
            Chat chat = groupChat(MemberRole.OWNER); // current(OWNER) + bob
            ChatMember nullUserMember = ChatMember.builder().chat(chat).user(null).build();
            nullUserMember.setRole(MemberRole.MEMBER);
            chat.getMembers().add(nullUserMember);
            when(chatRepository.findByUuid(any())).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(messageRepository.findByChat(chat)).thenReturn(List.of());

            service.deleteChat(CHAT_UUID, currentUser);

            // Only the real other member (bob) is notified; the null-user row is skipped
            // and the current user is never self-notified.
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/chats"), any());
            verify(messagingTemplate, never()).convertAndSendToUser(eq("alice"), anyString(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("mapToChatResponse — status/detail/group branches (via getChatByUuid)")
    class MapToChatResponseBranches {

        private static final String CAROL_UUID = "44444444-4444-4444-4444-444444444444";

        private User carol() {
            return user(3L, CAROL_UUID, "carol", "Carol");
        }

        private MessageReadReceipt receipt(User u, String status) {
            return MessageReadReceipt.builder().user(u).status(status).build();
        }

        /**
         * A GROUP with the current user (OWNER, first) followed by the given members.
         */
        private Chat ghostGroup(User... others) {
            Chat c = Chat.builder().chatType(ChatType.GROUP).name("G").build();
            c.setId(40L);
            c.setUuid(UUID.fromString(CHAT_UUID));
            c.getMembers().add(member(c, currentUser, MemberRole.OWNER));
            for (User u : others) {
                c.getMembers().add(member(c, u, MemberRole.MEMBER));
            }
            return c;
        }

        private void stubGuard(Chat chat) {
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(chat.getMembers().getFirst()));
        }

        @Test
        @DisplayName("own last message + ghost recipient with a non-ghost READ → status recomputed to READ")
        void ownMessageGhostRecomputesRead() {
            User carol = carol();
            Chat chat = ghostGroup(otherUser, carol);
            // Defensive null-user member row (never dereferenced because current is first).
            ChatMember nullUserMember = ChatMember.builder().chat(chat).user(null).build();
            nullUserMember.setRole(MemberRole.MEMBER);
            chat.getMembers().add(nullUserMember);

            Message own = messageFrom(currentUser, "2026-03-01T00:00:00Z");
            own.setReadReceipts(List.of(
                    receipt(currentUser, "READ"), // sender's own receipt → skipped
                    receipt(otherUser, "READ"),   // bob is a ghost → skipped
                    receipt(carol, "READ")));     // non-ghost READ → wins
            stubGuard(chat);
            when(messageRepository.findLastVisibleMessage(eq(chat), any(), any(), any()))
                    .thenReturn(List.of(own));
            when(messageMapper.toMessageResponse(own))
                    .thenReturn(MessageResponse.builder().status("SENT").createdAt("2026-03-01T00:00:00Z").build());
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of(2L));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getLastMessage().getStatus()).isEqualTo("READ");
        }

        @Test
        @DisplayName("own last message + ghost recipient with a non-ghost DELIVERED → status DELIVERED")
        void ownMessageGhostRecomputesDelivered() {
            User carol = carol();
            Chat chat = ghostGroup(otherUser, carol);
            Message own = messageFrom(currentUser, "2026-03-01T00:00:00Z");
            own.setReadReceipts(List.of(
                    receipt(currentUser, "READ"),      // sender skip
                    receipt(carol, "DELIVERED")));     // non-ghost delivered
            stubGuard(chat);
            when(messageRepository.findLastVisibleMessage(eq(chat), any(), any(), any()))
                    .thenReturn(List.of(own));
            when(messageMapper.toMessageResponse(own))
                    .thenReturn(MessageResponse.builder().status("SENT").createdAt("2026-03-01T00:00:00Z").build());
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of(2L));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getLastMessage().getStatus()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("own last message where every visible receipt is sender/ghost → caps at SENT")
        void ownMessageGhostAllHiddenCapsSent() {
            Chat chat = ghostGroup(otherUser);
            Message own = messageFrom(currentUser, "2026-03-01T00:00:00Z");
            own.setReadReceipts(List.of(
                    receipt(currentUser, "READ"),      // sender skip
                    receipt(otherUser, "DELIVERED")));  // bob ghost skip
            stubGuard(chat);
            when(messageRepository.findLastVisibleMessage(eq(chat), any(), any(), any()))
                    .thenReturn(List.of(own));
            when(messageMapper.toMessageResponse(own))
                    .thenReturn(MessageResponse.builder().status("DELIVERED").createdAt("2026-03-01T00:00:00Z").build());
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of(2L));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getLastMessage().getStatus()).isEqualTo("SENT");
        }

        @Test
        @DisplayName("own last message with NO receipts + a ghost member → SENT (empty-receipts branch)")
        void ownMessageGhostEmptyReceiptsSent() {
            Chat chat = ghostGroup(otherUser);
            Message own = messageFrom(currentUser, "2026-03-01T00:00:00Z");
            own.setReadReceipts(List.of());
            stubGuard(chat);
            when(messageRepository.findLastVisibleMessage(eq(chat), any(), any(), any()))
                    .thenReturn(List.of(own));
            when(messageMapper.toMessageResponse(own))
                    .thenReturn(MessageResponse.builder().status("DELIVERED").createdAt("2026-03-01T00:00:00Z").build());
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of(2L));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getLastMessage().getStatus()).isEqualTo("SENT");
        }

        @Test
        @DisplayName("own last message but NO ghost members → status left untouched")
        void ownMessageNoGhostsKeepsStatus() {
            Chat chat = ghostGroup(otherUser);
            Message own = messageFrom(currentUser, "2026-03-01T00:00:00Z");
            stubGuard(chat);
            when(messageRepository.findLastVisibleMessage(eq(chat), any(), any(), any()))
                    .thenReturn(List.of(own));
            when(messageMapper.toMessageResponse(own))
                    .thenReturn(MessageResponse.builder().status("DELIVERED").createdAt("2026-03-01T00:00:00Z").build());
            when(presenceService.getGhostUserIds(any())).thenReturn(Set.of()); // no ghosts

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getLastMessage().getStatus()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("private chat: friend (not deleted) + peer blocked-me + last-seen visible → masked")
        void privateChatFriendBlockedMasked() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            Friend friend = Friend.builder().build(); // isDeleted == false
            when(friendRepository.findByUserAndFriend(currentUser, otherUser)).thenReturn(Optional.of(friend));
            when(presenceService.getApparentLastSeen(otherUser)).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
            when(blockCache.hasBlocked(currentUser, otherUser.getId())).thenReturn(false); // I did not block them
            when(blockCache.hasBlocked(otherUser, 1L)).thenReturn(true); // peer blocked me

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.isFriend()).isTrue();
            // Masked: presence forced offline, last-seen dropped.
            assertThat(resp.getOtherUser().getPresence()).isEqualTo("offline");
            assertThat(resp.getOtherUser().getLastSeen()).isNull();
        }

        @Test
        @DisplayName("private chat: friend row exists but is soft-deleted → not a friend")
        void privateChatDeletedFriendNotFriend() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            Friend friend = Friend.builder().build();
            friend.setDeleted(true);
            when(friendRepository.findByUserAndFriend(currentUser, otherUser)).thenReturn(Optional.of(friend));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.isFriend()).isFalse();
        }

        @Test
        @DisplayName("private chat with no other member → other-user block skipped")
        void privateChatNoOtherMember() {
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE).build();
            chat.setId(13L);
            chat.setUuid(UUID.fromString(CHAT_UUID));
            chat.getMembers().add(member(chat, currentUser, MemberRole.MEMBER));
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp).isNotNull();
            assertThat(resp.getOtherUser()).isNull();
        }

        @Test
        @DisplayName("STRANGER chat is mapped through the 1:1 (other-user) branch")
        void strangerChatMappedLikePrivate() {
            Chat chat = Chat.builder().chatType(ChatType.STRANGER).build();
            chat.setId(14L);
            chat.setUuid(UUID.fromString(CHAT_UUID));
            chat.getMembers().add(member(chat, currentUser, MemberRole.MEMBER));
            chat.getMembers().add(member(chat, otherUser, MemberRole.MEMBER));
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getOtherUser()).isNotNull();
            assertThat(resp.getName()).isEqualTo("Bob");
        }

        @Test
        @DisplayName("private chat: manual-unread with zero genuine unread forces the badge to 1")
        void privateManualUnreadForcesBadge() {
            Chat chat = privateChat();
            ChatMember self = selfMemberOf(chat);
            self.setManuallyUnread(true);
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));
            // countUnreadMessages defaults to 0 → forced to 1 by the manual-unread flag.

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getUnreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("group: viewer is NOT a member → memberSelf null, unread 0, no role, inactive")
        void groupViewerNotMember() {
            Chat chat = Chat.builder().chatType(ChatType.GROUP).name("Others").build();
            chat.setId(41L);
            chat.setUuid(UUID.fromString(CHAT_UUID));
            chat.getMembers().add(member(chat, otherUser, MemberRole.MEMBER));
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            // Guard passes with any membership Optional; mapToChatResponse recomputes from members.
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(chat.getMembers().getFirst()));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getUnreadCount()).isZero();
            assertThat(resp.getGroup().getMyRole()).isNull();
            assertThat(resp.getGroup().isActive()).isFalse();
        }

        @Test
        @DisplayName("group: viewer has LEFT (leftAt set) → unread 0, inactive")
        void groupMemberLeft() {
            Chat chat = groupChat(MemberRole.MEMBER);
            ChatMember self = selfMemberOf(chat);
            self.setLeftAt(Instant.parse("2026-01-01T00:00:00Z"));
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getUnreadCount()).isZero();
            assertThat(resp.getGroup().isActive()).isFalse();
        }

        @Test
        @DisplayName("group: active member with a read watermark → unread from watermark count")
        void groupWatermarkUnread() {
            Chat chat = groupChat(MemberRole.MEMBER);
            ChatMember self = selfMemberOf(chat);
            self.setLastReadMessageId(5L);
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any())).thenReturn(Optional.of(self));
            when(messageRepository.countUnreadForWatermark(eq(chat), eq(1L), eq(5L), any())).thenReturn(3L);

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getUnreadCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("buildGroupInfo: owner + pinned message + tags resolved")
        void groupInfoWithOwnerPinnedAndTags() {
            Chat chat = groupChat(MemberRole.OWNER);
            chat.setOwnerId(2L);
            chat.setTags(Set.of(Interest.MUSIC));
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));
            when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));
            Message pinned = new Message();
            pinned.setUuid(UUID.fromString(OTHER_UUID));
            when(messageRepository.findFirstByChatAndPinnedTrueOrderByPinnedAtDesc(chat))
                    .thenReturn(Optional.of(pinned));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getGroup().getOwnerId()).isEqualTo(OTHER_UUID);
            assertThat(resp.getGroup().getPinnedMessageId()).isEqualTo(OTHER_UUID);
            assertThat(resp.getGroup().getTags()).containsExactly("MUSIC");
        }

        @Test
        @DisplayName("buildGroupInfo: null settings fall back to defaults + null tags → empty list")
        void groupInfoNullSettingsAndTags() {
            Chat chat = groupChat(MemberRole.OWNER);
            chat.setSettings(null);
            chat.setTags(null);
            when(chatRepository.findByUuid(UUID.fromString(CHAT_UUID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(any(), any()))
                    .thenReturn(Optional.of(selfMemberOf(chat)));

            ChatResponse resp = service.getChatByUuid(CHAT_UUID, currentUser);

            assertThat(resp.getGroup().getTags()).isEmpty();
            // Default ChatSettings applied → whoCanSend present.
            assertThat(resp.getGroup().getWhoCanSend()).isEqualTo("EVERYONE");
        }
    }
}
