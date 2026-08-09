package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.MemberCountCache;
import com.chat.talkMe.cache.UserSettingsCache;
import com.chat.talkMe.domain.AuditLog;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.ChatSettings;
import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.domain.GroupInvite;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.request.UpdateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.GroupMemberResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.ChatVisibility;
import com.chat.talkMe.enums.GroupAddPrivacy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.JoinPolicy;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.enums.SendPolicy;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.AuditLogRepository;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.GroupInviteRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.service.ChatService;
import com.chat.talkMe.service.EventService;
import com.chat.talkMe.service.GroupAuthzService;
import com.chat.talkMe.service.MessageService;
import com.chat.talkMe.service.NotificationService;
import com.chat.talkMe.service.PresenceService;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link GroupServiceImpl} — group/channel/room lifecycle:
 * create, update, member add/remove, role changes, join/leave, transfer-ownership, invites
 * (send / accept / decline), discovery, and reporting. Authorization is delegated to a mocked
 * {@link GroupAuthzService}; this suite verifies the impl's own branching, side effects
 * (member saves, system messages, WebSocket broadcasts, cache eviction, notifications) and the
 * exact {@code TM_###} code on every rejection path. A real {@link ObjectMapper} is used so the
 * system-event / invite JSON serialization is exercised end-to-end.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GroupServiceImpl (unit)")
class GroupServiceImplTest {

    private static final UUID CHAT_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CREATOR_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String CHAT_UUID_STR = CHAT_UUID.toString();
    private static final String TARGET_UUID_STR = TARGET_UUID.toString();

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GroupAuthzService authz;
    @Mock
    private ChatService chatService;
    @Mock
    private MessageService messageService;
    @Mock
    private PresenceService presenceService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private GroupInviteRepository groupInviteRepository;
    @Mock
    private MemberCountCache memberCountCache;
    @Mock
    private UserSettingsCache userSettingsCache;
    @Mock
    private ObjectProvider<EventService> eventServiceProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GroupServiceImpl service;

    private User creator;

    @BeforeEach
    void setUp() {
        service = new GroupServiceImpl(chatRepository, chatMemberRepository, userRepository, authz,
                chatService, messageService, presenceService, messagingTemplate, objectMapper,
                friendRepository, auditLogRepository, notificationService, userSettingRepository,
                groupInviteRepository, memberCountCache, userSettingsCache, eventServiceProvider);

        creator = user(1L, CREATOR_UUID, "Owner", "owner");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /**
     * Builds a persisted-looking {@link User} (id + uuid pre-set) with a deterministic profile image.
     */
    private User user(long id, UUID uuid, String name, String username) {
        User u = User.builder().name(name).username(username).profileImage("img-" + username).build();
        u.setId(id);
        u.setUuid(uuid);
        return u;
    }

    /**
     * A default PRIVATE / INVITE_ONLY {@link ChatType#GROUP} chat with empty settings, used as the
     * common subject-under-test in most nested groups.
     */
    private Chat groupChat() {
        Chat c = Chat.builder()
                .name("Squad")
                .chatType(ChatType.GROUP)
                .visibility(ChatVisibility.PRIVATE)
                .joinPolicy(JoinPolicy.INVITE_ONLY)
                .memberLimit(256)
                .settings(ChatSettings.builder().build())
                .build();
        c.setId(10L);
        c.setUuid(CHAT_UUID);
        return c;
    }

    /**
     * An active membership row (no leftAt) joining the given user to the chat with the given role.
     */
    private ChatMember member(Chat chat, User user, MemberRole role) {
        ChatMember m = ChatMember.builder().chat(chat).user(user).joinedAt(Instant.now()).build();
        m.setRole(role);
        return m;
    }

    /**
     * Stub loadGroup() to resolve to the given chat.
     */
    private void stubLoad(Chat chat) {
        when(chatRepository.findByUuidWithMembers(CHAT_UUID)).thenReturn(Optional.of(chat));
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createGroup")
    class CreateGroup {

        /**
         * Lenient stubs shared by every createGroup test: creator lookup, a save() that mints the
         * chat uuid when absent, and a DTO round-trip through the mocked {@link ChatService}.
         */
        @BeforeEach
        void stubCommon() {
            lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            lenient().when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> {
                Chat c = inv.getArgument(0);
                if (c.getUuid() == null) c.setUuid(CHAT_UUID);
                return c;
            });
            lenient().when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
        }

        @Test
        @DisplayName("default group → GROUP/PRIVATE/INVITE_ONLY, EVERYONE send policy, creator saved as OWNER")
        void defaultGroup() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Squad");

            service.createGroup(req, creator);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            Chat saved = chatCap.getValue();
            assertThat(saved.getChatType()).isEqualTo(ChatType.GROUP);
            assertThat(saved.getVisibility()).isEqualTo(ChatVisibility.PRIVATE);
            assertThat(saved.getJoinPolicy()).isEqualTo(JoinPolicy.INVITE_ONLY);
            assertThat(saved.isAllowNonFriends()).isFalse();
            assertThat(saved.getSettings().getWhoCanSend()).isEqualTo(SendPolicy.EVERYONE);
            assertThat(saved.getOwnerId()).isEqualTo(1L);

            ArgumentCaptor<ChatMember> memberCap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(memberCap.capture());
            assertThat(memberCap.getValue().getRole()).isEqualTo(MemberRole.OWNER);
            verify(memberCountCache).evict(saved);
        }

        @Test
        @DisplayName("channel subtype → CHANNEL with ADMINS_ONLY send policy")
        void channel() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("News");
            req.setSubtype("channel");

            service.createGroup(req, creator);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().getChatType()).isEqualTo(ChatType.CHANNEL);
            assertThat(chatCap.getValue().getSettings().getWhoCanSend()).isEqualTo(SendPolicy.ADMINS_ONLY);
        }

        @Test
        @DisplayName("room subtype → ROOM is PUBLIC, OPEN and allows non-friends")
        void room() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Lounge");
            req.setSubtype("room");

            service.createGroup(req, creator);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            Chat saved = chatCap.getValue();
            assertThat(saved.getChatType()).isEqualTo(ChatType.ROOM);
            assertThat(saved.getVisibility()).isEqualTo(ChatVisibility.PUBLIC);
            assertThat(saved.getJoinPolicy()).isEqualTo(JoinPolicy.OPEN);
            assertThat(saved.isAllowNonFriends()).isTrue();
        }

        @Test
        @DisplayName("PUBLIC channel → OPEN join policy")
        void publicChannelIsOpen() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Open Channel");
            req.setSubtype("channel");
            req.setVisibility("PUBLIC");

            service.createGroup(req, creator);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().getVisibility()).isEqualTo(ChatVisibility.PUBLIC);
            assertThat(chatCap.getValue().getJoinPolicy()).isEqualTo(JoinPolicy.OPEN);
        }

        @Test
        @DisplayName("PUBLIC group stays INVITE_ONLY (only channels/rooms open on public)")
        void publicGroupStaysInviteOnly() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Public Group");
            req.setVisibility("PUBLIC");

            service.createGroup(req, creator);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().getVisibility()).isEqualTo(ChatVisibility.PUBLIC);
            assertThat(chatCap.getValue().getJoinPolicy()).isEqualTo(JoinPolicy.INVITE_ONLY);
        }

        @Test
        @DisplayName("tags: valid interest tags parsed, unknown tags skipped")
        void tagsParsed() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Tagged");
            req.setTags(List.of("music", "NOT_A_TAG", "GAMING"));

            service.createGroup(req, creator);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().getTags())
                    .containsExactlyInAnyOrder(Interest.MUSIC, Interest.GAMING);
        }

        @Test
        @DisplayName("initial members: a friend is added, self / unknown / bad-uuid / non-friend are skipped")
        void initialMembers() {
            User friend = user(2L, TARGET_UUID, "Friend", "friend");
            UUID nonFriendUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
            User nonFriend = user(3L, nonFriendUuid, "Stranger", "stranger");

            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Squad");
            req.setMemberIds(List.of(
                    TARGET_UUID_STR,               // friend → added
                    nonFriendUuid.toString(),      // not a friend, allowNonFriends=false → skipped
                    CREATOR_UUID.toString(),       // self → skipped
                    "not-a-uuid",                  // malformed → skipped
                    "99999999-9999-9999-9999-999999999999")); // unknown user → skipped

            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(friend));
            when(userRepository.findByUuid(nonFriendUuid)).thenReturn(Optional.of(nonFriend));
            when(userRepository.findByUuid(CREATOR_UUID)).thenReturn(Optional.of(creator)); // self → skipped
            when(userRepository.findByUuid(UUID.fromString("99999999-9999-9999-9999-999999999999")))
                    .thenReturn(Optional.empty());
            Friend link = Friend.builder().user(creator).friend(friend).build();
            when(friendRepository.findByUserAndFriend(creator, friend)).thenReturn(Optional.of(link));
            when(friendRepository.findByUserAndFriend(creator, nonFriend)).thenReturn(Optional.empty());

            service.createGroup(req, creator);

            // owner + exactly the one friend saved (2 member saves total).
            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository, Mockito.times(2)).save(cap.capture());
            assertThat(cap.getAllValues()).anySatisfy(m -> {
                assertThat(m.getUser()).isEqualTo(friend);
                assertThat(m.getRole()).isEqualTo(MemberRole.MEMBER);
            });
        }

        @Test
        @DisplayName("returns the freshly-built chat DTO via chatService")
        void returnsDto() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Squad");

            ChatResponse out = service.createGroup(req, creator);

            assertThat(out.getId()).isEqualTo(CHAT_UUID_STR);
            verify(chatService).getChatByUuid(eq(CHAT_UUID_STR), any());
        }

        @Test
        @DisplayName("allowNonFriends=true group → a non-friend is added without any friend check (line 82 + 128 short-circuit)")
        void groupAllowsNonFriendsAddsNonFriend() {
            User nonFriend = user(3L, TARGET_UUID, "Stranger", "stranger");
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Open Squad");
            req.setAllowNonFriends(true);
            req.setMemberIds(List.of(TARGET_UUID_STR));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(nonFriend));

            service.createGroup(req, creator);

            ArgumentCaptor<Chat> chatCap = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chatCap.capture());
            assertThat(chatCap.getValue().isAllowNonFriends()).isTrue();
            // owner + the non-friend saved (2 member saves), and NO friendship lookup happened.
            verify(chatMemberRepository, Mockito.times(2)).save(any(ChatMember.class));
            verify(friendRepository, never()).findByUserAndFriend(any(), any());
        }

        @Test
        @DisplayName("member ids that are null / blank / 'undefined' / 'null' are all skipped by tryUuid (line 684 guards)")
        void skipsBlankNullUndefinedMemberIds() {
            CreateGroupRequest req = new CreateGroupRequest();
            req.setName("Squad");
            req.setMemberIds(Arrays.asList(null, "   ", "undefined", "null"));

            service.createGroup(req, creator);

            // Only the owner is saved; every member id was rejected before any lookup.
            verify(chatMemberRepository, Mockito.times(1)).save(any(ChatMember.class));
            verify(userRepository, never()).findByUuid(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateGroup")
    class UpdateGroup {

        @Test
        @DisplayName("editor applies fields + settings, saves and broadcasts group_updated")
        void updatesFields() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            UpdateGroupRequest req = new UpdateGroupRequest();
            req.setName("Renamed");
            req.setDescription("desc");
            req.setVisibility("PUBLIC");
            req.setJoinPolicy("OPEN");
            req.setWhoCanSend("ADMINS_ONLY");
            req.setWhoCanAddMembers("ADMIN");
            req.setWhoCanEditInfo("OWNER");
            req.setWhoCanPin("OWNER");
            req.setSlowModeSeconds(-5); // clamped to 0

            service.updateGroup(CHAT_UUID_STR, req, creator);

            assertThat(chat.getName()).isEqualTo("Renamed");
            assertThat(chat.getDescription()).isEqualTo("desc");
            assertThat(chat.getVisibility()).isEqualTo(ChatVisibility.PUBLIC);
            assertThat(chat.getJoinPolicy()).isEqualTo(JoinPolicy.OPEN);
            assertThat(chat.getSettings().getWhoCanSend()).isEqualTo(SendPolicy.ADMINS_ONLY);
            assertThat(chat.getSettings().getWhoCanAddMembers()).isEqualTo(MemberRole.ADMIN);
            assertThat(chat.getSettings().getWhoCanEditInfo()).isEqualTo(MemberRole.OWNER);
            assertThat(chat.getSettings().getWhoCanPin()).isEqualTo(MemberRole.OWNER);
            assertThat(chat.getSettings().getSlowModeSeconds()).isZero();
            verify(chatRepository).save(chat);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), any(Object.class));
        }

        @Test
        @DisplayName("member below whoCanEditInfo → ForbiddenException TM_291, nothing saved")
        void insufficientRole() {
            Chat chat = groupChat(); // whoCanEditInfo defaults to ADMIN
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));

            assertThatThrownBy(() -> service.updateGroup(CHAT_UUID_STR, new UpdateGroupRequest(), creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_291"));
            verify(chatRepository, never()).save(any());
        }

        @Test
        @DisplayName("group not found → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuidWithMembers(CHAT_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateGroup(CHAT_UUID_STR, new UpdateGroupRequest(), creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("target is a 1:1 chat (not multi-party) → BadRequestException TM_299")
        void notAGroup() {
            Chat oneToOne = groupChat();
            oneToOne.setChatType(ChatType.PRIVATE);
            stubLoad(oneToOne);

            assertThatThrownBy(() -> service.updateGroup(CHAT_UUID_STR, new UpdateGroupRequest(), creator))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_299"));
        }

        @Test
        @DisplayName("malformed chat uuid → BadRequestException TM_300")
        void invalidUuid() {
            assertThatThrownBy(() -> service.updateGroup("not-a-uuid", new UpdateGroupRequest(), creator))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_300"));
        }

        @Test
        @DisplayName("only image/flags set → those applied, every other field left untouched (false side of each optional guard)")
        void updatesOnlyImageFlagsLeavesRestUntouched() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.OWNER));
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            UpdateGroupRequest req = new UpdateGroupRequest();
            req.setImageUrl("new.png");
            req.setAllowNonFriends(true);
            req.setAllowExplicitContent(true);
            // name/description/visibility/joinPolicy/whoCan*/slowMode all left null

            service.updateGroup(CHAT_UUID_STR, req, creator);

            assertThat(chat.getImageUrl()).isEqualTo("new.png");
            assertThat(chat.isAllowNonFriends()).isTrue();
            assertThat(chat.isAllowExplicitContent()).isTrue();
            // untouched:
            assertThat(chat.getName()).isEqualTo("Squad");
            assertThat(chat.getVisibility()).isEqualTo(ChatVisibility.PRIVATE);
            assertThat(chat.getJoinPolicy()).isEqualTo(JoinPolicy.INVITE_ONLY);
            assertThat(chat.getSettings().getWhoCanSend()).isEqualTo(SendPolicy.EVERYONE);
            verify(chatRepository).save(chat);
        }

        @Test
        @DisplayName("WebSocket broadcast failure is swallowed → update still commits (broadcastGroupEvent catch)")
        void broadcastFailureSwallowed() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.OWNER));
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
            doThrow(new RuntimeException("ws down")).when(messagingTemplate)
                    .convertAndSend(anyString(), any(Object.class));

            UpdateGroupRequest req = new UpdateGroupRequest();
            req.setName("Renamed");

            ChatResponse out = service.updateGroup(CHAT_UUID_STR, req, creator);

            assertThat(out.getId()).isEqualTo(CHAT_UUID_STR);
            verify(chatRepository).save(chat); // save (line 161) happens before the broadcast
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getMembers")
    class GetMembers {

        @Test
        @DisplayName("returns active members with role + presence, excludes former members")
        void listsActiveMembers() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.OWNER));

            ChatMember active = member(chat, creator, MemberRole.OWNER);
            User other = user(2L, TARGET_UUID, "Bob", "bob");
            ChatMember left = member(chat, other, MemberRole.MEMBER);
            left.setLeftAt(Instant.now());
            when(chatMemberRepository.findByChat(chat)).thenReturn(List.of(active, left));
            when(presenceService.getStatus(creator)).thenReturn(PresenceStatus.ONLINE);

            List<GroupMemberResponse> out = service.getMembers(CHAT_UUID_STR, creator);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getUserId()).isEqualTo(CREATOR_UUID.toString());
            assertThat(out.get(0).getRole()).isEqualTo("OWNER");
            assertThat(out.get(0).getPresence()).isEqualTo("online");
        }

        @Test
        @DisplayName("empty membership list → empty result, no NPE")
        void emptyList() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.OWNER));
            when(chatMemberRepository.findByChat(chat)).thenReturn(List.of());

            assertThat(service.getMembers(CHAT_UUID_STR, creator)).isEmpty();
        }

        @Test
        @DisplayName("null PresenceService → presence falls back to 'offline'")
        void nullPresence() {
            GroupServiceImpl noPresence = new GroupServiceImpl(chatRepository, chatMemberRepository,
                    userRepository, authz, chatService, messageService, null, messagingTemplate,
                    objectMapper, friendRepository, auditLogRepository, notificationService,
                    userSettingRepository, groupInviteRepository, memberCountCache, userSettingsCache,
                    eventServiceProvider);
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.OWNER));
            when(chatMemberRepository.findByChat(chat))
                    .thenReturn(List.of(member(chat, creator, MemberRole.OWNER)));

            List<GroupMemberResponse> out = noPresence.getMembers(CHAT_UUID_STR, creator);

            assertThat(out.get(0).getPresence()).isEqualTo("offline");
        }

        @Test
        @DisplayName("member with null joinedAt and a mutedUntil → joinedAt serialized null, mutedUntil serialized (both ternary branches)")
        void nullJoinedAtAndMutedMember() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.OWNER));

            ChatMember m = ChatMember.builder().chat(chat).user(creator).build();
            m.setJoinedAt(null); // @Builder.Default sets now(); force null to hit the null-joinedAt branch
            m.setRole(MemberRole.MEMBER);
            Instant muted = Instant.now();
            m.setMutedUntil(muted);
            when(chatMemberRepository.findByChat(chat)).thenReturn(List.of(m));
            when(presenceService.getStatus(creator)).thenReturn(PresenceStatus.ONLINE);

            List<GroupMemberResponse> out = service.getMembers(CHAT_UUID_STR, creator);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getJoinedAt()).isNull();
            assertThat(out.get(0).getMutedUntil()).isEqualTo(muted.toString());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("addMembers")
    class AddMembers {

        private final User target = user(2L, TARGET_UUID, "Bob", "bob");

        /**
         * Stubs the mocked {@link ChatService} so addMembers can return its chat DTO.
         */
        private void stubReturnDto() {
            lenient().when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
        }

        @Test
        @DisplayName("adds a friend directly → member saved, system message, broadcast, notification, evict")
        void addsFriend() {
            Chat chat = groupChat();
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(3L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(friendRepository.findByUserAndFriend(creator, target))
                    .thenReturn(Optional.of(Friend.builder().user(creator).friend(target).build()));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.EVERYONE);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.empty());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            ArgumentCaptor<ChatMember> cap = ArgumentCaptor.forClass(ChatMember.class);
            verify(chatMemberRepository).save(cap.capture());
            assertThat(cap.getValue().getUser()).isEqualTo(target);
            assertThat(cap.getValue().getRole()).isEqualTo(MemberRole.MEMBER);
            verify(messageService).sendSystemMessage(eq(CHAT_UUID_STR), eq(creator), anyString(), isNull());
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), any(Object.class));
            verify(notificationService).createNotification(eq(target), eq("Added to a group"),
                    anyString(), eq("GROUP_ADDED"), eq(CHAT_UUID_STR), eq(creator), any());
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("allowNonFriends group → a non-friend with EVERYONE privacy is added directly")
        void addsNonFriendWhenAllowed() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.EVERYONE);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.empty());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            verify(friendRepository, never()).findByUserAndFriend(any(), any());
        }

        @Test
        @DisplayName("former member is re-activated fresh (leftAt cleared, role reset to MEMBER)")
        void reactivatesFormerMember() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.EVERYONE);
            ChatMember former = member(chat, target, MemberRole.ADMIN);
            former.setLeftAt(Instant.now());
            former.setBanned(true);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(former));

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository).save(former);
            assertThat(former.getLeftAt()).isNull();
            assertThat(former.isBanned()).isFalse();
            assertThat(former.getRole()).isEqualTo(MemberRole.MEMBER);
        }

        @Test
        @DisplayName("already-active member → skipped (no save, no system message)")
        void skipsActiveMember() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(1L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.EVERYONE);
            when(chatMemberRepository.findByChatAndUser(chat, target))
                    .thenReturn(Optional.of(member(chat, target, MemberRole.MEMBER)));

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository, never()).save(any());
            verify(messageService, never()).sendSystemMessage(anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("target restricts direct-adds (NOBODY) → a group invite is sent, no direct add")
        void sendsInviteWhenNotAllowed() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.NOBODY);
            when(groupInviteRepository.existsByChatAndInviteeAndStatus(chat, target, "PENDING"))
                    .thenReturn(false);
            when(groupInviteRepository.findByChatAndInvitee(chat, target)).thenReturn(Optional.empty());
            when(chatService.createChat(any(), eq(creator)))
                    .thenReturn(ChatResponse.builder().id("dm-1").build());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            ArgumentCaptor<GroupInvite> cap = ArgumentCaptor.forClass(GroupInvite.class);
            verify(groupInviteRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
            assertThat(cap.getValue().getInviter()).isEqualTo(creator);
            verify(messageService).sendMessage(eq("dm-1"), any(), eq(creator));
            verify(notificationService).createNotification(eq(target), eq("Group invitation"),
                    anyString(), eq("GROUP_INVITE"), eq(CHAT_UUID_STR), eq(creator), any());
            // no direct membership was created
            verify(chatMemberRepository, never()).save(any());
            verify(messageService, never()).sendSystemMessage(anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("invite is idempotent → a still-PENDING invite is not re-created")
        void inviteIdempotent() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.NOBODY);
            when(groupInviteRepository.existsByChatAndInviteeAndStatus(chat, target, "PENDING"))
                    .thenReturn(true);

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(groupInviteRepository, never()).save(any());
            verify(notificationService, never()).createNotification(any(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("notification failure is swallowed → the add still completes")
        void notificationFailureSwallowed() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.EVERYONE);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.empty());
            doThrow(new RuntimeException("push down")).when(notificationService).createNotification(
                    any(), anyString(), anyString(), anyString(), anyString(), any(), any());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("adder below whoCanAddMembers → ForbiddenException TM_291")
        void insufficientRole() {
            Chat chat = groupChat();
            chat.getSettings().setWhoCanAddMembers(MemberRole.ADMIN);
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));

            assertThatThrownBy(() -> service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_291"));
        }

        @Test
        @DisplayName("member limit reached → BadRequestException TM_297")
        void limitReached() {
            Chat chat = groupChat();
            chat.setMemberLimit(2);
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(2L);

            assertThatThrownBy(() -> service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_297"));
        }

        @Test
        @DisplayName("friends-only group + non-friend target → ForbiddenException TM_306")
        void nonFriendRejected() {
            Chat chat = groupChat(); // allowNonFriends=false
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(friendRepository.findByUserAndFriend(creator, target)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_306"));
        }

        @Test
        @DisplayName("null member list → no-op add, still evicts cache and returns the DTO")
        void nullMemberList() {
            Chat chat = groupChat();
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);

            service.addMembers(CHAT_UUID_STR, null, creator);

            verify(chatMemberRepository, never()).save(any());
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("malformed member uuid → skipped (tryUuid null, line 206), no lookup, still evicts")
        void skipsMalformedMemberUuid() {
            Chat chat = groupChat();
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);

            service.addMembers(CHAT_UUID_STR, List.of("not-a-uuid"), creator);

            verify(userRepository, never()).findByUuid(any());
            verify(chatMemberRepository, never()).save(any());
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("unknown user (findByUuid empty) → skipped (line 208), no save")
        void skipsUnknownUser() {
            Chat chat = groupChat();
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository, never()).save(any());
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("adder adds themselves → added with no self-notification (line 218 self short-circuit, line 241 false)")
        void selfAddNoNotification() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(CREATOR_UUID)).thenReturn(Optional.of(creator));
            when(chatMemberRepository.findByChatAndUser(chat, creator)).thenReturn(Optional.empty());

            service.addMembers(CHAT_UUID_STR, List.of(CREATOR_UUID.toString()), creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            // self-add: neither a privacy check nor a notification is performed
            verify(userSettingsCache, never()).getGroupAddPrivacy(any());
            verify(notificationService, never()).createNotification(any(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("existing DELETED member → not skipped, re-activated fresh (line 225 isDeleted branch)")
        void reactivatesDeletedMember() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.EVERYONE);
            ChatMember deleted = member(chat, target, MemberRole.MEMBER);
            deleted.setDeleted(true); // deleted but leftAt == null
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(deleted));

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository).save(deleted);
            assertThat(deleted.isDeleted()).isFalse();
            assertThat(deleted.getRole()).isEqualTo(MemberRole.MEMBER);
        }

        @Test
        @DisplayName("target's group-add privacy is null → treated as EVERYONE, direct add (allowsDirectAdd line 511 null)")
        void directAddWhenPrivacyNull() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(null);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.empty());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            verify(groupInviteRepository, never()).save(any());
        }

        @Test
        @DisplayName("target privacy FRIENDS_ONLY + they ARE friends → direct add (allowsDirectAdd line 514/515 true)")
        void friendsOnlyPrivacyAddsFriend() {
            Chat chat = groupChat(); // allowNonFriends=false → the 211 friend gate also applies
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(friendRepository.findByUserAndFriend(creator, target))
                    .thenReturn(Optional.of(Friend.builder().user(creator).friend(target).build()));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.FRIENDS_ONLY);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.empty());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            verify(groupInviteRepository, never()).save(any());
        }

        @Test
        @DisplayName("friend link exists but is soft-deleted → treated as non-friend → TM_306 (areFriends map isDeleted branch)")
        void deletedFriendLinkTreatedAsNonFriend() {
            Chat chat = groupChat(); // allowNonFriends=false
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            Friend deletedLink = Friend.builder().user(creator).friend(target).build();
            deletedLink.setDeleted(true);
            when(friendRepository.findByUserAndFriend(creator, target)).thenReturn(Optional.of(deletedLink));

            assertThatThrownBy(() -> service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_306"));
        }

        @Test
        @DisplayName("invite path: chat-message delivery fails → swallowed, invite + notification still delivered (sendGroupInvite inner catch)")
        void inviteInnerMessageFailureSwallowed() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.NOBODY);
            when(groupInviteRepository.existsByChatAndInviteeAndStatus(chat, target, "PENDING")).thenReturn(false);
            when(groupInviteRepository.findByChatAndInvitee(chat, target)).thenReturn(Optional.empty());
            when(chatService.createChat(any(), eq(creator))).thenThrow(new RuntimeException("friends-only"));

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(groupInviteRepository).save(any(GroupInvite.class));
            verify(messageService, never()).sendMessage(anyString(), any(), any());
            verify(notificationService).createNotification(eq(target), eq("Group invitation"),
                    anyString(), eq("GROUP_INVITE"), eq(CHAT_UUID_STR), eq(creator), any());
        }

        @Test
        @DisplayName("invite path: outer failure (invite lookup throws) is swallowed → add loop still completes (sendGroupInvite outer catch)")
        void inviteOuterFailureSwallowed() {
            Chat chat = groupChat();
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            stubReturnDto();
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.NOBODY);
            when(groupInviteRepository.existsByChatAndInviteeAndStatus(chat, target, "PENDING"))
                    .thenThrow(new RuntimeException("db down"));

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), creator);

            verify(groupInviteRepository, never()).save(any());
            verify(notificationService, never()).createNotification(any(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("invite payload: null group name/inviter name + non-null avatar → ternary defaults applied (sendGroupInvite lines 545-547)")
        void inviteNullNamesUseDefaults() {
            User namelessInviter = user(1L, CREATOR_UUID, null, "owner");
            Chat chat = groupChat();
            chat.setName(null);
            chat.setImageUrl("avatar.png");
            chat.setAllowNonFriends(true);
            stubLoad(chat);
            lenient().when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
            when(authz.requireMember(any(), any())).thenReturn(member(chat, namelessInviter, MemberRole.MEMBER));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(userSettingsCache.getGroupAddPrivacy(target)).thenReturn(GroupAddPrivacy.NOBODY);
            when(groupInviteRepository.existsByChatAndInviteeAndStatus(chat, target, "PENDING")).thenReturn(false);
            when(groupInviteRepository.findByChatAndInvitee(chat, target)).thenReturn(Optional.empty());
            when(chatService.createChat(any(), eq(namelessInviter)))
                    .thenReturn(ChatResponse.builder().id("dm-1").build());

            service.addMembers(CHAT_UUID_STR, List.of(TARGET_UUID_STR), namelessInviter);

            // The invite is delivered end-to-end despite the null name/avatar fields.
            verify(messageService).sendMessage(eq("dm-1"), any(), eq(namelessInviter));
            verify(groupInviteRepository).save(any(GroupInvite.class));
            verify(notificationService).createNotification(eq(target), eq("Group invitation"),
                    anyString(), eq("GROUP_INVITE"), eq(CHAT_UUID_STR), eq(namelessInviter), any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("removeMember")
    class RemoveMember {

        private final User target = user(2L, TARGET_UUID, "Bob", "bob");

        @Test
        @DisplayName("admin removes a member → leftAt set, saved, system message, broadcast, evict")
        void removesMember() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            ChatMember targetMember = member(chat, target, MemberRole.MEMBER);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(targetMember));

            service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator);

            assertThat(targetMember.getLeftAt()).isNotNull();
            verify(chatMemberRepository).save(targetMember);
            verify(messageService).sendSystemMessage(eq(CHAT_UUID_STR), eq(creator), anyString(), isNull());
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), any(Object.class));
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("owner may remove an admin")
        void ownerRemovesAdmin() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            ChatMember targetMember = member(chat, target, MemberRole.ADMIN);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(targetMember));

            service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator);

            assertThat(targetMember.getLeftAt()).isNotNull();
            verify(chatMemberRepository).save(targetMember);
        }

        @Test
        @DisplayName("cannot remove the owner → ForbiddenException TM_303")
        void cannotRemoveOwner() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(chatMemberRepository.findByChatAndUser(chat, target))
                    .thenReturn(Optional.of(member(chat, target, MemberRole.OWNER)));

            assertThatThrownBy(() -> service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_303"));
            verify(chatMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("an admin cannot remove another admin → ForbiddenException TM_304")
        void adminCannotRemoveAdmin() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(chatMemberRepository.findByChatAndUser(chat, target))
                    .thenReturn(Optional.of(member(chat, target, MemberRole.ADMIN)));

            assertThatThrownBy(() -> service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_304"));
        }

        @Test
        @DisplayName("target user does not exist → NotFoundException TM_064")
        void userNotFound() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }

        @Test
        @DisplayName("target is not a member → NotFoundException TM_141")
        void notAMember() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("target row exists but is soft-deleted → filtered out → NotFoundException TM_141 (lambda !isDeleted)")
        void deletedMemberIsNotFound() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            ChatMember deleted = member(chat, target, MemberRole.MEMBER);
            deleted.setDeleted(true);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(deleted));

            assertThatThrownBy(() -> service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("system-message emission fails → swallowed, the removal still commits (systemMessage catch)")
        void systemMessageFailureSwallowed() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.ADMIN))
                    .thenReturn(member(chat, creator, MemberRole.ADMIN));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            ChatMember targetMember = member(chat, target, MemberRole.MEMBER);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(targetMember));
            doThrow(new RuntimeException("bus down")).when(messageService)
                    .sendSystemMessage(anyString(), any(), anyString(), any());

            service.removeMember(CHAT_UUID_STR, TARGET_UUID_STR, creator);

            assertThat(targetMember.getLeftAt()).isNotNull();
            verify(chatMemberRepository).save(targetMember);
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("setRole")
    class SetRole {

        private final User target = user(2L, TARGET_UUID, "Bob", "bob");

        @Test
        @DisplayName("owner promotes a member to ADMIN → role saved, system message, role_changed broadcast")
        void promotesMember() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            ChatMember targetMember = member(chat, target, MemberRole.MEMBER);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(targetMember));

            service.setRole(CHAT_UUID_STR, TARGET_UUID_STR, MemberRole.ADMIN, creator);

            assertThat(targetMember.getRole()).isEqualTo(MemberRole.ADMIN);
            verify(chatMemberRepository).save(targetMember);
            verify(messageService).sendSystemMessage(eq(CHAT_UUID_STR), eq(creator), anyString(), isNull());

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), cap.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapper = (Map<String, Object>) cap.getValue();
            assertThat(wrapper.get("event")).isEqualTo("role_changed");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) wrapper.get("payload");
            assertThat(payload.get("role")).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("assigning OWNER via setRole → BadRequestException TM_301 (use transfer-ownership)")
        void cannotAssignOwner() {
            assertThatThrownBy(() -> service.setRole(CHAT_UUID_STR, TARGET_UUID_STR, MemberRole.OWNER, creator))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_301"));
            verify(chatRepository, never()).findByUuidWithMembers(any());
        }

        @Test
        @DisplayName("cannot change the owner's role → ForbiddenException TM_305")
        void cannotChangeOwnerRole() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(chatMemberRepository.findByChatAndUser(chat, target))
                    .thenReturn(Optional.of(member(chat, target, MemberRole.OWNER)));

            assertThatThrownBy(() -> service.setRole(CHAT_UUID_STR, TARGET_UUID_STR, MemberRole.ADMIN, creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_305"));
        }

        @Test
        @DisplayName("target user not found → NotFoundException TM_064")
        void userNotFound() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setRole(CHAT_UUID_STR, TARGET_UUID_STR, MemberRole.ADMIN, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }

        @Test
        @DisplayName("target not a member → NotFoundException TM_141")
        void notAMember() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setRole(CHAT_UUID_STR, TARGET_UUID_STR, MemberRole.ADMIN, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("target row exists but is soft-deleted → filtered out → NotFoundException TM_141 (lambda !isDeleted)")
        void deletedMemberIsNotFound() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(target));
            ChatMember deleted = member(chat, target, MemberRole.MEMBER);
            deleted.setDeleted(true);
            when(chatMemberRepository.findByChatAndUser(chat, target)).thenReturn(Optional.of(deleted));

            assertThatThrownBy(() -> service.setRole(CHAT_UUID_STR, TARGET_UUID_STR, MemberRole.ADMIN, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("leaveGroup")
    class LeaveGroup {

        @Test
        @DisplayName("member leaves → leftAt set, saved, member_left system message + broadcast, evict")
        void memberLeaves() {
            Chat chat = groupChat();
            stubLoad(chat);
            ChatMember me = member(chat, creator, MemberRole.MEMBER);
            when(authz.requireMember(any(), any())).thenReturn(me);

            service.leaveGroup(CHAT_UUID_STR, creator);

            assertThat(me.getLeftAt()).isNotNull();
            verify(chatMemberRepository).save(me);
            verify(messageService).sendSystemMessage(eq(CHAT_UUID_STR), eq(creator), anyString(), isNull());
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), any(Object.class));
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("owner cannot leave without transferring/deleting → BadRequestException TM_298")
        void ownerCannotLeave() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireMember(any(), any())).thenReturn(member(chat, creator, MemberRole.OWNER));

            assertThatThrownBy(() -> service.leaveGroup(CHAT_UUID_STR, creator))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_298"));
            verify(chatMemberRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("transferOwnership")
    class TransferOwnership {

        private final User newOwner = user(2L, TARGET_UUID, "Bob", "bob");

        @Test
        @DisplayName("owner transfers → old owner demoted to ADMIN, new owner promoted, ownerId + saves + broadcast")
        void transfers() {
            Chat chat = groupChat();
            stubLoad(chat);
            ChatMember me = member(chat, creator, MemberRole.OWNER);
            when(authz.requireRole(chat, creator, MemberRole.OWNER)).thenReturn(me);
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(newOwner));
            ChatMember newOwnerMember = member(chat, newOwner, MemberRole.MEMBER);
            when(chatMemberRepository.findByChatAndUser(chat, newOwner))
                    .thenReturn(Optional.of(newOwnerMember));

            service.transferOwnership(CHAT_UUID_STR, TARGET_UUID_STR, creator);

            assertThat(me.getRole()).isEqualTo(MemberRole.ADMIN);
            assertThat(newOwnerMember.getRole()).isEqualTo(MemberRole.OWNER);
            assertThat(chat.getOwnerId()).isEqualTo(2L);
            verify(chatMemberRepository).save(me);
            verify(chatMemberRepository).save(newOwnerMember);
            verify(chatRepository).save(chat);
            verify(messageService).sendSystemMessage(eq(CHAT_UUID_STR), eq(creator), anyString(), isNull());
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), any(Object.class));
        }

        @Test
        @DisplayName("new owner user not found → NotFoundException TM_064")
        void userNotFound() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transferOwnership(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }

        @Test
        @DisplayName("new owner is not a member → NotFoundException TM_141")
        void notAMember() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(newOwner));
            when(chatMemberRepository.findByChatAndUser(chat, newOwner)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transferOwnership(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("new-owner row exists but is soft-deleted → filtered out → NotFoundException TM_141 (lambda !isDeleted)")
        void deletedMemberIsNotFound() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(authz.requireRole(chat, creator, MemberRole.OWNER))
                    .thenReturn(member(chat, creator, MemberRole.OWNER));
            when(userRepository.findByUuid(TARGET_UUID)).thenReturn(Optional.of(newOwner));
            ChatMember deleted = member(chat, newOwner, MemberRole.MEMBER);
            deleted.setDeleted(true);
            when(chatMemberRepository.findByChatAndUser(chat, newOwner)).thenReturn(Optional.of(deleted));

            assertThatThrownBy(() -> service.transferOwnership(CHAT_UUID_STR, TARGET_UUID_STR, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("discover")
    class Discover {

        /**
         * A discoverable PUBLIC / OPEN {@link ChatType#ROOM} used as a discovery search hit.
         */
        private Chat publicRoom() {
            Chat c = Chat.builder()
                    .name("City Lounge")
                    .chatType(ChatType.ROOM)
                    .visibility(ChatVisibility.PUBLIC)
                    .joinPolicy(JoinPolicy.OPEN)
                    .memberLimit(256)
                    .settings(ChatSettings.builder().build())
                    .build();
            c.setId(20L);
            c.setUuid(CHAT_UUID);
            return c;
        }

        @Test
        @DisplayName("type=channel → queries only CHANNEL; null query/tag → null filters")
        void channelType() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(eq(List.of(ChatType.CHANNEL)), isNull(), isNull(), any()))
                    .thenReturn(List.of());

            List<ChatResponse> out = service.discover("channel", null, null, creator);

            assertThat(out).isEmpty();
            verify(chatRepository).findPublicForDiscovery(
                    eq(List.of(ChatType.CHANNEL)), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("type=room → queries only ROOM")
        void roomType() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(eq(List.of(ChatType.ROOM)), any(), any(), any()))
                    .thenReturn(List.of());

            service.discover("room", null, null, creator);

            verify(chatRepository).findPublicForDiscovery(eq(List.of(ChatType.ROOM)), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("no/unknown type → queries both CHANNEL and ROOM; query lowercased to a LIKE pattern; valid tag parsed")
        void bothTypesWithFilters() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(
                    eq(List.of(ChatType.CHANNEL, ChatType.ROOM)), eq("%lounge%"), eq(Interest.MUSIC), any()))
                    .thenReturn(List.of());

            service.discover(null, "Lounge", "music", creator);

            verify(chatRepository).findPublicForDiscovery(
                    eq(List.of(ChatType.CHANNEL, ChatType.ROOM)), eq("%lounge%"), eq(Interest.MUSIC), any());
        }

        @Test
        @DisplayName("unknown tag string → no tag filter (null)")
        void unknownTagIgnored() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(any(), any(), isNull(), any())).thenReturn(List.of());

            service.discover(null, null, "NOPE", creator);

            verify(chatRepository).findPublicForDiscovery(any(), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("maps a public chat to a membership-free card — member's active/role filled in")
        void mapsMemberCard() {
            Chat room = publicRoom();
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(any(), any(), any(), any())).thenReturn(List.of(room));
            when(chatMemberRepository.findByChatAndUser(room, creator))
                    .thenReturn(Optional.of(member(room, creator, MemberRole.ADMIN)));
            when(memberCountCache.get(room)).thenReturn(42);

            List<ChatResponse> out = service.discover(null, null, null, creator);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getName()).isEqualTo("City Lounge");
            assertThat(out.get(0).getGroup().getMemberCount()).isEqualTo(42);
            assertThat(out.get(0).getGroup().isActive()).isTrue();
            assertThat(out.get(0).getGroup().getMyRole()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("non-member card → active=false, myRole=null; count-cache error → memberCount 0")
        void nonMemberCardAndCountFailure() {
            Chat room = publicRoom();
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(any(), any(), any(), any())).thenReturn(List.of(room));
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.empty());
            when(memberCountCache.get(room)).thenThrow(new RuntimeException("redis down"));

            List<ChatResponse> out = service.discover(null, null, null, creator);

            assertThat(out.get(0).getGroup().isActive()).isFalse();
            assertThat(out.get(0).getGroup().getMyRole()).isNull();
            assertThat(out.get(0).getGroup().getMemberCount()).isZero();
        }

        @Test
        @DisplayName("blank query + blank tag → both filters null (line 362/365 blank branches)")
        void blankQueryAndTagIgnored() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(any(), isNull(), isNull(), any())).thenReturn(List.of());

            service.discover(null, "   ", "   ", creator);

            verify(chatRepository).findPublicForDiscovery(any(), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("membership is soft-deleted + chat has null tags → card active=false, tags empty (line 391 isDeleted + 415 null-tags)")
        void deletedMembershipWithNullTags() {
            Chat room = publicRoom();
            room.setTags(null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(any(), any(), any(), any())).thenReturn(List.of(room));
            ChatMember deleted = member(room, creator, MemberRole.MEMBER);
            deleted.setDeleted(true);
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.of(deleted));
            when(memberCountCache.get(room)).thenReturn(5);

            List<ChatResponse> out = service.discover(null, null, null, creator);

            assertThat(out.get(0).getGroup().isActive()).isFalse();
            assertThat(out.get(0).getGroup().getMyRole()).isNull();
            assertThat(out.get(0).getGroup().getTags()).isEmpty();
        }

        @Test
        @DisplayName("membership is a former member (leftAt set) → card active=false (line 391 leftAt branch)")
        void formerMembershipNotActive() {
            Chat room = publicRoom();
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatRepository.findPublicForDiscovery(any(), any(), any(), any())).thenReturn(List.of(room));
            ChatMember former = member(room, creator, MemberRole.MEMBER);
            former.setLeftAt(Instant.now());
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.of(former));
            when(memberCountCache.get(room)).thenReturn(3);

            List<ChatResponse> out = service.discover(null, null, null, creator);

            assertThat(out.get(0).getGroup().isActive()).isFalse();
            assertThat(out.get(0).getGroup().getMyRole()).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("joinChat")
    class JoinChat {

        /**
         * The base group re-typed to a PUBLIC / OPEN room so it is eligible for {@code joinChat}.
         */
        private Chat openRoom() {
            Chat c = groupChat();
            c.setChatType(ChatType.ROOM);
            c.setVisibility(ChatVisibility.PUBLIC);
            c.setJoinPolicy(JoinPolicy.OPEN);
            return c;
        }

        @Test
        @DisplayName("joins an open public room → member added, broadcast, evict, attendance hook fired")
        void joinsOpenRoom() {
            Chat room = openRoom();
            stubLoad(room);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.empty());
            when(chatMemberRepository.countActiveMembers(room)).thenReturn(5L);
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
            EventService eventService = mock(EventService.class);
            when(eventServiceProvider.getObject()).thenReturn(eventService);

            service.joinChat(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), any(Object.class));
            verify(memberCountCache).evict(CHAT_UUID_STR);
            verify(eventService).markAttendedByRoom(CHAT_UUID_STR, creator);
        }

        @Test
        @DisplayName("already an active member → returns early, no save, no broadcast")
        void alreadyMember() {
            Chat room = openRoom();
            stubLoad(room);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatMemberRepository.findByChatAndUser(room, creator))
                    .thenReturn(Optional.of(member(room, creator, MemberRole.MEMBER)));
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            service.joinChat(CHAT_UUID_STR, creator);

            verify(chatMemberRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("former member re-activated on re-join")
        void reactivatesFormer() {
            Chat room = openRoom();
            stubLoad(room);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            ChatMember former = member(room, creator, MemberRole.MEMBER);
            former.setLeftAt(Instant.now());
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.of(former));
            when(chatMemberRepository.countActiveMembers(room)).thenReturn(1L);
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
            when(eventServiceProvider.getObject()).thenReturn(mock(EventService.class));

            service.joinChat(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(former);
            assertThat(former.getLeftAt()).isNull();
        }

        @Test
        @DisplayName("attendance hook failure is swallowed → join still succeeds")
        void attendanceHookSwallowed() {
            Chat room = openRoom();
            stubLoad(room);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.empty());
            when(chatMemberRepository.countActiveMembers(room)).thenReturn(0L);
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
            when(eventServiceProvider.getObject()).thenThrow(new RuntimeException("no events"));

            ChatResponse out = service.joinChat(CHAT_UUID_STR, creator);

            assertThat(out.getId()).isEqualTo(CHAT_UUID_STR);
            verify(chatMemberRepository).save(any(ChatMember.class));
        }

        @Test
        @DisplayName("chat not open to join → ForbiddenException TM_293")
        void notOpen() {
            Chat chat = groupChat(); // PRIVATE / INVITE_ONLY
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));

            assertThatThrownBy(() -> service.joinChat(CHAT_UUID_STR, creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_293"));
        }

        @Test
        @DisplayName("room is full → BadRequestException TM_297")
        void roomFull() {
            Chat room = openRoom();
            room.setMemberLimit(2);
            stubLoad(room);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.empty());
            when(chatMemberRepository.countActiveMembers(room)).thenReturn(2L);

            assertThatThrownBy(() -> service.joinChat(CHAT_UUID_STR, creator))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_297"));
        }

        @Test
        @DisplayName("PUBLIC but not OPEN join-policy → ForbiddenException TM_293 (line 429 second sub-condition)")
        void publicButNotOpenRejected() {
            Chat chat = groupChat();
            chat.setVisibility(ChatVisibility.PUBLIC); // joinPolicy stays INVITE_ONLY
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));

            assertThatThrownBy(() -> service.joinChat(CHAT_UUID_STR, creator))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_293"));
        }

        @Test
        @DisplayName("existing DELETED membership → not 'already a member', re-activated on join (line 434 isDeleted branch)")
        void deletedMemberRejoins() {
            Chat room = openRoom();
            stubLoad(room);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            ChatMember deleted = member(room, creator, MemberRole.MEMBER);
            deleted.setDeleted(true); // leftAt null, but deleted
            when(chatMemberRepository.findByChatAndUser(room, creator)).thenReturn(Optional.of(deleted));
            when(chatMemberRepository.countActiveMembers(room)).thenReturn(1L);
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());
            when(eventServiceProvider.getObject()).thenReturn(mock(EventService.class));

            service.joinChat(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(deleted);
            assertThat(deleted.isDeleted()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("reportChat")
    class ReportChat {

        @Test
        @DisplayName("persists an audit log with reason + details and the actor")
        void reports() {
            Chat chat = groupChat();
            stubLoad(chat);

            service.reportChat(CHAT_UUID_STR, "spam", "keeps happening", creator);

            ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(cap.capture());
            AuditLog log = cap.getValue();
            assertThat(log.getEventName()).isEqualTo("chat.report");
            assertThat(log.getEntityName()).isEqualTo("Chat");
            assertThat(log.getEntityId()).isEqualTo(10L);
            assertThat(log.getActor()).isEqualTo(creator);
            assertThat(log.getDetails()).isEqualTo("reason=spam; keeps happening");
        }

        @Test
        @DisplayName("null reason → 'other'; blank details → omitted")
        void reportsDefaults() {
            Chat chat = groupChat();
            stubLoad(chat);

            service.reportChat(CHAT_UUID_STR, null, "   ", creator);

            ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(cap.capture());
            assertThat(cap.getValue().getDetails()).isEqualTo("reason=other");
        }

        @Test
        @DisplayName("group not found → NotFoundException TM_121")
        void notFound() {
            when(chatRepository.findByUuidWithMembers(CHAT_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reportChat(CHAT_UUID_STR, "spam", null, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("null details → omitted entirely (line 474 details==null branch)")
        void reportNullDetails() {
            Chat chat = groupChat();
            stubLoad(chat);

            service.reportChat(CHAT_UUID_STR, "spam", null, creator);

            ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(cap.capture());
            assertThat(cap.getValue().getDetails()).isEqualTo("reason=spam");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("acceptGroupInvite")
    class AcceptGroupInvite {

        private final User inviter = user(9L, UUID.fromString("55555555-5555-5555-5555-555555555555"),
                "Inviter", "inviter");

        /**
         * A PENDING invite from {@code inviter} to {@code creator} (the accepter) for the given chat.
         */
        private GroupInvite pendingInvite(Chat chat) {
            return GroupInvite.builder().chat(chat).invitee(creator).inviter(inviter).status("PENDING").build();
        }

        @Test
        @DisplayName("accepts a pending invite → member added, invite ACCEPTED, system message, broadcast, inviter notified, evict")
        void acceptsInvite() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            GroupInvite invite = pendingInvite(chat);
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(invite));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(3L);
            when(chatMemberRepository.findByChatAndUser(chat, creator)).thenReturn(Optional.empty());
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            service.acceptGroupInvite(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(any(ChatMember.class)); // addMemberInternal
            assertThat(invite.getStatus()).isEqualTo("ACCEPTED");
            verify(groupInviteRepository).save(invite);
            // The system message is authored by the original inviter (actor), not the accepter.
            verify(messageService).sendSystemMessage(eq(CHAT_UUID_STR), eq(inviter), anyString(), isNull());
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + CHAT_UUID_STR + "/messages"), any(Object.class));
            verify(notificationService).createNotification(eq(inviter), eq("Invite accepted"),
                    anyString(), eq("GROUP_ADDED"), eq(CHAT_UUID_STR), eq(creator), any());
            verify(memberCountCache).evict(CHAT_UUID_STR);
        }

        @Test
        @DisplayName("existing former member is re-activated on accept")
        void reactivatesFormer() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(pendingInvite(chat)));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            ChatMember former = member(chat, creator, MemberRole.MEMBER);
            former.setLeftAt(Instant.now());
            when(chatMemberRepository.findByChatAndUser(chat, creator)).thenReturn(Optional.of(former));
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            service.acceptGroupInvite(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(former);
            assertThat(former.getLeftAt()).isNull();
        }

        @Test
        @DisplayName("no pending invite → NotFoundException TM_307")
        void noPendingInvite() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptGroupInvite(CHAT_UUID_STR, creator))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_307"));
        }

        @Test
        @DisplayName("group full → BadRequestException TM_297")
        void groupFull() {
            Chat chat = groupChat();
            chat.setMemberLimit(2);
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(pendingInvite(chat)));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(2L);

            assertThatThrownBy(() -> service.acceptGroupInvite(CHAT_UUID_STR, creator))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_297"));
            verify(chatMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("existing DELETED member accepts → re-activated (line 592 isDeleted branch)")
        void reactivatesDeletedMemberOnAccept() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(pendingInvite(chat)));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            ChatMember deleted = member(chat, creator, MemberRole.MEMBER);
            deleted.setDeleted(true); // leftAt null but deleted
            when(chatMemberRepository.findByChatAndUser(chat, creator)).thenReturn(Optional.of(deleted));
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            service.acceptGroupInvite(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(deleted);
            assertThat(deleted.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("already-active member accepts → invite ACCEPTED, membership untouched (line 592 both-false branch)")
        void alreadyActiveMemberAcceptsInvite() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            GroupInvite invite = pendingInvite(chat);
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(invite));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(1L);
            when(chatMemberRepository.findByChatAndUser(chat, creator))
                    .thenReturn(Optional.of(member(chat, creator, MemberRole.MEMBER))); // active
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            service.acceptGroupInvite(CHAT_UUID_STR, creator);

            // no membership row was created or re-activated…
            verify(chatMemberRepository, never()).save(any());
            // …but the invite is still marked accepted and saved
            assertThat(invite.getStatus()).isEqualTo("ACCEPTED");
            verify(groupInviteRepository).save(invite);
        }

        @Test
        @DisplayName("invite with null inviter → no inviter notification; system message authored by accepter (line 608 null-inviter + systemMessage actor==null)")
        void nullInviterNoNotification() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            GroupInvite invite = GroupInvite.builder().chat(chat).invitee(creator).status("PENDING").build(); // inviter null
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(invite));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(chatMemberRepository.findByChatAndUser(chat, creator)).thenReturn(Optional.empty());
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            service.acceptGroupInvite(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            assertThat(invite.getStatus()).isEqualTo("ACCEPTED");
            // actor (inviter) is null → the system message falls back to the accepter as author
            verify(messageService).sendSystemMessage(eq(CHAT_UUID_STR), eq(creator), anyString(), isNull());
            verify(notificationService, never()).createNotification(any(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("inviter is the accepter themselves → no self-notification (line 608 second sub-condition)")
        void selfInviterNoNotification() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            GroupInvite invite = GroupInvite.builder()
                    .chat(chat).invitee(creator).inviter(creator).status("PENDING").build();
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(invite));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(0L);
            when(chatMemberRepository.findByChatAndUser(chat, creator)).thenReturn(Optional.empty());
            when(chatService.getChatByUuid(eq(CHAT_UUID_STR), any()))
                    .thenReturn(ChatResponse.builder().id(CHAT_UUID_STR).build());

            service.acceptGroupInvite(CHAT_UUID_STR, creator);

            verify(chatMemberRepository).save(any(ChatMember.class));
            assertThat(invite.getStatus()).isEqualTo("ACCEPTED");
            verify(notificationService, never()).createNotification(any(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("declineGroupInvite")
    class DeclineGroupInvite {

        @Test
        @DisplayName("pending invite → marked DECLINED and saved")
        void declines() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            GroupInvite invite = GroupInvite.builder().chat(chat).invitee(creator).status("PENDING").build();
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.of(invite));

            service.declineGroupInvite(CHAT_UUID_STR, creator);

            assertThat(invite.getStatus()).isEqualTo("DECLINED");
            verify(groupInviteRepository).save(invite);
        }

        @Test
        @DisplayName("no pending invite → silent no-op (nothing saved)")
        void noInviteNoop() {
            Chat chat = groupChat();
            stubLoad(chat);
            when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
            when(groupInviteRepository.findByChatAndInviteeAndStatus(chat, creator, "PENDING"))
                    .thenReturn(Optional.empty());

            service.declineGroupInvite(CHAT_UUID_STR, creator);

            verify(groupInviteRepository, never()).save(any());
        }
    }
}
