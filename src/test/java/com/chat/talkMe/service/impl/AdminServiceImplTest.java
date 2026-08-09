package com.chat.talkMe.service.impl;

import com.chat.talkMe.crypto.MessageCryptoService;
import com.chat.talkMe.domain.AdminAuditLog;
import com.chat.talkMe.domain.AudioTrack;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.Feedback;
import com.chat.talkMe.domain.MatchReport;
import com.chat.talkMe.domain.MatchSession;
import com.chat.talkMe.domain.MediaAsset;
import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.MessageAttachment;
import com.chat.talkMe.domain.Poll;
import com.chat.talkMe.domain.Post;
import com.chat.talkMe.domain.PostComment;
import com.chat.talkMe.domain.PostLike;
import com.chat.talkMe.domain.PostMedia;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.dto.request.AdminCreateUserRequest;
import com.chat.talkMe.dto.request.AdminUpdateUserRequest;
import com.chat.talkMe.dto.request.AdminUserFilter;
import com.chat.talkMe.dto.response.AdminAnalyticsResponse;
import com.chat.talkMe.dto.response.AdminAttachmentView;
import com.chat.talkMe.dto.response.AdminAuditView;
import com.chat.talkMe.dto.response.AdminChatView;
import com.chat.talkMe.dto.response.AdminConnectorView;
import com.chat.talkMe.dto.response.AdminFeedbackView;
import com.chat.talkMe.dto.response.AdminMediaOwnershipResponse;
import com.chat.talkMe.dto.response.AdminMessageView;
import com.chat.talkMe.dto.response.AdminPostCommentView;
import com.chat.talkMe.dto.response.AdminPostLikeView;
import com.chat.talkMe.dto.response.AdminPostView;
import com.chat.talkMe.dto.response.AdminReportView;
import com.chat.talkMe.dto.response.AdminStatsResponse;
import com.chat.talkMe.dto.response.AdminStorageListResponse;
import com.chat.talkMe.dto.response.AdminStorageObjectView;
import com.chat.talkMe.dto.response.AdminTimeseriesPoint;
import com.chat.talkMe.dto.response.AdminTimeseriesResult;
import com.chat.talkMe.dto.response.AdminUserFullView;
import com.chat.talkMe.dto.response.AdminUserView;
import com.chat.talkMe.dto.response.LabelCount;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.FeedbackStatus;
import com.chat.talkMe.enums.FeedbackType;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.MediaContext;
import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.enums.ModerationStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.MessageMapper;
import com.chat.talkMe.repository.AdminAuditLogRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.FeedbackRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.FriendRequestRepository;
import com.chat.talkMe.repository.MatchReportRepository;
import com.chat.talkMe.repository.MediaAssetRepository;
import com.chat.talkMe.repository.MessageAttachmentRepository;
import com.chat.talkMe.repository.MessageReactionRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.PostCommentRepository;
import com.chat.talkMe.repository.PostLikeRepository;
import com.chat.talkMe.repository.PostRepository;
import com.chat.talkMe.repository.ProfileViewRepository;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.StoryRepository;
import com.chat.talkMe.repository.UserFollowRepository;
import com.chat.talkMe.repository.UserPresenceRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.storage.MediaStorage;
import com.chat.talkMe.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link AdminServiceImpl} — the SuperAdmin read/analytics/
 * moderation surface. Every public method is exercised for its happy path, each valid
 * branch, and every {@code TM_###} error path (malformed/absent UUID → 404, conflicts,
 * invalid roles/actions/statuses, unsafe storage keys). Side effects (entity saves, audit
 * writes, storage deletes) are asserted with captors/verify; the Redis read-through cache
 * is verified for hit / miss-then-populate / fail-open.
 *
 * <p>{@link ObjectMapper} is a real instance (a pure serializer, not a behavioural
 * collaborator) so the cache serialize/deserialize round-trip is genuine. JPA
 * {@code Specification} lambdas are never executed by the mocked repositories, so the
 * criteria-building bodies are out of scope for a unit test (they require a real
 * EntityManager) — only the code around them is asserted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminServiceImpl (unit)")
class AdminServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private ChatRepository chatRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private PresenceService presenceService;
    @Mock private MessageCryptoService messageCryptoService;
    @Mock private MessageMapper messageMapper;
    @Mock private RoleRepository roleRepository;
    @Mock private AdminAuditLogRepository auditRepository;
    @Mock private AdminAuditLogger auditLogger;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private MessageAttachmentRepository attachmentRepository;
    @Mock private PostRepository postRepository;
    @Mock private StoryRepository storyRepository;
    @Mock private ProfileViewRepository profileViewRepository;
    @Mock private MatchReportRepository matchReportRepository;
    @Mock private FeedbackRepository feedbackRepository;
    @Mock private UserFollowRepository userFollowRepository;
    @Mock private FriendRepository friendRepository;
    @Mock private FriendRequestRepository friendRequestRepository;
    @Mock private MessageReactionRepository reactionRepository;
    @Mock private PostLikeRepository postLikeRepository;
    @Mock private PostCommentRepository postCommentRepository;
    @Mock private UserSettingRepository userSettingRepository;
    @Mock private UserPresenceRepository userPresenceRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private MediaStorage mediaStorage;
    @Mock private StorageProperties storageProperties;
    @Mock private MediaAssetRepository mediaAssetRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminServiceImpl service;

    private long idSeq = 1;

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl(
                userRepository, chatRepository, messageRepository, presenceService,
                messageCryptoService, messageMapper, roleRepository, auditRepository,
                auditLogger, passwordEncoder, attachmentRepository, postRepository,
                storyRepository, profileViewRepository, matchReportRepository, feedbackRepository,
                userFollowRepository, friendRepository, friendRequestRepository, reactionRepository,
                postLikeRepository, postCommentRepository, userSettingRepository, userPresenceRepository,
                redisTemplate, objectMapper, mediaStorage, storageProperties, mediaAssetRepository);

        // Shared, harmless defaults. lenient() so methods that don't touch them don't fail.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(null); // cache miss by default
        lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
        lenient().when(presenceService.getAwayUsernames()).thenReturn(Set.of());
        lenient().when(chatRepository.findChatsByUser(any())).thenReturn(List.of());
        lenient().when(messageRepository.countBySenderId(any())).thenReturn(0L);
        // decrypt is identity in tests (crypto correctness is covered elsewhere).
        lenient().when(messageCryptoService.decrypt(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(storageProperties.getMediaRoot()).thenReturn("/media");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private User user(String username) {
        User u = User.builder()
                .username(username).name(username + " Name").email(username + "@x.com")
                .build();
        u.setId(idSeq++);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private Chat chat(ChatType type) {
        Chat c = Chat.builder().chatType(type).name("Chat").build();
        c.setId(idSeq++);
        c.setUuid(UUID.randomUUID());
        return c;
    }

    /** A media_assets ownership row (for the media-analytics / user-media tests). */
    private MediaAsset mediaAsset(
            User owner, MediaContext ctx, String key, String uploadType, long size) {
        MediaAsset a = MediaAsset.builder()
                .owner(owner).context(ctx).storageKey(key).reference("/media/" + key)
                .uploadType(uploadType).contentType("image/jpeg").fileSize(size).originalFileName("orig.jpg")
                .contextId(ctx == MediaContext.CONVERSATION ? "cid" : null)
                .build();
        a.setId(idSeq++);
        a.setUuid(UUID.randomUUID());
        a.setCreatedAt(Instant.now());
        return a;
    }

    /** A chat MessageAttachment (for the chat-media + reconcile tests). */
    private MessageAttachment attachment(
            Chat c, User sender, MessageType type, String mime, String fileName, String key) {
        Message m = Message.builder().chat(c).sender(sender).messageType(type).content("cap").build();
        m.setId(idSeq++);
        m.setUuid(UUID.randomUUID());
        MessageAttachment a = MessageAttachment.builder()
                .message(m).fileName(fileName).fileUrl("/media/" + key).mimeType(mime).fileSize(20L).build();
        a.setId(idSeq++);
        a.setUuid(UUID.randomUUID());
        a.setCreatedAt(Instant.now());
        return a;
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getStats")
    class GetStats {

        private void stubCounts() {
            when(userRepository.count()).thenReturn(100L);
            when(userRepository.countByIsDeletedFalse()).thenReturn(90L);
            when(userRepository.countByIsDeletedTrue()).thenReturn(10L);
            when(userRepository.countByIsVerifiedTrue()).thenReturn(40L);
            when(userRepository.countByIsGuestTrue()).thenReturn(5L);
            when(userRepository.countByCreatedAtAfter(any())).thenReturn(7L);
            when(chatRepository.count()).thenReturn(50L);
            when(messageRepository.count()).thenReturn(1000L);
        }

        @Test
        @DisplayName("cache miss → computes aggregates from the repositories and populates the cache")
        void cacheMissComputesAndPopulates() {
            stubCounts();
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("a", "b"));

            AdminStatsResponse res = service.getStats();

            assertThat(res.getTotalUsers()).isEqualTo(100L);
            assertThat(res.getActiveUsers()).isEqualTo(90L);
            assertThat(res.getDeletedUsers()).isEqualTo(10L);
            assertThat(res.getOnlineNow()).isEqualTo(2L);
            assertThat(res.getTotalChats()).isEqualTo(50L);
            assertThat(res.getTotalMessages()).isEqualTo(1000L);
            // populated the cache under the gen-namespaced key
            verify(valueOps).set(eq("admin:g0:stats"), anyString(), any());
        }

        @Test
        @DisplayName("cache hit → deserializes the cached value and never queries the DB")
        void cacheHitSkipsCompute() throws Exception {
            AdminStatsResponse cachedValue = AdminStatsResponse.builder().totalUsers(777L).build();
            when(valueOps.get("admin:cachegen")).thenReturn(null);
            when(valueOps.get("admin:g0:stats"))
                    .thenReturn(objectMapper.writeValueAsString(cachedValue));

            AdminStatsResponse res = service.getStats();

            assertThat(res.getTotalUsers()).isEqualTo(777L);
            verify(userRepository, never()).count();
        }

        @Test
        @DisplayName("Redis outage → falls back to a live DB computation (fail-open)")
        void redisOutageFailsOpen() {
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
            stubCounts();

            AdminStatsResponse res = service.getStats();

            assertThat(res.getTotalUsers()).isEqualTo(100L);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listUsers")
    class ListUsers {

        @SuppressWarnings("unchecked")
        private void stubFindAll(Page<User> page) {
            when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        }

        @Test
        @DisplayName("maps the page of users into views with presence + pagination metadata")
        void mapsPage() {
            User u = user("alice");
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            stubFindAll(new PageImpl<>(List.of(u)));

            PaginatedResponse<AdminUserView> res = service.listUsers(new AdminUserFilter(), 0, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getUsername()).isEqualTo("alice");
            assertThat(res.getItems().get(0).getPresence()).isEqualTo("online");
            assertThat(res.getPagination().getTotal()).isEqualTo(1L);
        }

        @Test
        @DisplayName("null filter is tolerated (defaults applied, no NPE)")
        void nullFilterDefaults() {
            stubFindAll(new PageImpl<>(List.of()));

            PaginatedResponse<AdminUserView> res = service.listUsers(null, 0, 20);

            assertThat(res.getItems()).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("sort field is whitelisted (username honoured) and size is clamped to 100")
        void whitelistsSortAndClampsSize() {
            AdminUserFilter f = new AdminUserFilter();
            f.setSort("username");
            f.setDir("asc");
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            when(userRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listUsers(f, -3, 500); // negative page → 0, oversize → clamp

            Pageable used = pageableCaptor.getValue();
            assertThat(used.getPageNumber()).isZero();
            assertThat(used.getPageSize()).isEqualTo(100);
            assertThat(used.getSort().getOrderFor("username")).isNotNull();
            assertThat(used.getSort().getOrderFor("username").isAscending()).isTrue();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("unknown sort key falls back to createdAt, descending")
        void unknownSortFallsBack() {
            AdminUserFilter f = new AdminUserFilter();
            f.setSort("dropTable");
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            when(userRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listUsers(f, 0, 20);

            assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUser")
    class GetUser {

        @Test
        @DisplayName("returns a detailed view with chat + message counts")
        void returnsDetailView() {
            User u = user("bob");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(chatRepository.findChatsByUser(u)).thenReturn(List.of(chat(ChatType.PRIVATE)));
            when(messageRepository.countBySenderId(u.getId())).thenReturn(42L);

            AdminUserView v = service.getUser(u.getUuid().toString());

            assertThat(v.getUsername()).isEqualTo("bob");
            assertThat(v.getChatCount()).isEqualTo(1L);
            assertThat(v.getMessageCount()).isEqualTo(42L);
        }

        @Test
        @DisplayName("absent user → NotFoundException TM_064")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUser(id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }

        @Test
        @DisplayName("malformed uuid → NotFoundException TM_064 (not a raw 500)")
        void malformedUuid() {
            assertThatThrownBy(() -> service.getUser("not-a-uuid"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserFull")
    class GetUserFull {

        @Test
        @DisplayName("assembles account + settings + presence when both rows exist")
        void withSettingsAndPresence() {
            User u = user("carol");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            UserSetting s = UserSetting.builder().user(u).theme("DARK").language("fr").build();
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.of(s));
            UserPresence p = UserPresence.builder().user(u).status("ONLINE").build();
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.of(p));

            AdminUserFullView v = service.getUserFull(u.getUuid().toString());

            assertThat(v.getAccount().get("username")).isEqualTo("carol");
            assertThat(v.getSettings().get("theme")).isEqualTo("DARK");
            assertThat(v.getPresence().get("status")).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("missing settings/presence rows fall back to explanatory notes")
        void missingSettingsPresence() {
            User u = user("dave");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.empty());
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.empty());

            AdminUserFullView v = service.getUserFull(u.getUuid().toString());

            assertThat(v.getSettings()).containsKey("_note");
            assertThat(v.getPresence()).containsKey("_note");
        }

        @Test
        @DisplayName("absent user → NotFoundException TM_064")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUserFull(id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserChats")
    class GetUserChats {

        @Test
        @DisplayName("maps the admin chat history (incl. soft-deleted) into views")
        void mapsChats() {
            User u = user("erin");
            Chat c = chat(ChatType.GROUP);
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(chatRepository.findAllChatsByUserForAdmin(u)).thenReturn(List.of(c));
            when(messageRepository.countByChat(c)).thenReturn(3L);
            when(messageRepository.findFirstByChatAndIsDeletedFalseOrderByCreatedAtDesc(c))
                    .thenReturn(Optional.empty());

            List<AdminChatView> views = service.getUserChats(u.getUuid().toString());

            assertThat(views).hasSize(1);
            assertThat(views.get(0).getType()).isEqualTo("GROUP");
            assertThat(views.get(0).getMessageCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("absent user → NotFoundException TM_064")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUserChats(id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listChats")
    class ListChats {

        @Test
        @DisplayName("valid type filter is parsed and an audit row is written")
        void parsesTypeAndAudits() {
            Chat c = chat(ChatType.PRIVATE);
            when(messageRepository.countByChat(c)).thenReturn(0L);
            when(messageRepository.findFirstByChatAndIsDeletedFalseOrderByCreatedAtDesc(c))
                    .thenReturn(Optional.empty());
            when(chatRepository.findForAdmin(eq(ChatType.PRIVATE), any(), anyBoolean(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(c)));

            PaginatedResponse<AdminChatView> res =
                    service.listChats("hello", "private", false, 0, 20, "root");

            assertThat(res.getItems()).hasSize(1);
            verify(auditLogger).write(eq("root"), eq("VIEW_CHATS"), eq("CHAT"), eq("all"), anyString());
        }

        @Test
        @DisplayName("unknown type → no type filter (null passed to the repository)")
        void unknownTypeNoFilter() {
            when(chatRepository.findForAdmin(eq(null), any(), anyBoolean(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listChats(null, "bogus", true, 0, 20, "root");

            verify(chatRepository).findForAdmin(eq(null), eq(null), eq(true), any(Pageable.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChatMessages")
    class GetChatMessages {

        @Test
        @DisplayName("decrypts message content, records an audit trail, and paginates")
        void decryptsAndAudits() {
            Chat c = chat(ChatType.PRIVATE);
            User sender = user("frank");
            Message m = Message.builder().chat(c).sender(sender).content("cipher")
                    .messageType(MessageType.TEXT).build();
            m.setId(9L);
            m.setUuid(UUID.randomUUID());
            when(chatRepository.findByUuidWithMembers(c.getUuid())).thenReturn(Optional.of(c));
            when(messageRepository.findByChat(eq(c), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(messageCryptoService.decrypt(c.getId(), "cipher")).thenReturn("hello world");
            when(messageMapper.resolveMessageStatus(m)).thenReturn("SENT");

            PaginatedResponse<AdminMessageView> res =
                    service.getChatMessages(c.getUuid().toString(), 0, 50, "root");

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getContent()).isEqualTo("hello world");
            assertThat(res.getItems().get(0).getSenderUsername()).isEqualTo("frank");
            verify(auditLogger).write(eq("root"), eq("VIEW_MESSAGES"), eq("CHAT"), anyString(), anyString());
        }

        @Test
        @DisplayName("absent chat → NotFoundException TM_121")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(chatRepository.findByUuidWithMembers(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChatMessages(id.toString(), 0, 50, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("malformed chat uuid → NotFoundException TM_121")
        void malformedUuid() {
            assertThatThrownBy(() -> service.getChatMessages("xx", 0, 50, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("moderation mutations (setBanned / setVerified / setSoftDeleted)")
    class Mutations {

        private User stubbedUser(String name) {
            User u = user(name);
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            return u;
        }

        @Test
        @DisplayName("setBanned(true) sets the flag, saves, and audits BAN_USER")
        void banUser() {
            User u = stubbedUser("mallory");

            AdminUserView v = service.setBanned(u.getUuid().toString(), true, "root");

            assertThat(u.isBanned()).isTrue();
            assertThat(v.isBanned()).isTrue();
            verify(userRepository).save(u);
            verify(auditLogger).write(eq("root"), eq("BAN_USER"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("setBanned(false) audits UNBAN_USER")
        void unbanUser() {
            User u = stubbedUser("mallory");

            service.setBanned(u.getUuid().toString(), false, "root");

            assertThat(u.isBanned()).isFalse();
            verify(auditLogger).write(eq("root"), eq("UNBAN_USER"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("setVerified toggles the flag and audits VERIFY_USER")
        void verifyUser() {
            User u = stubbedUser("nate");

            service.setVerified(u.getUuid().toString(), true, "root");

            assertThat(u.isVerified()).isTrue();
            verify(auditLogger).write(eq("root"), eq("VERIFY_USER"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("setSoftDeleted(true) stamps deletionRequestedAt and audits SOFT_DELETE_USER")
        void softDelete() {
            User u = stubbedUser("olive");

            service.setSoftDeleted(u.getUuid().toString(), true, "root");

            assertThat(u.isDeleted()).isTrue();
            assertThat(u.getDeletionRequestedAt()).isNotNull();
            verify(auditLogger).write(eq("root"), eq("SOFT_DELETE_USER"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("setSoftDeleted(false) clears deletionRequestedAt and audits RESTORE_USER")
        void restore() {
            User u = stubbedUser("olive");
            u.setDeletionRequestedAt(Instant.now());

            service.setSoftDeleted(u.getUuid().toString(), false, "root");

            assertThat(u.isDeleted()).isFalse();
            assertThat(u.getDeletionRequestedAt()).isNull();
            verify(auditLogger).write(eq("root"), eq("RESTORE_USER"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("setBanned on absent user → NotFoundException TM_064, no save")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setBanned(id.toString(), true, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
            verify(userRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("grantRole / revokeRole")
    class Roles {

        @Test
        @DisplayName("grants a new role (found existing Role), saves, and audits GRANT_ROLE")
        void grantExistingRole() {
            User u = user("pat");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            Role r = Role.builder().name("ROLE_MODERATOR").build();
            when(roleRepository.findByName("ROLE_MODERATOR")).thenReturn(Optional.of(r));

            service.grantRole(u.getUuid().toString(), "moderator", "root");

            assertThat(u.getRoles()).extracting(Role::getName).contains("ROLE_MODERATOR");
            verify(userRepository).save(u);
            verify(auditLogger).write(eq("root"), eq("GRANT_ROLE"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("grants a role that doesn't exist yet → the Role is created")
        void grantCreatesMissingRole() {
            User u = user("pat");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.empty());
            when(roleRepository.save(any(Role.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.grantRole(u.getUuid().toString(), "ROLE_USER", "root");

            verify(roleRepository).save(any(Role.class));
            assertThat(u.getRoles()).extracting(Role::getName).contains("ROLE_USER");
        }

        @Test
        @DisplayName("granting a role the user already has is a no-op (no save, no audit)")
        void grantIdempotent() {
            User u = user("pat");
            u.getRoles().add(Role.builder().name("ROLE_MODERATOR").build());
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(roleRepository.findByName("ROLE_MODERATOR"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_MODERATOR").build()));

            service.grantRole(u.getUuid().toString(), "ROLE_MODERATOR", "root");

            verify(userRepository, never()).save(any());
            verify(auditLogger, never()).write(any(), eq("GRANT_ROLE"), any(), any(), any());
        }

        @Test
        @DisplayName("non-assignable role → BadRequestException TM_071 (before any user lookup)")
        void grantInvalidRole() {
            assertThatThrownBy(() -> service.grantRole(UUID.randomUUID().toString(), "HACKER", "root"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_071"));
            verify(userRepository, never()).findByUuid(any());
        }

        @Test
        @DisplayName("revokes a held role, saves, and audits REVOKE_ROLE")
        void revokeHeldRole() {
            User u = user("quinn");
            u.getRoles().add(Role.builder().name("ROLE_MODERATOR").build());
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));

            service.revokeRole(u.getUuid().toString(), "ROLE_MODERATOR", "root");

            assertThat(u.getRoles()).extracting(Role::getName).doesNotContain("ROLE_MODERATOR");
            verify(userRepository).save(u);
            verify(auditLogger).write(eq("root"), eq("REVOKE_ROLE"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("revoking a role the user doesn't have is a no-op (no save, no audit)")
        void revokeNotHeld() {
            User u = user("quinn");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));

            service.revokeRole(u.getUuid().toString(), "ROLE_MODERATOR", "root");

            verify(userRepository, never()).save(any());
            verify(auditLogger, never()).write(any(), eq("REVOKE_ROLE"), any(), any(), any());
        }

        @Test
        @DisplayName("revoke with a non-assignable role → BadRequestException TM_071")
        void revokeInvalidRole() {
            assertThatThrownBy(() -> service.revokeRole(UUID.randomUUID().toString(), "HACKER", "root"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_071"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listAudit")
    class ListAudit {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("maps audit rows and resolves acting-admin uuids in one batch")
        void mapsAndResolvesAdminUuids() {
            AdminAuditLog log = AdminAuditLog.builder()
                    .adminUsername("root").action("BAN_USER").targetType("USER").targetId("u1").build();
            log.setId(1L);
            log.setUuid(UUID.randomUUID());
            when(auditRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(log)));
            User admin = user("root");
            when(userRepository.findByUsernameIn(Set.of("root"))).thenReturn(List.of(admin));

            PaginatedResponse<AdminAuditView> res =
                    service.listAudit(null, null, null, null, null, 0, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getAdminUsername()).isEqualTo("root");
            assertThat(res.getItems().get(0).getAdminId()).isEqualTo(admin.getUuid().toString());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("empty page → no username resolution query, empty items")
        void emptyPage() {
            when(auditRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            PaginatedResponse<AdminAuditView> res =
                    service.listAudit("BAN_USER", "USER", "root", "2020-01-01", "2020-12-31", 0, 20);

            assertThat(res.getItems()).isEmpty();
            verify(userRepository, never()).findByUsernameIn(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("createUser")
    class CreateUser {

        private AdminCreateUserRequest req() {
            AdminCreateUserRequest r = new AdminCreateUserRequest();
            r.setName("New Person");
            r.setEmail("New@X.com");
            r.setUsername("newbie");
            r.setPassword("secret");
            r.setAge(25);
            r.setGender("MALE");
            r.setCountry("India");
            return r;
        }

        @Test
        @DisplayName("creates a pre-verified user, hashes the password, and audits CREATE_USER")
        void createsUser() {
            when(userRepository.existsByEmailIgnoreCase("new@x.com")).thenReturn(false);
            when(userRepository.existsByUsernameIgnoreCase("newbie")).thenReturn(false);
            when(roleRepository.findByName("ROLE_USER"))
                    .thenReturn(Optional.of(Role.builder().name("ROLE_USER").build()));
            when(passwordEncoder.encode("secret")).thenReturn("HASHED");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User x = inv.getArgument(0);
                x.setUuid(UUID.randomUUID());
                return x;
            });

            AdminUserView v = service.createUser(req(), "root");

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            User saved = captor.getValue();
            assertThat(saved.getEmail()).isEqualTo("new@x.com"); // normalized
            assertThat(saved.getUsername()).isEqualTo("newbie");
            assertThat(saved.getPasswordHash()).isEqualTo("HASHED");
            assertThat(saved.isVerified()).isTrue();
            assertThat(saved.isGuest()).isFalse();
            assertThat(v.getUsername()).isEqualTo("newbie");
            verify(auditLogger).write(eq("root"), eq("CREATE_USER"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("duplicate email → ConflictException TM_047, no save")
        void duplicateEmail() {
            when(userRepository.existsByEmailIgnoreCase("new@x.com")).thenReturn(true);

            assertThatThrownBy(() -> service.createUser(req(), "root"))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_047"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("duplicate username → ConflictException TM_048, no save")
        void duplicateUsername() {
            when(userRepository.existsByEmailIgnoreCase("new@x.com")).thenReturn(false);
            when(userRepository.existsByUsernameIgnoreCase("newbie")).thenReturn(true);

            assertThatThrownBy(() -> service.createUser(req(), "root"))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_048"));
            verify(userRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("updateUser")
    class UpdateUser {

        @Test
        @DisplayName("applies only the non-null fields, parses interests, resets password, and audits")
        void appliesPartialUpdate() {
            User u = user("rick");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(passwordEncoder.encode("newpw")).thenReturn("NEWHASH");

            AdminUpdateUserRequest req = new AdminUpdateUserRequest();
            req.setName("Rick Updated");
            req.setAge(30);
            req.setInterests(List.of("SPORTS", "BOGUS_INTEREST"));
            req.setNewPassword("newpw");

            service.updateUser(u.getUuid().toString(), req, "root");

            assertThat(u.getName()).isEqualTo("Rick Updated");
            assertThat(u.getAge()).isEqualTo(30);
            assertThat(u.getInterests()).containsExactly(Interest.SPORTS); // bogus filtered out
            assertThat(u.getPasswordHash()).isEqualTo("NEWHASH");
            verify(userRepository).save(u);
            verify(auditLogger).write(eq("root"), eq("EDIT_USER"), eq("USER"), anyString(), anyString());
        }

        @Test
        @DisplayName("changing email to one already taken → ConflictException TM_047")
        void emailConflict() {
            User u = user("rick");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(userRepository.existsByEmailIgnoreCase("taken@x.com")).thenReturn(true);

            AdminUpdateUserRequest req = new AdminUpdateUserRequest();
            req.setEmail("taken@x.com");

            assertThatThrownBy(() -> service.updateUser(u.getUuid().toString(), req, "root"))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_047"));
        }

        @Test
        @DisplayName("changing username to one already taken → ConflictException TM_048")
        void usernameConflict() {
            User u = user("rick");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(userRepository.existsByUsernameIgnoreCase("taken")).thenReturn(true);

            AdminUpdateUserRequest req = new AdminUpdateUserRequest();
            req.setUsername("taken");

            assertThatThrownBy(() -> service.updateUser(u.getUuid().toString(), req, "root"))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_048"));
        }

        @Test
        @DisplayName("absent user → NotFoundException TM_064")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateUser(id.toString(), new AdminUpdateUserRequest(), "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("deleteMessage / deleteChat")
    class Deletes {

        @Test
        @DisplayName("deleteMessage soft-deletes, saves, and audits DELETE_MESSAGE")
        void deleteMessage() {
            Chat c = chat(ChatType.PRIVATE);
            Message m = Message.builder().chat(c).build();
            m.setUuid(UUID.randomUUID());
            when(messageRepository.findByUuid(m.getUuid())).thenReturn(Optional.of(m));

            service.deleteMessage(m.getUuid().toString(), "root");

            assertThat(m.isDeleted()).isTrue();
            verify(messageRepository).save(m);
            verify(auditLogger).write(eq("root"), eq("DELETE_MESSAGE"), eq("MESSAGE"), anyString(), anyString());
        }

        @Test
        @DisplayName("deleteMessage absent → NotFoundException TM_150")
        void deleteMessageNotFound() {
            UUID id = UUID.randomUUID();
            when(messageRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteMessage(id.toString(), "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_150"));
        }

        @Test
        @DisplayName("deleteChat soft-deletes, saves, and audits DELETE_CHAT")
        void deleteChat() {
            Chat c = chat(ChatType.GROUP);
            when(chatRepository.findByUuid(c.getUuid())).thenReturn(Optional.of(c));

            service.deleteChat(c.getUuid().toString(), "root");

            assertThat(c.isDeleted()).isTrue();
            verify(chatRepository).save(c);
            verify(auditLogger).write(eq("root"), eq("DELETE_CHAT"), eq("CHAT"), anyString(), anyString());
        }

        @Test
        @DisplayName("deleteChat absent → NotFoundException TM_121")
        void deleteChatNotFound() {
            UUID id = UUID.randomUUID();
            when(chatRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteChat(id.toString(), "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getSignupTimeseries")
    class SignupTimeseries {

        @Test
        @DisplayName("returns one zero-filled point per day for the requested window")
        void zeroFilled() {
            when(userRepository.findSignupTimesSince(any())).thenReturn(List.of(Instant.now()));

            List<AdminTimeseriesPoint> pts = service.getSignupTimeseries(7);

            assertThat(pts).hasSize(7);
            assertThat(pts.stream().mapToLong(AdminTimeseriesPoint::getCount).sum())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("days is clamped to the [1, 365] range")
        void clampsDays() {
            when(userRepository.findSignupTimesSince(any())).thenReturn(List.of());

            assertThat(service.getSignupTimeseries(0)).hasSize(1);       // min 1
            assertThat(service.getSignupTimeseries(10_000)).hasSize(365); // max 365
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getTimeseries")
    class Timeseries {

        @Test
        @DisplayName("default metric routes to message timestamps and buckets them")
        void defaultMetricMessages() {
            // A timestamp a minute in the past — firmly inside the window (a point at the exact
            // "now" boundary would map to idx == buckets and fall just outside the last half-open bucket).
            when(messageRepository.findMessageTimesSince(any()))
                    .thenReturn(List.of(Instant.now().minusSeconds(60)));

            AdminTimeseriesResult res = service.getTimeseries(null, "7d", null, null, null);

            assertThat(res.getMetric()).isEqualTo("messages");
            assertThat(res.getTotal()).isEqualTo(1L);
            verify(messageRepository).findMessageTimesSince(any());
        }

        @Test
        @DisplayName("signups metric routes to signup timestamps")
        void signupsMetric() {
            when(userRepository.findSignupTimesSince(any())).thenReturn(List.of());

            AdminTimeseriesResult res = service.getTimeseries("signups", "30d", null, null, null);

            assertThat(res.getMetric()).isEqualTo("signups");
            verify(userRepository).findSignupTimesSince(any());
        }

        @Test
        @DisplayName("custom from/to window bypasses the cache and computes directly")
        void customWindowNotCached() {
            when(messageRepository.findMessageTimesSince(any())).thenReturn(List.of());
            String from = Instant.now().minusSeconds(3600).toString();
            String to = Instant.now().toString();

            AdminTimeseriesResult res = service.getTimeseries("messages", null, "5m", from, to);

            assertThat(res).isNotNull();
            // custom windows never read the ts cache key
            verify(valueOps, never()).get(ArgumentMatchers.startsWith("admin:g0:ts"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getAnalytics")
    class Analytics {

        private void stubAnalyticsDefaults() {
            lenient().when(userRepository.count()).thenReturn(10L);
            lenient().when(userRepository.countByIsVerifiedTrue()).thenReturn(4L);
            lenient().when(userRepository.countByIsGuestTrue()).thenReturn(1L);
            lenient().when(userRepository.countByBannedTrue()).thenReturn(2L);
            lenient().when(userRepository.countByPresenceLastSeenAtAfter(any())).thenReturn(3L);
            lenient().when(userRepository.countGroupedByGender()).thenReturn(List.of());
            lenient().when(userRepository.countGroupedByCountry()).thenReturn(List.of());
            lenient().when(userRepository.findByIsDeletedTrueAndDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtDesc())
                    .thenReturn(List.of());
            lenient().when(userRepository.findSignupTimesSince(any())).thenReturn(List.of());
            lenient().when(chatRepository.count()).thenReturn(5L);
            lenient().when(chatRepository.countGroupedByType()).thenReturn(List.of());
            lenient().when(messageRepository.count()).thenReturn(20L);
            lenient().when(messageRepository.countGroupedByType()).thenReturn(List.of());
            lenient().when(messageRepository.findMessageTimesSince(any())).thenReturn(List.of());
            lenient().when(attachmentRepository.count()).thenReturn(6L);
            lenient().when(attachmentRepository.sumFileSize()).thenReturn(1234L);
            lenient().when(postRepository.count()).thenReturn(7L);
            lenient().when(storyRepository.count()).thenReturn(8L);
            lenient().when(profileViewRepository.count()).thenReturn(9L);
            lenient().when(matchReportRepository.count()).thenReturn(1L);
            lenient().when(userFollowRepository.count()).thenReturn(3L);
            lenient().when(friendRepository.count()).thenReturn(4L);
            lenient().when(friendRepository.countFriendsPerUser()).thenReturn(List.of());
            lenient().when(friendRepository.topConnectors(any(Pageable.class))).thenReturn(List.of());
            lenient().when(friendRequestRepository.countGroupedByStatus()).thenReturn(List.of());
        }

        @Test
        @DisplayName("assembles totals, a live lobby snapshot, and a presence status breakdown")
        void assemblesAnalytics() {
            stubAnalyticsDefaults();
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("a", "b"));
            when(presenceService.getAwayUsernames()).thenReturn(Set.of("c"));
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size("lobby:users")).thenReturn(4L);

            AdminAnalyticsResponse res = service.getAnalytics("30d");

            assertThat(res.getTotalUsers()).isEqualTo(10L);
            assertThat(res.getBannedUsers()).isEqualTo(2L);
            assertThat(res.getLobbyNow()).isEqualTo(4L);
            assertThat(res.getOnlineNow()).isEqualTo(2L);
            assertThat(res.getFriendLinks()).isEqualTo(4L);
            assertThat(res.getFriendships()).isEqualTo(2L); // links / 2
            assertThat(res.getUsersByStatus()).extracting(LabelCount::getLabel)
                    .containsExactly("Online", "Idle", "Offline");
            assertThat(res.getRange()).isEqualTo("30d");
        }

        @Test
        @DisplayName("Redis lobby lookup failure → lobbyNow falls back to 0 (fail-open)")
        void lobbyFailOpen() {
            stubAnalyticsDefaults();
            when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("redis down"));

            AdminAnalyticsResponse res = service.getAnalytics(null);

            assertThat(res.getLobbyNow()).isZero();
            assertThat(res.getRange()).isEqualTo("30d"); // null range defaults
        }

        @Test
        @DisplayName("friend-count distribution buckets every band, and label-counts tolerate null/enum keys")
        void richBreakdownsAndDistribution() {
            stubAnalyticsDefaults();
            when(userRepository.count()).thenReturn(20L);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size("lobby:users")).thenReturn(0L);

            // Every friend-count band: 0/blank(→"0 friends" via total), 1-5, 6-10, 11-25, 26-50, 50+.
            when(friendRepository.countFriendsPerUser()).thenReturn(List.of(
                    new Object[]{1L, 0L},    // <=5 → "1-5" bucket (a 0-count row still counts as "with friends")
                    new Object[]{2L, 3L},    // 1-5
                    new Object[]{3L, 8L},    // 6-10
                    new Object[]{4L, 20L},   // 11-25
                    new Object[]{5L, 40L},   // 26-50
                    new Object[]{6L, 100L},  // 50+
                    new Object[]{7L, null})); // null count → treated as 0

            // toLabelCounts: enum key, null key (→"Unknown"), null count (→0).
            when(messageRepository.countGroupedByType()).thenReturn(List.of(
                    new Object[]{MessageType.TEXT, 5L},
                    new Object[]{null, 3L},
                    new Object[]{"CUSTOM", null}));

            User pending = user("pending");
            pending.setDeleted(true);
            when(userRepository.findByIsDeletedTrueAndDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtDesc())
                    .thenReturn(List.of(pending));

            User hub = user("hub");
            when(friendRepository.topConnectors(any(Pageable.class)))
                    .thenReturn(List.<Object[]>of(new Object[]{hub, 9L}));

            AdminAnalyticsResponse res = service.getAnalytics("30d");

            // 7 rows had friends → bucket[0] = 20 - 7 = 13
            assertThat(res.getFriendCountDistribution()).extracting(LabelCount::getLabel)
                    .containsExactly("0 friends", "1-5", "6-10", "11-25", "26-50", "50+");
            assertThat(res.getFriendCountDistribution().get(0).getCount()).isEqualTo(13L); // 20 total - 7 with friends
            assertThat(res.getFriendCountDistribution().get(1).getCount()).isEqualTo(3L);  // counts 0, 3, and null→0 all ≤5
            assertThat(res.getFriendCountDistribution().get(5).getCount()).isEqualTo(1L);  // count 100
            assertThat(res.getMessagesByType()).extracting(LabelCount::getLabel)
                    .contains("TEXT", "Unknown", "CUSTOM");
            assertThat(res.getPendingDeletion()).extracting(AdminUserView::getUsername).containsExactly("pending");
            assertThat(res.getTopConnectors()).extracting(AdminConnectorView::getUsername).containsExactly("hub");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserFriends")
    class UserFriends {

        @Test
        @DisplayName("returns each friend as a connector, sorted by friend count desc")
        void sortedConnectors() {
            User u = user("sam");
            User f1 = user("f1");
            User f2 = user("f2");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(friendRepository.findFriendsByUser(u)).thenReturn(List.of(f1, f2));
            when(friendRepository.countByUserAndIsDeletedFalse(f1)).thenReturn(2L);
            when(friendRepository.countByUserAndIsDeletedFalse(f2)).thenReturn(9L);

            List<AdminConnectorView> res = service.getUserFriends(u.getUuid().toString());

            assertThat(res).extracting(AdminConnectorView::getUsername).containsExactly("f2", "f1");
            assertThat(res.get(0).getFriendCount()).isEqualTo(9L);
        }

        @Test
        @DisplayName("absent user → NotFoundException TM_064")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUserFriends(id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getAttachments")
    class Attachments {

        private MessageAttachment attachment(String url, MessageType type) {
            Chat c = chat(ChatType.PRIVATE);
            User sender = user("sender");
            Message m = Message.builder().chat(c).sender(sender).messageType(type).build();
            m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder()
                    .message(m).fileName("f.jpg").fileUrl(url).mimeType("image/jpeg").fileSize(500L).build();
            a.setId(idSeq++);
            a.setUuid(UUID.randomUUID());
            return a;
        }

        @Test
        @DisplayName("maps attachments (decrypted urls) and audits VIEW_ATTACHMENTS for a user filter")
        void mapsWithUserFilter() {
            User u = user("target");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            MessageAttachment a = attachment("/media/conversations/x/f.jpg", MessageType.IMAGE);
            when(attachmentRepository.findForAdmin(eq(u.getId()), eq(MessageType.IMAGE), eq(false), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(a)));

            PaginatedResponse<AdminAttachmentView> res =
                    service.getAttachments(u.getUuid().toString(), "image", false, 0, 20, "root");

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getFileUrl()).isEqualTo("/media/conversations/x/f.jpg");
            verify(auditLogger).write(eq("root"), eq("VIEW_ATTACHMENTS"), eq("ATTACHMENT"), anyString(), anyString());
        }

        @Test
        @DisplayName("no user filter + unknown type → queries with null senderId and null type")
        void noFilters() {
            when(attachmentRepository.findForAdmin(eq(null), eq(null), eq(true), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            service.getAttachments(null, "bogus", true, 0, 20, "root");

            verify(attachmentRepository).findForAdmin(eq(null), eq(null), eq(true), any(Pageable.class));
            verify(auditLogger).write(eq("root"), eq("VIEW_ATTACHMENTS"), eq("ATTACHMENT"), eq("all"), anyString());
        }

        @Test
        @DisplayName("user filter with an absent user → NotFoundException TM_064")
        void userFilterNotFound() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAttachments(id.toString(), null, false, 0, 20, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getStorageObjects")
    class StorageObjects {

        private MediaStorage.StoredObject obj(String key, long size, String ct) {
            return new MediaStorage.StoredObject("/media/" + key, key, size, Instant.now(), ct);
        }

        @Test
        @DisplayName("reconciles storage, flags chat-media orphans, computes counts, and audits")
        void reconcilesAndCounts() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/u/a.jpg", 100, "image/jpeg"),
                    obj("conversations/u/b.mp4", 200, "video/mp4"),
                    obj("profiles/p.png", 50, "image/png")));

            AdminStorageListResponse res = service.getStorageObjects(
                    null, null, null, false, null, "newest", 0, 50, "root");

            assertThat(res.getTotal()).isEqualTo(3L);
            assertThat(res.getCounts().getAll()).isEqualTo(3L);
            assertThat(res.getCounts().getImage()).isEqualTo(2L);
            assertThat(res.getCounts().getVideo()).isEqualTo(1L);
            assertThat(res.getCounts().getBytes()).isEqualTo(350L);
            // chat-media without a DB row is an orphan; profiles/* is not
            assertThat(res.getCounts().getOrphan()).isEqualTo(2L);
            verify(auditLogger).write(eq("root"), eq("VIEW_STORAGE"), eq("STORAGE"), anyString(), anyString());
        }

        @Test
        @DisplayName("kind filter narrows the page but counts remain over the pre-kind base set")
        void kindFilterNarrowsPage() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/u/a.jpg", 100, "image/jpeg"),
                    obj("conversations/u/b.mp4", 200, "video/mp4")));

            AdminStorageListResponse res = service.getStorageObjects(
                    null, null, "video", false, null, "newest", 0, 50, "root");

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getKind()).isEqualTo("video");
            assertThat(res.getTotal()).isEqualTo(1L);       // page total after kind filter
            assertThat(res.getCounts().getAll()).isEqualTo(2L); // counts over the base
        }

        @Test
        @DisplayName("onlyOrphans + category filter restricts the result set")
        void categoryAndOrphanFilter() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/u/a.jpg", 100, "image/jpeg"),
                    obj("profiles/p.png", 50, "image/png")));

            AdminStorageListResponse res = service.getStorageObjects(
                    null, "conversations", null, true, null, "newest", 0, 50, "root");

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getCategory()).isEqualTo("conversations");
        }

        @Test
        @DisplayName("search matches on the object key")
        void searchFilter() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/u/holiday.jpg", 100, "image/jpeg"),
                    obj("conversations/u/work.mp4", 200, "video/mp4")));

            AdminStorageListResponse res = service.getStorageObjects(
                    null, null, null, false, "holiday", "newest", 0, 50, "root");

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getKey()).contains("holiday");
        }

        @Test
        @DisplayName("empty store → empty items, zeroed counts, no NPE")
        void emptyStore() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of());

            AdminStorageListResponse res = service.getStorageObjects(
                    null, null, null, false, null, "largest", 0, 50, "root");

            assertThat(res.getItems()).isEmpty();
            assertThat(res.getCounts().getAll()).isZero();
            assertThat(res.isHasNext()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("deleteStorageObject")
    class DeleteStorageObject {

        @Test
        @DisplayName("deletes the physical object by reference and audits DELETE_STORAGE_OBJECT")
        void deletesObject() {
            service.deleteStorageObject("conversations/u/a.jpg", "root");

            verify(mediaStorage).delete("/media/conversations/u/a.jpg");
            verify(auditLogger).write(eq("root"), eq("DELETE_STORAGE_OBJECT"), eq("STORAGE"),
                    eq("conversations/u/a.jpg"), anyString());
        }

        @Test
        @DisplayName("null key → BadRequestException TM_071, nothing deleted")
        void nullKey() {
            assertThatThrownBy(() -> service.deleteStorageObject(null, "root"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_071"));
            verify(mediaStorage, never()).delete(anyString());
        }

        @Test
        @DisplayName("path-traversal key → BadRequestException TM_071")
        void unsafeKey() {
            assertThatThrownBy(() -> service.deleteStorageObject("../../etc/passwd", "root"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_071"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listPosts / getPostLikes / getPostComments")
    class Posts {

        private Post post(User author) {
            Post p = Post.builder().user(author).content("a post").build();
            p.setId(idSeq++);
            p.setUuid(UUID.randomUUID());
            return p;
        }

        @Test
        @DisplayName("listPosts maps all posts (incl. soft-deleted) with like/comment counts")
        void listPosts() {
            Post p = post(user("author"));
            when(postRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(p)));
            when(postLikeRepository.countByPost(p)).thenReturn(3L);
            when(postCommentRepository.countForPost(p)).thenReturn(1L);

            PaginatedResponse<AdminPostView> res = service.listPosts(0, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getContent()).isEqualTo("a post");
        }

        @Test
        @DisplayName("getPostLikes maps the likers of an existing post")
        void postLikes() {
            Post p = post(user("author"));
            PostLike like = PostLike.builder().post(p).user(user("liker")).build();
            like.setUuid(UUID.randomUUID());
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.findByPost(eq(p), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(like)));

            PaginatedResponse<AdminPostLikeView> res =
                    service.getPostLikes(p.getUuid().toString(), 0, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getUsername()).isEqualTo("liker");
        }

        @Test
        @DisplayName("getPostLikes on absent post → NotFoundException TM_180")
        void postLikesNotFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPostLikes(id.toString(), 0, 20))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_180"));
        }

        @Test
        @DisplayName("getPostComments maps comments of an existing post")
        void postComments() {
            Post p = post(user("author"));
            PostComment cm = PostComment.builder().post(p).user(user("commenter")).content("nice").build();
            cm.setUuid(UUID.randomUUID());
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postCommentRepository.findAllForPost(eq(p), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(cm)));

            PaginatedResponse<AdminPostCommentView> res =
                    service.getPostComments(p.getUuid().toString(), 0, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getContent()).isEqualTo("nice");
        }

        @Test
        @DisplayName("getPostComments on absent post → NotFoundException TM_180")
        void postCommentsNotFound() {
            UUID id = UUID.randomUUID();
            when(postRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPostComments(id.toString(), 0, 20))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_180"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listReports / getReport / reviewReport")
    class Reports {

        private MatchReport report(String status) {
            MatchReport r = new MatchReport();
            r.setId(idSeq++);
            r.setUuid(UUID.randomUUID());
            r.setReason("spam");
            r.setStatus(status);
            r.setReporter(user("reporter"));
            r.setReported(user("reported"));
            return r;
        }

        @Test
        @DisplayName("listReports with no status → findAll; maps to views with counts")
        void listAllReports() {
            MatchReport r = report("PENDING");
            when(matchReportRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(r)));

            PaginatedResponse<AdminReportView> res = service.listReports(null, 0, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("listReports with a concrete status → findByStatus")
        void listByStatus() {
            MatchReport r = report("DISMISSED");
            when(matchReportRepository.findByStatus(eq("DISMISSED"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(r)));

            service.listReports("dismissed", 0, 20);

            verify(matchReportRepository).findByStatus(eq("DISMISSED"), any(Pageable.class));
        }

        @Test
        @DisplayName("getReport enriches with the reported user's summary + history")
        void getReportEnriched() {
            MatchReport r = report("PENDING");
            when(matchReportRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));
            when(matchReportRepository.findByReportedId(eq(r.getReported().getId()), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            when(chatRepository.findChatsByUser(r.getReported())).thenReturn(List.of());
            when(chatRepository.findPrivateChatBetweenUsers(anyLong(), anyLong())).thenReturn(List.of());

            AdminReportView v = service.getReport(r.getUuid().toString());

            assertThat(v.getReportedSummary()).isNotNull();
            assertThat(v.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("getReport absent → NotFoundException TM_181")
        void getReportNotFound() {
            UUID id = UUID.randomUUID();
            when(matchReportRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReport(id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_181"));
        }

        @Test
        @DisplayName("reviewReport DISMISS → status DISMISSED, actionTaken NONE, audited")
        void reviewDismiss() {
            MatchReport r = report("PENDING");
            when(matchReportRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            service.reviewReport(r.getUuid().toString(), "dismiss", "spammy", "root");

            assertThat(r.getStatus()).isEqualTo("DISMISSED");
            assertThat(r.getActionTaken()).isEqualTo("NONE");
            assertThat(r.getReviewedBy()).isEqualTo("root");
            assertThat(r.getResolutionNote()).isEqualTo("spammy");
            verify(matchReportRepository).save(r);
            verify(auditLogger).write(eq("root"), eq("REVIEW_REPORT"), eq("REPORT"), anyString(), anyString());
        }

        @Test
        @DisplayName("reviewReport RESOLVE → status ACTION_TAKEN, actionTaken REVIEWED")
        void reviewResolve() {
            MatchReport r = report("PENDING");
            when(matchReportRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            service.reviewReport(r.getUuid().toString(), "RESOLVE", null, "root");

            assertThat(r.getStatus()).isEqualTo("ACTION_TAKEN");
            assertThat(r.getActionTaken()).isEqualTo("REVIEWED");
        }

        @Test
        @DisplayName("reviewReport BAN_REPORTED → bans the reported user and saves them")
        void reviewBan() {
            MatchReport r = report("PENDING");
            when(matchReportRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            service.reviewReport(r.getUuid().toString(), "BAN_REPORTED", null, "root");

            assertThat(r.getReported().isBanned()).isTrue();
            assertThat(r.getActionTaken()).isEqualTo("BANNED_REPORTED");
            verify(userRepository).save(r.getReported());
        }

        @Test
        @DisplayName("reviewReport unknown action → BadRequestException TM_071, nothing saved")
        void reviewUnknownAction() {
            MatchReport r = report("PENDING");
            when(matchReportRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.reviewReport(r.getUuid().toString(), "NUKE", null, "root"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_071"));
            verify(matchReportRepository, never()).save(any());
        }

        @Test
        @DisplayName("reviewReport absent → NotFoundException TM_181")
        void reviewNotFound() {
            UUID id = UUID.randomUUID();
            when(matchReportRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reviewReport(id.toString(), "DISMISS", null, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_181"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listFeedback / updateFeedbackStatus")
    class FeedbackOps {

        private Feedback feedback() {
            Feedback f = Feedback.builder().user(user("fbuser")).rating(4).comment("great")
                    .type(FeedbackType.MANUAL).status(FeedbackStatus.NEW).build();
            f.setId(idSeq++);
            f.setUuid(UUID.randomUUID());
            return f;
        }

        @Test
        @DisplayName("listFeedback with both type + status filters uses findByTypeAndStatus")
        void listByTypeAndStatus() {
            Feedback f = feedback();
            when(feedbackRepository.findByTypeAndStatus(eq(FeedbackType.MANUAL), eq(FeedbackStatus.NEW), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(f)));

            PaginatedResponse<AdminFeedbackView> res = service.listFeedback("MANUAL", "NEW", 0, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getStatus()).isEqualTo("NEW");
        }

        @Test
        @DisplayName("listFeedback with type only uses findByType")
        void listByTypeOnly() {
            when(feedbackRepository.findByType(eq(FeedbackType.MANUAL), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listFeedback("manual", "ALL", 0, 20);

            verify(feedbackRepository).findByType(eq(FeedbackType.MANUAL), any(Pageable.class));
        }

        @Test
        @DisplayName("listFeedback with status only uses findByStatus")
        void listByStatusOnly() {
            when(feedbackRepository.findByStatus(eq(FeedbackStatus.REVIEWED), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listFeedback(null, "REVIEWED", 0, 20);

            verify(feedbackRepository).findByStatus(eq(FeedbackStatus.REVIEWED), any(Pageable.class));
        }

        @Test
        @DisplayName("listFeedback with no filters uses findAll")
        void listNoFilter() {
            when(feedbackRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.listFeedback(null, null, 0, 20);

            verify(feedbackRepository).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("updateFeedbackStatus moves the row, saves, and audits")
        void updateStatus() {
            Feedback f = feedback();
            when(feedbackRepository.findByUuid(f.getUuid())).thenReturn(Optional.of(f));

            AdminFeedbackView v = service.updateFeedbackStatus(f.getUuid().toString(), "ARCHIVED", "root");

            assertThat(f.getStatus()).isEqualTo(FeedbackStatus.ARCHIVED);
            assertThat(v.getStatus()).isEqualTo("ARCHIVED");
            verify(feedbackRepository).save(f);
            verify(auditLogger).write(eq("root"), eq("UPDATE_FEEDBACK_STATUS"), eq("FEEDBACK"), anyString(), anyString());
        }

        @Test
        @DisplayName("updateFeedbackStatus with an unknown status → BadRequestException TM_071")
        void updateInvalidStatus() {
            Feedback f = feedback();
            when(feedbackRepository.findByUuid(f.getUuid())).thenReturn(Optional.of(f));

            assertThatThrownBy(() -> service.updateFeedbackStatus(f.getUuid().toString(), "NONSENSE", "root"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_071"));
            verify(feedbackRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateFeedbackStatus absent → NotFoundException TM_312")
        void updateNotFound() {
            UUID id = UUID.randomUUID();
            when(feedbackRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateFeedbackStatus(id.toString(), "NEW", "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_312"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toView mapper (presence + detail + null-field branches)")
    class ToViewMapper {

        @Test
        @DisplayName("detailed view of a fully-populated, idle user renders every optional field")
        void detailedIdleUserPopulated() {
            User u = user("populated");
            u.setGoogleId("g-123");
            u.setInterests(new HashSet<>(Set.of(Interest.SPORTS)));
            u.setLastLocation("NY");
            u.setLastLocationAt(Instant.now());
            u.setPresenceLastSeenAt(Instant.now());
            u.setDeletionRequestedAt(Instant.now());
            u.setCreatedAt(Instant.now());
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(presenceService.getAwayUsernames()).thenReturn(Set.of("populated")); // idle bucket

            AdminUserView v = service.getUser(u.getUuid().toString());

            assertThat(v.getPresence()).isEqualTo("idle");
            assertThat(v.isHasGoogleLinked()).isTrue();
            assertThat(v.getInterests()).contains("SPORTS");
            assertThat(v.getLastLocationAt()).isNotNull();
            assertThat(v.getDeletionRequestedAt()).isNotNull();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("list view of an offline user (neither online nor away) resolves to 'offline'")
        void listOfflineUser() {
            User u = user("ghost"); // not in the online/away sets → offline
            when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(u)));

            PaginatedResponse<AdminUserView> res = service.listUsers(new AdminUserFilter(), 0, 20);

            assertThat(res.getItems().get(0).getPresence()).isEqualTo("offline");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserFull (populated account map)")
    class GetUserFullPopulated {

        @Test
        @DisplayName("all optional account fields present → their non-null branches render")
        void allFieldsPresent() {
            User u = user("full");
            u.setPasswordHash("HASH");
            u.setGoogleId("g-abc");
            u.setInterests(new HashSet<>(Set.of(Interest.SPORTS)));
            u.getRoles().add(Role.builder().name("ROLE_USER").build());
            u.setLastLocationAt(Instant.now());
            u.setPresenceLastSeenAt(Instant.now());
            u.setDeletionRequestedAt(Instant.now());
            u.setCreatedAt(Instant.now());
            u.setUpdatedAt(Instant.now());
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.empty());
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.empty());

            AdminUserFullView v = service.getUserFull(u.getUuid().toString());

            assertThat(v.getAccount().get("passwordSet")).isEqualTo(true);
            assertThat(v.getAccount().get("googleLinked")).isEqualTo(true);
            assertThat(v.getAccount().get("lastLocationAt")).isNotNull();
            assertThat(v.getAccount().get("createdAt")).isNotNull();
            assertThat((List<?>) v.getAccount().get("interests")).hasSize(1);
            assertThat((List<String>) v.getAccount().get("roles")).contains("ROLE_USER");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("updateUser (remaining partial-field + same-identity branches)")
    class UpdateUserAllFields {

        @Test
        @DisplayName("sets every scalar field; same-case-insensitive email/username short-circuit the uniqueness checks; blank password is ignored")
        void appliesAllRemainingFields() {
            User u = user("multi"); // username "multi", email "multi@x.com"
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));

            AdminUpdateUserRequest req = new AdminUpdateUserRequest();
            req.setBio("bio");
            req.setCountry("US");
            req.setCity("NYC");
            req.setGender("FEMALE");
            req.setOccupation("dev");
            req.setEducation("phd");
            req.setMobileNumber("+15550000");
            req.setVerified(true);
            req.setEmail("MULTI@X.COM");   // equals current (ignoring case) → no existsBy call
            req.setUsername("MULTI");       // equals current (ignoring case) → no existsBy call
            req.setNewPassword("   ");      // blank → no re-hash

            service.updateUser(u.getUuid().toString(), req, "root");

            assertThat(u.getBio()).isEqualTo("bio");
            assertThat(u.getCountry()).isEqualTo("US");
            assertThat(u.getCity()).isEqualTo("NYC");
            assertThat(u.getGender()).isEqualTo("FEMALE");
            assertThat(u.getOccupation()).isEqualTo("dev");
            assertThat(u.getEducation()).isEqualTo("phd");
            assertThat(u.getMobileNumber()).isEqualTo("+15550000");
            assertThat(u.isVerified()).isTrue();
            assertThat(u.getEmail()).isEqualTo("multi@x.com");
            verify(userRepository).save(u);
            verify(userRepository, never()).existsByEmailIgnoreCase(anyString());
            verify(userRepository, never()).existsByUsernameIgnoreCase(anyString());
            verify(passwordEncoder, never()).encode(anyString());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChatMessages (attachment media-url branch)")
    class GetChatMessagesAttachment {

        @Test
        @DisplayName("a message with an attachment surfaces a decrypted mediaUrl")
        void mapsAttachmentMediaUrl() {
            Chat c = chat(ChatType.PRIVATE);
            User sender = user("gm");
            Message m = Message.builder().chat(c).sender(sender).content("cipher")
                    .messageType(MessageType.IMAGE).build();
            m.setUuid(UUID.randomUUID());
            MessageAttachment att = MessageAttachment.builder().message(m).fileUrl("mediacipher").build();
            att.setUuid(UUID.randomUUID());
            m.getAttachments().add(att);
            when(chatRepository.findByUuidWithMembers(c.getUuid())).thenReturn(Optional.of(c));
            when(messageRepository.findByChat(eq(c), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(messageMapper.resolveMessageStatus(m)).thenReturn("SENT");

            PaginatedResponse<AdminMessageView> res =
                    service.getChatMessages(c.getUuid().toString(), 0, 50, "root");

            assertThat(res.getItems().get(0).getMediaUrl()).isEqualTo("mediacipher");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toAttachmentView (shared-with + orphaned-message branches)")
    class AttachmentViewMapper {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("chat members other than the sender become sharedWith; thumbnail + chat metadata render")
        void sharedWithAndThumbnail() {
            Chat c = chat(ChatType.PRIVATE);
            User sender = user("sn");
            User receiver = user("rc");
            c.getMembers().add(ChatMember.builder().chat(c).user(sender).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(receiver).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(null).build());
            Message m = Message.builder().chat(c).sender(sender).messageType(MessageType.IMAGE).build();
            m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder().message(m).fileName("f.jpg")
                    .fileUrl("/u.jpg").thumbnailUrl("/t.jpg").mimeType("image/jpeg").fileSize(9L).build();
            a.setUuid(UUID.randomUUID());
            when(attachmentRepository.findForAdmin(eq(null), eq(null), eq(false), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(a)));

            PaginatedResponse<AdminAttachmentView> res =
                    service.getAttachments(null, null, false, 0, 20, "root");

            var item = res.getItems().get(0);
            assertThat(item.getSharedWith())
                    .extracting(AdminAttachmentView.SharedUser::getUsername)
                    .containsExactly("rc"); // sender + null-user member filtered out
            assertThat(item.getThumbnailUrl()).isEqualTo("/t.jpg");
            assertThat(item.getChatType()).isEqualTo("PRIVATE");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("orphaned message (no chat/sender/type, no attachment uuid) → null metadata + numeric id")
        void orphanedMessage() {
            Message m = Message.builder().build(); // chat null, sender null
            m.setMessageType(null); // @Builder.Default sets TEXT; force null to hit the null-type branch
            m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder().message(m).fileName("x").fileUrl("y").build();
            a.setId(77L); // no uuid → numeric id fallback
            when(attachmentRepository.findForAdmin(eq(null), eq(null), eq(true), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(a)));

            PaginatedResponse<AdminAttachmentView> res =
                    service.getAttachments(null, "bogus", true, 0, 20, "root");

            var item = res.getItems().get(0);
            assertThat(item.getSharedWith()).isEmpty();
            assertThat(item.getChatId()).isNull();
            assertThat(item.getSenderUsername()).isNull();
            assertThat(item.getType()).isNull();
            assertThat(item.getId()).isEqualTo("77");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toPostView + like/comment mappers (media/poll/audio + null-relation branches)")
    class PostViewMappers {

        private Post post(User author) {
            Post p = Post.builder().user(author).content("a post").build();
            p.setId(idSeq++);
            p.setUuid(UUID.randomUUID());
            return p;
        }

        @Test
        @DisplayName("a rich post (media + poll + audio) maps every media item and the poll/audio flags")
        void richPost() {
            Post p = post(user("author"));
            p.setShortCode("SC1");
            p.getMedia().add(PostMedia.builder().mediaUrl("m1").mediaType("IMAGE").build());
            p.getMedia().add(PostMedia.builder().mediaUrl("m2").mediaType("VIDEO").build());
            p.setPoll(Poll.builder().build());
            p.setAudio(AudioTrack.builder().build());
            when(postRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(p)));
            when(postLikeRepository.countByPost(p)).thenReturn(2L);
            when(postCommentRepository.countForPost(p)).thenReturn(1L);

            PaginatedResponse<AdminPostView> res = service.listPosts(0, 20);

            AdminPostView v = res.getItems().get(0);
            assertThat(v.isHasPoll()).isTrue();
            assertThat(v.isHasAudio()).isTrue();
            assertThat(v.getMedia()).hasSize(2);
            assertThat(v.getShortCode()).isEqualTo("SC1");
        }

        @Test
        @DisplayName("a post with no author and no uuid → null author fields + numeric id")
        void authorlessPost() {
            Post p = Post.builder().content("c").build();
            p.setId(88L); // no uuid, no author
            when(postRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(p)));
            when(postLikeRepository.countByPost(p)).thenReturn(0L);
            when(postCommentRepository.countForPost(p)).thenReturn(0L);

            PaginatedResponse<AdminPostView> res = service.listPosts(0, 20);

            AdminPostView v = res.getItems().get(0);
            assertThat(v.getAuthorUsername()).isNull();
            assertThat(v.getAuthorId()).isNull();
            assertThat(v.getId()).isEqualTo("88");
        }

        @Test
        @DisplayName("getPostLikes tolerates a like whose user row is gone (null username)")
        void nullUserLike() {
            Post p = post(user("author"));
            PostLike like = PostLike.builder().post(p).user(null).build();
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postLikeRepository.findByPost(eq(p), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(like)));

            PaginatedResponse<AdminPostLikeView> res =
                    service.getPostLikes(p.getUuid().toString(), 0, 20);

            assertThat(res.getItems().get(0).getUsername()).isNull();
        }

        @Test
        @DisplayName("getPostComments maps a null-user reply and resolves its parent id")
        void nullUserReplyWithParent() {
            Post p = post(user("author"));
            PostComment parent = PostComment.builder().post(p).content("parent").build();
            parent.setUuid(UUID.randomUUID());
            PostComment child = PostComment.builder().post(p).user(null).content("child").parent(parent).build();
            when(postRepository.findByUuid(p.getUuid())).thenReturn(Optional.of(p));
            when(postCommentRepository.findAllForPost(eq(p), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(child)));

            PaginatedResponse<AdminPostCommentView> res =
                    service.getPostComments(p.getUuid().toString(), 0, 20);

            var item = res.getItems().get(0);
            assertThat(item.getUsername()).isNull();
            assertThat(item.getParentId()).isEqualTo(parent.getUuid().toString());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toReportView (session + null-party branches)")
    class ReportViewMapper {

        private MatchReport report(String status, User reporter, User reported) {
            MatchReport r = new MatchReport();
            r.setId(idSeq++);
            r.setUuid(UUID.randomUUID());
            r.setReason("spam");
            r.setStatus(status);
            r.setReporter(reporter);
            r.setReported(reported);
            return r;
        }

        @Test
        @DisplayName("a report carrying a match session renders the session block and the count fields")
        void withSessionAndCounts() {
            User reporter = user("rp");
            User reported = user("rd");
            MatchReport r = report("PENDING", reporter, reported);
            MatchSession s = MatchSession.builder()
                    .host(user("h")).peer(user("pe")).endedAt(Instant.now()).build();
            s.setUuid(UUID.randomUUID());
            r.setSession(s);
            when(matchReportRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(r)));
            when(matchReportRepository.countByReportedId(reported.getId())).thenReturn(3L);
            when(matchReportRepository.countByReporterId(reporter.getId())).thenReturn(2L);
            when(matchReportRepository.countByReporterIdAndReportedId(reporter.getId(), reported.getId()))
                    .thenReturn(1L);

            PaginatedResponse<AdminReportView> res = service.listReports(null, 0, 20);

            AdminReportView v = res.getItems().get(0);
            assertThat(v.getSession()).isNotNull();
            assertThat(v.getSession().getHostUsername()).isEqualTo("h");
            assertThat(v.getReportsAgainstReported()).isEqualTo(3L);
            assertThat(v.getReportsByReporter()).isEqualTo(2L);
            assertThat(v.getDuplicateCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("null reporter/reported and a session with no host/peer → null parties, zeroed counts, no count query")
        void nullPartiesAndEmptySession() {
            MatchReport r = report("PENDING", null, null);
            MatchSession s = MatchSession.builder().build();
            s.setUuid(UUID.randomUUID());
            r.setSession(s);
            when(matchReportRepository.findByStatus(eq("PENDING"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(r)));

            PaginatedResponse<AdminReportView> res = service.listReports("pending", 0, 20);

            AdminReportView v = res.getItems().get(0);
            assertThat(v.getReporter()).isNull();
            assertThat(v.getReported()).isNull();
            assertThat(v.getSession().getHostUsername()).isNull();
            assertThat(v.getReportsAgainstReported()).isZero();
            verify(matchReportRepository, never()).countByReportedId(anyLong());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("reconcileStorage — linked-attachment enrichment + kind classification")
    class ReconcileStorageLinked {

        private MediaStorage.StoredObject obj(String key, long size, String ct) {
            return new MediaStorage.StoredObject("/media/" + key, key, size, Instant.now(), ct);
        }

        /** Build a chat with a sender member, a receiver member, and a null-user member. */
        private Chat chatWithMembers(User sender, User receiver) {
            Chat c = chat(ChatType.PRIVATE);
            c.getMembers().add(ChatMember.builder().chat(c).user(sender).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(receiver).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(null).build());
            return c;
        }

        private MessageAttachment linked(Chat c, User sender, MessageType type, String mime,
                                         String fileName, String key, String thumbKey) {
            Message m = Message.builder().chat(c).sender(sender).messageType(type).content("caption").build();
            m.setId(idSeq++);
            m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder()
                    .message(m).fileName(fileName).fileUrl("/media/" + key).mimeType(mime).fileSize(10L)
                    .thumbnailUrl(thumbKey != null ? "/media/" + thumbKey : null).build();
            a.setId(idSeq++);
            a.setUuid(UUID.randomUUID());
            return a;
        }

        @Test
        @DisplayName("linked chat media is classified by type/mime/name; thumbnails fold into their parent; receivers exclude the sender")
        void classifiesLinkedAndFoldsThumbnails() {
            User sender = user("sender");
            User receiver = user("receiver");
            Chat c = chatWithMembers(sender, receiver);

            String thumbKey = "conversations/c/img-thumb.jpg";
            List<MessageAttachment> atts = List.of(
                    linked(c, sender, MessageType.IMAGE, "image/jpeg", "img.jpg", "conversations/c/img.jpg", thumbKey),
                    linked(c, sender, MessageType.VIDEO, "video/mp4", "clip.mp4", "conversations/c/clip.mp4", null),
                    linked(c, sender, MessageType.AUDIO, "audio/webm", "voice-message-1.webm", "conversations/c/voice.webm", null),
                    linked(c, sender, MessageType.AUDIO, "audio/mpeg", "song.mp3", "conversations/c/song.mp3", null),
                    linked(c, sender, MessageType.DOCUMENT, "application/pdf", "doc.pdf", "conversations/c/doc.pdf", null),
                    linked(c, sender, null, null, "weird.xyz", "conversations/c/weird.xyz", null));
            when(attachmentRepository.findAll()).thenReturn(atts);
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/c/img.jpg", 10, "image/jpeg"),
                    obj("conversations/c/clip.mp4", 10, "video/mp4"),
                    obj("conversations/c/voice.webm", 10, "audio/webm"),
                    obj("conversations/c/song.mp3", 10, "audio/mpeg"),
                    obj("conversations/c/doc.pdf", 10, "application/pdf"),
                    obj("conversations/c/weird.xyz", 10, null),
                    obj(thumbKey, 5, "image/jpeg"))); // folded into img.jpg → not its own tile

            AdminStorageListResponse res = service.getStorageObjects(
                    null, null, null, false, null, "newest", 0, 50, "root");

            assertThat(res.getTotal()).isEqualTo(6L);           // thumbnail folded away
            assertThat(res.getCounts().getLinked()).isEqualTo(6L);
            assertThat(res.getCounts().getOrphan()).isZero();
            assertThat(res.getCounts().getImage()).isEqualTo(1L);
            assertThat(res.getCounts().getVideo()).isEqualTo(1L);
            assertThat(res.getCounts().getVoice()).isEqualTo(1L); // voice-message-*.webm
            assertThat(res.getCounts().getAudio()).isEqualTo(1L); // song.mp3
            assertThat(res.getCounts().getFile()).isEqualTo(2L);  // doc.pdf + weird.xyz

            var imageItem = res.getItems().stream()
                    .filter(o -> "image".equals(o.getKind())).findFirst().orElseThrow();
            assertThat(imageItem.getReceivers())
                    .extracting(AdminStorageObjectView.SharedUser::getUsername)
                    .containsExactly("receiver"); // sender + null-user filtered out
            assertThat(imageItem.getThumbnailUrl()).isEqualTo("/media/" + thumbKey);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("reconcileStorage — orphan / non-chat classification from extension + content-type")
    class ReconcileStorageKinds {

        private MediaStorage.StoredObject obj(String key, String ct) {
            return new MediaStorage.StoredObject("/media/" + key, key, 10, Instant.now(), ct);
        }

        @Test
        @DisplayName("kind is derived from content-type then extension; orphan is true only for chat-media categories")
        void classifiesByExtensionAndCategory() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/x/a.png", null),   // image (ext), orphan
                    obj("lobby/l.mp4", null),             // video (ext), orphan
                    obj("strangers/s.wav", null),         // audio (ext), orphan
                    obj("conversations/y/b", "audio/mpeg"), // audio (content-type), orphan
                    obj("posts/p.gif", null),             // image, NOT orphan (non-chat category)
                    obj("stories/v.mov", null),           // video, NOT orphan
                    obj("profiles/pic.jpeg", null),       // image, NOT orphan
                    obj("other/f.dat", null)));           // file (default), category "other"

            AdminStorageListResponse res = service.getStorageObjects(
                    null, null, null, false, null, "newest", 0, 50, "root");

            assertThat(res.getCounts().getAll()).isEqualTo(8L);
            assertThat(res.getCounts().getImage()).isEqualTo(3L);
            assertThat(res.getCounts().getVideo()).isEqualTo(2L);
            assertThat(res.getCounts().getAudio()).isEqualTo(2L);
            assertThat(res.getCounts().getFile()).isEqualTo(1L);
            assertThat(res.getCounts().getOrphan()).isEqualTo(4L);
            assertThat(res.getCounts().getLinked()).isZero();
            assertThat(res.getItems()).extracting(
                    AdminStorageObjectView::getCategory)
                    .contains("conversations", "lobby", "strangers", "posts", "stories", "profiles", "other");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getStorageObjects — alternate sorts + un-decryptable row skip")
    class StorageSortsAndFailures {

        private MediaStorage.StoredObject obj(String key, long size) {
            return new MediaStorage.StoredObject("/media/" + key, key, size, Instant.now(), "image/jpeg");
        }

        @Test
        @DisplayName("oldest / smallest / name sorts each resolve to a comparator without error")
        void alternateSorts() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/u/a.jpg", 300),
                    obj("conversations/u/b.jpg", 100)));

            for (String sort : List.of("oldest", "smallest", "name")) {
                AdminStorageListResponse res = service.getStorageObjects(
                        null, null, null, false, null, sort, 0, 50, "root");
                assertThat(res.getItems()).hasSize(2);
            }
        }

        @Test
        @DisplayName("an attachment whose file reference cannot be decrypted is skipped (not fatal)")
        void undecryptableRowSkipped() {
            Message m = Message.builder().build(); // no chat → chatId null
            MessageAttachment a = MessageAttachment.builder().message(m).fileUrl("ENC").build();
            when(attachmentRepository.findAll()).thenReturn(List.of(a));
            when(messageCryptoService.decrypt(any(), eq("ENC"))).thenThrow(new RuntimeException("bad key"));
            when(mediaStorage.list(null)).thenReturn(List.of());

            AdminStorageListResponse res = service.getStorageObjects(
                    null, null, null, false, null, "newest", 0, 50, "root");

            assertThat(res.getTotal()).isZero();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toChatView (preview / derived-name / last-message branches)")
    class ToChatViewMapper {

        private void stubGetUserChats(User owner, Chat c, Message last, long count) {
            when(userRepository.findByUuid(owner.getUuid())).thenReturn(Optional.of(owner));
            when(chatRepository.findAllChatsByUserForAdmin(owner)).thenReturn(List.of(c));
            when(messageRepository.countByChat(c)).thenReturn(count);
            when(messageRepository.findFirstByChatAndIsDeletedFalseOrderByCreatedAtDesc(c))
                    .thenReturn(Optional.ofNullable(last));
        }

        @Test
        @DisplayName("blank name derives from members; long preview truncates; null-uuid member id null")
        void longPreviewDerivedName() {
            User owner = user("owner1");
            User m1 = user("m1"); m1.setUuid(null); // null-uuid member → Member.id null
            User m2 = user("m2");
            Chat c = Chat.builder().chatType(ChatType.GROUP).name("   ").build(); // blank → derive
            c.setId(idSeq++); c.setUuid(UUID.randomUUID());
            c.setUpdatedAt(Instant.now());
            c.getMembers().add(ChatMember.builder().chat(c).user(null).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(m1).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(m2).build());
            User sender = user("cs");
            Message last = Message.builder().chat(c).sender(sender).content("x".repeat(200))
                    .messageType(MessageType.TEXT).build();
            last.setCreatedAt(Instant.now());
            stubGetUserChats(owner, c, last, 5L);

            AdminChatView v = service.getUserChats(owner.getUuid().toString()).get(0);

            assertThat(v.getName()).contains("m1", "m2");       // derived from member names
            assertThat(v.getLastMessagePreview()).endsWith("…"); // >140 → truncated
            assertThat(v.getLastMessageSender()).isEqualTo("cs");
            assertThat(v.getMessageCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("null content + media type → type-name preview; null sender/created/updated → null lastMessageAt")
        void mediaPreviewNoSender() {
            User owner = user("owner2");
            Chat c = chat(ChatType.PRIVATE);        // name "Chat" (non-blank), updatedAt null
            Message last = Message.builder().chat(c).sender(null).content(null)
                    .messageType(MessageType.IMAGE).build(); // createdAt null
            stubGetUserChats(owner, c, last, 0L);

            AdminChatView v = service.getUserChats(owner.getUuid().toString()).get(0);

            assertThat(v.getName()).isEqualTo("Chat");
            assertThat(v.getLastMessagePreview()).isEqualTo("IMAGE");
            assertThat(v.getLastMessageSender()).isNull();
            assertThat(v.getLastMessageAt()).isNull();
        }

        @Test
        @DisplayName("blank decrypted content + TEXT type → no preview")
        void blankContentTextNoPreview() {
            User owner = user("owner3");
            Chat c = chat(ChatType.PRIVATE);
            Message last = Message.builder().chat(c).sender(user("s3")).content("")
                    .messageType(MessageType.TEXT).build();
            stubGetUserChats(owner, c, last, 1L);

            AdminChatView v = service.getUserChats(owner.getUuid().toString()).get(0);
            assertThat(v.getLastMessagePreview()).isNull();
        }

        @Test
        @DisplayName("short content → verbatim preview (no truncation)")
        void shortContentPreview() {
            User owner = user("owner4");
            Chat c = chat(ChatType.PRIVATE);
            Message last = Message.builder().chat(c).sender(user("s4")).content("hi there")
                    .messageType(MessageType.TEXT).build();
            stubGetUserChats(owner, c, last, 1L);

            AdminChatView v = service.getUserChats(owner.getUuid().toString()).get(0);
            assertThat(v.getLastMessagePreview()).isEqualTo("hi there");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("reconcileStorage — populated flags / null-relation / edge references / cache-hit")
    class ReconcileStorageEdgeBranches {

        private MediaStorage.StoredObject obj(String key, long size, String ct, Instant lm) {
            return new MediaStorage.StoredObject("/media/" + key, key, size, lm, ct);
        }

        @Test
        @DisplayName("a fully-populated linked message renders forwarded/edited/expired/deleted + all timestamps")
        void richPopulatedLinkedAttachment() {
            User sender = user("rs");
            User receiver = user("rr");
            Chat c = chat(ChatType.PRIVATE);
            c.getMembers().add(ChatMember.builder().chat(c).user(sender).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(receiver).build());
            Message m = Message.builder().chat(c).sender(sender).messageType(MessageType.IMAGE)
                    .content("caption").build();
            m.setId(idSeq++); m.setUuid(UUID.randomUUID());
            m.setForwarded(true);
            m.setEdited(true);
            m.setModerationStatus(ModerationStatus.RELEASED);
            m.setSelfDestructSeconds(10);
            m.setSelfDestructExpired(true);
            m.setDeleted(true);
            m.setCreatedAt(Instant.now());
            MessageAttachment a = MessageAttachment.builder().message(m).fileName("img.jpg")
                    .fileUrl("/media/conversations/rp/img.jpg").mimeType("image/jpeg").fileSize(10L)
                    .thumbnailUrl("/media/conversations/rp/img-thumb.jpg").build();
            a.setId(idSeq++); a.setUuid(UUID.randomUUID());
            a.setCreatedAt(Instant.now()); a.setUpdatedAt(Instant.now());
            when(attachmentRepository.findAll()).thenReturn(List.of(a));
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/rp/img.jpg", 10, "image/jpeg", Instant.now())));

            var item = service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root")
                    .getItems().get(0);

            assertThat(item.isForwarded()).isTrue();
            assertThat(item.isEdited()).isTrue();
            assertThat(item.isDeleted()).isTrue();
            assertThat(item.isSelfDestructExpired()).isTrue();
            assertThat(item.getModerationStatus()).isEqualTo("RELEASED");
            assertThat(item.getCaption()).isEqualTo("caption");
            assertThat(item.getSentAt()).isNotNull();
            assertThat(item.getCreatedAt()).isNotNull();
            assertThat(item.getUpdatedAt()).isNotNull();
            assertThat(item.getReceivers())
                    .extracting(AdminStorageObjectView.SharedUser::getUsername)
                    .containsExactly("rr");
        }

        @Test
        @DisplayName("linked message with null chat/sender/type and null attachment fields → null metadata, id fallback")
        void minimalNullLinkedAttachment() {
            Message m = Message.builder().build();
            m.setMessageType(null);
            m.setModerationStatus(null);
            m.setReactions(null);
            MessageAttachment a = MessageAttachment.builder().message(m)
                    .fileName("x.bin").fileUrl("/media/conversations/mn/x.bin").fileSize(null).build();
            a.setId(55L); // no uuid → id fallback
            when(attachmentRepository.findAll()).thenReturn(List.of(a));
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/mn/x.bin", 10, null, Instant.now())));

            var item = service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root")
                    .getItems().get(0);

            assertThat(item.isLinked()).isTrue();
            assertThat(item.getChatId()).isNull();
            assertThat(item.getSenderUsername()).isNull();
            assertThat(item.getMessageType()).isNull();
            assertThat(item.getCaption()).isNull();
            assertThat(item.getReceivers()).isEmpty();
            assertThat(item.getFileSize()).isZero();
            assertThat(item.getAttachmentId()).isEqualTo("55");
        }

        @Test
        @DisplayName("linked message with a null sender includes every member; a null-uuid member yields a null id")
        void nullSenderIncludesAllMembers() {
            User m1 = user("mm1"); m1.setUuid(null);
            User m2 = user("mm2");
            Chat c = chat(ChatType.PRIVATE);
            c.getMembers().add(ChatMember.builder().chat(c).user(m1).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(m2).build());
            Message m = Message.builder().chat(c).sender(null).messageType(MessageType.IMAGE).build();
            m.setId(idSeq++); m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder().message(m).fileName("p.jpg")
                    .fileUrl("/media/conversations/ns/p.jpg").mimeType("image/jpeg").fileSize(9L).build();
            a.setId(idSeq++); a.setUuid(UUID.randomUUID());
            when(attachmentRepository.findAll()).thenReturn(List.of(a));
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/ns/p.jpg", 9, "image/jpeg", Instant.now())));

            var item = service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root")
                    .getItems().get(0);

            assertThat(item.getReceivers())
                    .extracting(AdminStorageObjectView.SharedUser::getUsername)
                    .containsExactlyInAnyOrder("mm1", "mm2"); // sender null → every member included
            assertThat(item.getReceivers().stream()
                    .filter(su -> "mm1".equals(su.getUsername())).findFirst().orElseThrow().getId()).isNull();
        }

        @Test
        @DisplayName("undecodable / foreign / message-less references are skipped; null-lastModified orphan tolerated")
        void edgeReferencesAndNullLastModified() {
            Message m = Message.builder().build();
            MessageAttachment noKey = MessageAttachment.builder().message(m)
                    .fileUrl("foreign-ref-no-slash").thumbnailUrl("thumb-ref-no-slash").build();
            MessageAttachment nullMsg = MessageAttachment.builder().message(null)
                    .fileUrl("another-foreign").build();
            when(attachmentRepository.findAll()).thenReturn(List.of(noKey, nullMsg));
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/z/only.jpg", 12, "image/jpeg", null))); // orphan, null lastModified

            var item = service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root")
                    .getItems().get(0);

            assertThat(item.isOrphan()).isTrue();     // chat-media category, no DB link
            assertThat(item.getLastModified()).isNull();
            assertThat(item.getKind()).isEqualTo("image");
        }

        @Test
        @DisplayName("a second call within the TTL is served from the in-memory snapshot")
        void cacheHitReusesSnapshot() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("profiles/p.png", 5, "image/png", Instant.now())));

            service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root");
            service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root");

            verify(mediaStorage, Mockito.times(1)).list(null);
            verify(attachmentRepository, Mockito.times(1)).findAll();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("kindForLinked (mime-driven arms + voice variants) & categoryOf (no-slash key)")
    class KindForLinkedArms {

        private MediaStorage.StoredObject obj(String key) {
            return new MediaStorage.StoredObject("/media/" + key, key, 10, Instant.now(), null);
        }

        private MessageAttachment linked(String key, MessageType type, String mime, String fileName) {
            Chat c = chat(ChatType.PRIVATE);
            Message m = Message.builder().chat(c).sender(user("kfl")).messageType(type).build();
            m.setId(idSeq++); m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder().message(m).fileName(fileName)
                    .fileUrl("/media/" + key).mimeType(mime).fileSize(10L).build();
            a.setId(idSeq++); a.setUuid(UUID.randomUUID());
            return a;
        }

        @Test
        @DisplayName("null message-type falls back to mime: audio→voice/audio, video, image; ptt/opus/ogg/voice-prefix read as voice")
        void mimeDrivenClassification() {
            List<MessageAttachment> atts = List.of(
                    linked("conversations/k/a1", null, "audio/opus", "clip.opus"),  // voice (opus)
                    linked("conversations/k/a2", null, "audio/ogg", "clip.ogg"),    // voice (ogg)
                    linked("conversations/k/a3", null, "audio/aac", "ptt-note"),    // voice (ptt name)
                    linked("conversations/k/a4", null, "audio/mp4", "voicememo"),   // voice (voice prefix)
                    linked("conversations/k/a5", null, "video/mp4", "vid"),         // video (mime)
                    linked("conversations/k/a6", null, "image/png", "img"));        // image (mime)
            when(attachmentRepository.findAll()).thenReturn(atts);
            when(mediaStorage.list(null)).thenReturn(List.of(
                    obj("conversations/k/a1"), obj("conversations/k/a2"), obj("conversations/k/a3"),
                    obj("conversations/k/a4"), obj("conversations/k/a5"), obj("conversations/k/a6"),
                    obj("loosefile"))); // no-slash key → categoryOf "other"

            var res = service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root");

            assertThat(res.getCounts().getVoice()).isEqualTo(4L);
            assertThat(res.getCounts().getVideo()).isEqualTo(1L);
            assertThat(res.getCounts().getImage()).isEqualTo(1L);
            assertThat(res.getItems()).extracting(
                    AdminStorageObjectView::getCategory).contains("other");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toView / getUserFull (null-uuid, blank-google, null-collection, non-null sub-branches)")
    class ViewNullBranches {

        @Test
        @DisplayName("null uuid, blank googleId, null roles/interests render as null/false")
        void nullUuidBlankGoogleNullCollections() {
            UUID lookup = UUID.randomUUID();
            User u = user("nb");
            u.setUuid(null);
            u.setGoogleId("   ");    // blank → hasGoogleLinked false
            u.setRoles(null);
            u.setInterests(null);
            when(userRepository.findByUuid(lookup)).thenReturn(Optional.of(u));

            AdminUserView v = service.getUser(lookup.toString());

            assertThat(v.getId()).isNull();
            assertThat(v.getRoles()).isNull();
            assertThat(v.getInterests()).isNull();
            assertThat(v.isHasGoogleLinked()).isFalse();
        }

        @Test
        @DisplayName("null messagingPrivacy + null lastSeenAt exercise the null sub-branches inside the present lambdas")
        void settingsPrivacyAndPresenceLastSeenNull() {
            // The DTO defaults are non-null (messagingPrivacy=EVERYONE, lastSeenAt=now), so the
            // uncovered arms are the NULL branches: force both to null.
            User u = user("gf");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            UserSetting s = UserSetting.builder().user(u).theme("DARK").build();
            s.setMessagingPrivacy(null);
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.of(s));
            UserPresence p = UserPresence.builder().user(u).status("ONLINE").build();
            p.setLastSeenAt(null);
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.of(p));

            AdminUserFullView v = service.getUserFull(u.getUuid().toString());

            assertThat(v.getSettings().get("theme")).isEqualTo("DARK");     // present lambda ran
            assertThat(v.getSettings().get("messagingPrivacy")).isNull();   // null sub-branch
            assertThat(v.getPresence().get("status")).isEqualTo("ONLINE");  // present lambda ran
            assertThat(v.getPresence().get("lastSeenAt")).isNull();         // null sub-branch
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChatMessages (message null-field branches)")
    class GetChatMessagesEdge {

        @Test
        @DisplayName("null uuid/sender/type + null attachments + null moderation + createdAt present")
        void messageNullFieldBranches() {
            Chat c = chat(ChatType.PRIVATE);
            Message m = Message.builder().chat(c).sender(null).content("cipher")
                    .messageType(null).build();
            m.setId(41L);            // no uuid → id fallback
            m.setModerationStatus(null);
            m.setAttachments(null);
            m.setCreatedAt(Instant.now());
            when(chatRepository.findByUuidWithMembers(c.getUuid())).thenReturn(Optional.of(c));
            when(messageRepository.findByChat(eq(c), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(messageMapper.resolveMessageStatus(m)).thenReturn("SENT");

            var item = service.getChatMessages(c.getUuid().toString(), 0, 50, "root").getItems().get(0);

            assertThat(item.getId()).isEqualTo("41");
            assertThat(item.getSenderUsername()).isNull();
            assertThat(item.getSenderId()).isNull();
            assertThat(item.getType()).isEqualTo("TEXT"); // null type → "TEXT"
            assertThat(item.getMediaUrl()).isNull();       // attachments null
            assertThat(item.getModerationStatus()).isNull();
            assertThat(item.getCreatedAt()).isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listAudit (null-uuid admin resolution, id/createdAt fallbacks, garbage/blank date filters)")
    class ListAuditEdge {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("log without uuid uses id; admin whose uuid is null is dropped; unparseable/blank dates ignored")
        void nullUuidAndGarbageDates() {
            AdminAuditLog logRow = AdminAuditLog.builder()
                    .adminUsername("root").action("BAN_USER").targetType("USER").targetId("u1").build();
            logRow.setId(9L);        // no uuid → id fallback
            logRow.setCreatedAt(Instant.now());
            when(auditRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(logRow)));
            User admin = user("root");
            admin.setUuid(null);     // → filtered out of the uuid map
            when(userRepository.findByUsernameIn(Set.of("root"))).thenReturn(List.of(admin));

            PaginatedResponse<AdminAuditView> res =
                    service.listAudit(null, null, null, "not-a-date", "   ", 0, 20);

            var item = res.getItems().get(0);
            assertThat(item.getId()).isEqualTo("9");
            assertThat(item.getAdminId()).isNull();
            assertThat(item.getCreatedAt()).isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toPostView (null-media, null-audience, author-uuid, createdAt branches)")
    class PostViewEdge {

        @Test
        @DisplayName("null media list, null audience, author without uuid, createdAt present")
        void postEdgeBranches() {
            User author = user("pe"); author.setUuid(null);
            Post p = Post.builder().user(author).content("c").build();
            p.setId(idSeq++);
            p.setMedia(null);
            p.setAudience(null);
            p.setCreatedAt(Instant.now());
            when(postRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(p)));
            when(postLikeRepository.countByPost(p)).thenReturn(0L);
            when(postCommentRepository.countForPost(p)).thenReturn(0L);

            AdminPostView v = service.listPosts(0, 20).getItems().get(0);

            assertThat(v.getMedia()).isEmpty();
            assertThat(v.getAudience()).isNull();
            assertThat(v.getAuthorId()).isNull();
            assertThat(v.getAuthorUsername()).isEqualTo("pe");
            assertThat(v.getCreatedAt()).isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toAttachmentView (null-sender inclusion, null members, null chat uuid/type, createdAt)")
    class AttachmentViewEdge {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("null sender → all members shared; null-uuid member, chat uuid/type null; createdAt present")
        void nullSenderAllMembers() {
            User mem1 = user("am1"); mem1.setUuid(null);
            User mem2 = user("am2");
            Chat c = Chat.builder().chatType(null).name("Chat").build();
            c.setId(idSeq++);        // no uuid
            c.getMembers().add(ChatMember.builder().chat(c).user(mem1).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(mem2).build());
            c.getMembers().add(ChatMember.builder().chat(c).user(null).build());
            Message m = Message.builder().chat(c).sender(null).messageType(MessageType.IMAGE).build();
            m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder().message(m).fileName("f.jpg").fileUrl("/u.jpg").build();
            a.setUuid(UUID.randomUUID());
            a.setCreatedAt(Instant.now());
            when(attachmentRepository.findForAdmin(eq(null), eq(MessageType.IMAGE), eq(false), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(a)));

            var item = service.getAttachments(null, "image", false, 0, 20, "root").getItems().get(0);

            assertThat(item.getSharedWith())
                    .extracting(AdminAttachmentView.SharedUser::getUsername)
                    .containsExactlyInAnyOrder("am1", "am2");
            assertThat(item.getChatId()).isNull();   // chat has no uuid
            assertThat(item.getChatType()).isNull(); // chatType null
            assertThat(item.getCreatedAt()).isNotNull();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("null members list → empty sharedWith; sender present but without uuid → null senderId")
        void nullMembersSenderNoUuid() {
            User sender = user("as"); sender.setUuid(null);
            Chat c = chat(ChatType.PRIVATE);
            c.setMembers(null);
            Message m = Message.builder().chat(c).sender(sender).messageType(MessageType.IMAGE).build();
            m.setUuid(UUID.randomUUID());
            MessageAttachment a = MessageAttachment.builder().message(m).fileName("f").fileUrl("/u").build();
            a.setUuid(UUID.randomUUID());
            when(attachmentRepository.findForAdmin(eq(null), eq(null), eq(false), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(a)));

            var item = service.getAttachments(null, null, false, 0, 20, "root").getItems().get(0);

            assertThat(item.getSharedWith()).isEmpty();
            assertThat(item.getSenderUsername()).isEqualTo("as");
            assertThat(item.getSenderId()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toReportView (id/createdAt/session-uuid fallbacks) + getReport history/related-chat")
    class ReportViewEdgeAndHistory {

        @Test
        @DisplayName("report without uuid uses id; createdAt present; session without uuid → null session id")
        void reportIdAndSessionFallbacks() {
            MatchReport r = new MatchReport();
            r.setId(71L);            // no uuid → id fallback
            r.setReason("spam");
            r.setStatus("PENDING");
            r.setCreatedAt(Instant.now());
            r.setReviewedAt(Instant.now());
            User reporter = user("erp");
            User reported = user("erd");
            r.setReporter(reporter);
            r.setReported(reported);
            MatchSession s = MatchSession.builder()
                    .host(user("eh")).build(); // no uuid, no peer
            r.setSession(s);
            when(matchReportRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(r)));
            when(matchReportRepository.countByReportedId(reported.getId())).thenReturn(1L);
            when(matchReportRepository.countByReporterId(reporter.getId())).thenReturn(1L);
            when(matchReportRepository.countByReporterIdAndReportedId(reporter.getId(), reported.getId()))
                    .thenReturn(0L);

            AdminReportView v = service.listReports(null, 0, 20).getItems().get(0);

            assertThat(v.getId()).isEqualTo("71");
            assertThat(v.getCreatedAt()).isNotNull();
            assertThat(v.getSession().getId()).isNull();
        }

        @Test
        @DisplayName("getReport builds history (uuid/reporter/createdAt present + all-null) and sets relatedChatId")
        void getReportHistoryAndRelatedChat() {
            MatchReport r = new MatchReport();
            r.setId(idSeq++); r.setUuid(UUID.randomUUID());
            r.setStatus("PENDING");
            User reporter = user("hrp");
            User reported = user("hrd");
            r.setReporter(reporter); r.setReported(reported);
            when(matchReportRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            MatchReport h1 = new MatchReport();
            h1.setId(idSeq++); h1.setUuid(UUID.randomUUID());
            h1.setReason("r1"); h1.setStatus("PENDING");
            h1.setReporter(user("h1r")); h1.setCreatedAt(Instant.now());
            MatchReport h2 = new MatchReport();
            h2.setId(88L);          // no uuid, reporter null, createdAt null
            h2.setReason("r2"); h2.setStatus("PENDING");
            when(matchReportRepository.findByReportedId(eq(reported.getId()), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(h1, h2)));
            when(chatRepository.findChatsByUser(reported)).thenReturn(List.of());
            Chat evidence = chat(ChatType.PRIVATE); // has uuid
            when(chatRepository.findPrivateChatBetweenUsers(reporter.getId(), reported.getId()))
                    .thenReturn(List.of(evidence));

            AdminReportView v = service.getReport(r.getUuid().toString());

            assertThat(v.getHistory()).hasSize(2);
            assertThat(v.getHistory().get(1).getId()).isEqualTo("88");
            assertThat(v.getHistory().get(1).getReporterUsername()).isNull();
            assertThat(v.getRelatedChatId()).isEqualTo(evidence.getUuid().toString());
        }

        @Test
        @DisplayName("evidence chat without a uuid leaves relatedChatId unset")
        void getReportEvidenceNullUuid() {
            MatchReport r = new MatchReport();
            r.setId(idSeq++); r.setUuid(UUID.randomUUID()); r.setStatus("PENDING");
            User reporter = user("nrp");
            User reported = user("nrd");
            r.setReporter(reporter); r.setReported(reported);
            when(matchReportRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));
            when(matchReportRepository.findByReportedId(eq(reported.getId()), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            when(chatRepository.findChatsByUser(reported)).thenReturn(List.of());
            Chat evidence = Chat.builder().chatType(ChatType.PRIVATE).build();
            evidence.setId(idSeq++); // no uuid
            when(chatRepository.findPrivateChatBetweenUsers(reporter.getId(), reported.getId()))
                    .thenReturn(List.of(evidence));

            AdminReportView v = service.getReport(r.getUuid().toString());
            assertThat(v.getRelatedChatId()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("feedback parse fallbacks + toFeedbackView null/edge branches")
    class FeedbackEdge {

        @Test
        @DisplayName("unparseable type AND status both fall through to findAll")
        void invalidTypeAndStatusFallThrough() {
            when(feedbackRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.listFeedback("BOGUS_TYPE", "BOGUS_STATUS", 0, 20);

            verify(feedbackRepository).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("null user → null author; null uuid/type/status → fallbacks; author uuid null; createdAt present")
        void feedbackViewNullBranches() {
            Feedback f1 = Feedback.builder().user(null).comment("c").build();
            f1.setId(31L);           // no uuid → id maps to null (no numeric fallback in toFeedbackView)
            f1.setType(null);
            f1.setStatus(null);
            f1.setCreatedAt(Instant.now());
            User u = user("fu"); u.setUuid(null);
            Feedback f2 = Feedback.builder().user(u).comment("d")
                    .type(FeedbackType.MANUAL).status(FeedbackStatus.NEW).build();
            f2.setId(idSeq++); f2.setUuid(UUID.randomUUID());
            when(feedbackRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(f1, f2)));

            List<AdminFeedbackView> items = service.listFeedback(null, null, 0, 20).getItems();

            AdminFeedbackView v1 = items.get(0);
            assertThat(v1.getAuthor()).isNull();
            assertThat(v1.getId()).isNull();
            assertThat(v1.getType()).isNull();
            assertThat(v1.getStatus()).isNull();
            assertThat(v1.getCreatedAt()).isNotNull();
            assertThat(items.get(1).getAuthor().getId()).isNull(); // user uuid null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getTimeseries (range / metric / interval switch coverage)")
    class TimeseriesSwitches {

        @Test
        @DisplayName("every named range resolves and buckets without error")
        void rangeArms() {
            lenient().when(messageRepository.findMessageTimesSince(any())).thenReturn(List.of());
            for (String r : List.of("1h", "6h", "12h", "24h", "1d", "7d", "1w",
                    "90d", "3m", "1y", "365d", "30d", "1m", "weird")) {
                AdminTimeseriesResult res = service.getTimeseries(null, r, null, null, null);
                assertThat(res).isNotNull();
                assertThat(res.getMetric()).isEqualTo("messages");
            }
        }

        @Test
        @DisplayName("every metric routes to its repository")
        void metricArms() {
            lenient().when(userRepository.findSignupTimesSince(any())).thenReturn(List.of());
            lenient().when(attachmentRepository.findAttachmentTimesSince(any())).thenReturn(List.of());
            lenient().when(postRepository.findTimesSince(any())).thenReturn(List.of());
            lenient().when(storyRepository.findTimesSince(any())).thenReturn(List.of());
            lenient().when(profileViewRepository.findTimesSince(any())).thenReturn(List.of());
            lenient().when(userFollowRepository.findTimesSince(any())).thenReturn(List.of());
            lenient().when(friendRequestRepository.findTimesSince(any())).thenReturn(List.of());
            lenient().when(matchReportRepository.findTimesSince(any())).thenReturn(List.of());
            lenient().when(reactionRepository.findTimesSince(any())).thenReturn(List.of());
            lenient().when(messageRepository.findMessageTimesSince(any())).thenReturn(List.of());
            String from = Instant.now().minusSeconds(7200).toString();
            String to = Instant.now().toString();
            for (String metric : List.of("signups", "users", "attachments", "media", "posts", "stories",
                    "profileviews", "profile_views", "views", "follows", "friendrequests", "friend_requests",
                    "friends", "reports", "reactions", "messages", "somethingelse")) {
                AdminTimeseriesResult res = service.getTimeseries(metric, null, "1h", from, to);
                assertThat(res.getMetric()).isEqualTo(metric.toLowerCase());
            }
        }

        @Test
        @DisplayName("interval overrides + bucket descriptions across custom windows (incl. snap-to-1w)")
        void intervalArms() {
            lenient().when(messageRepository.findMessageTimesSince(any())).thenReturn(List.of());
            String from = Instant.now().minusSeconds(90L * 24 * 3600).toString();
            String to = Instant.now().toString();
            for (String iv : List.of("5m", "15m", "30m", "1h", "6h", "12h", "1d", "1w", "bogus")) {
                assertThat(service.getTimeseries("messages", null, iv, from, to)).isNotNull();
            }
            // huge span + unknown interval → snapInterval falls through to "1w"
            String longFrom = Instant.now().minusSeconds(400L * 24 * 3600).toString();
            assertThat(service.getTimeseries("messages", null, "bogus", longFrom, to)).isNotNull();
        }

        @Test
        @DisplayName("non-custom range with an interval override uses the override bucket")
        void rangeWithIntervalOverride() {
            lenient().when(messageRepository.findMessageTimesSince(any())).thenReturn(List.of());
            assertThat(service.getTimeseries("messages", "7d", "1h", null, null)).isNotNull();
        }

        @Test
        @DisplayName("reversed and unparseable custom bounds are clamped/fallen-back without error")
        void reversedAndUnparseableBounds() {
            lenient().when(messageRepository.findMessageTimesSince(any())).thenReturn(List.of());
            String earlier = Instant.now().minusSeconds(3600).toString();
            String later = Instant.now().toString();
            assertThat(service.getTimeseries("messages", null, "5m", later, earlier)).isNotNull(); // from>to → clamp
            assertThat(service.getTimeseries("messages", null, "5m", "garbage", "garbage")).isNotNull(); // fallback
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getMediaOwnership")
    class GetMediaOwnership {

        @Test
        @DisplayName("computes totals, per-context/type buckets, top uploaders, recent uploads + audits")
        void computesOwnershipAnalytics() {
            User uploader = user("shutterbug");
            when(mediaAssetRepository.count()).thenReturn(10L);
            when(mediaAssetRepository.sumBytes()).thenReturn(1000L);
            when(mediaAssetRepository.countByOwnerIsNull()).thenReturn(2L);
            when(mediaAssetRepository.countDistinctOwners()).thenReturn(3L);
            when(mediaAssetRepository.countByContext(MediaContext.STRANGER)).thenReturn(4L);
            when(mediaAssetRepository.sumBytesByContext(MediaContext.STRANGER)).thenReturn(400L);
            when(mediaAssetRepository.countByContext(MediaContext.LOBBY)).thenReturn(1L);
            when(mediaAssetRepository.countByContext(MediaContext.CONVERSATION)).thenReturn(3L);
            when(mediaAssetRepository.aggregateByContext()).thenReturn(List.of(
                    new Object[]{MediaContext.STRANGER, 4L, 400L},
                    new Object[]{MediaContext.CONVERSATION, 3L, 300L}));
            when(mediaAssetRepository.aggregateByType()).thenReturn(List.of(
                    new Object[]{"image", 6L, 600L},
                    new Object[]{"video", 4L, 400L}));
            when(mediaAssetRepository.topUploaders(
                    eq(MediaContext.STRANGER), any(Pageable.class)))
                    .thenReturn(List.<Object[]>of(new Object[]{uploader.getId(), 7L, 700L, 2L}));
            when(userRepository.findAllById(List.of(uploader.getId()))).thenReturn(List.of(uploader));
            when(mediaAssetRepository.recentWithOwner(any(Pageable.class))).thenReturn(List.of(
                    mediaAsset(uploader, MediaContext.STRANGER, "strangers/a.jpg", "image", 100L)));
            when(mediaAssetRepository.findUploadTimesSince(any())).thenReturn(List.of(Instant.now()));

            var res = service.getMediaOwnership("30d", "root");

            assertThat(res.getTotalAssets()).isEqualTo(10L);
            assertThat(res.getTotalBytes()).isEqualTo(1000L);
            assertThat(res.getAttributedAssets()).isEqualTo(8L);   // 10 total − 2 unattributed
            assertThat(res.getUnattributedAssets()).isEqualTo(2L);
            assertThat(res.getUploaderCount()).isEqualTo(3L);
            assertThat(res.getStrangerAssets()).isEqualTo(4L);
            assertThat(res.getStrangerBytes()).isEqualTo(400L);
            assertThat(res.getByContext()).extracting(
                    AdminMediaOwnershipResponse.Bucket::getLabel)
                    .contains("STRANGER", "CONVERSATION");
            assertThat(res.getByType()).extracting(
                    AdminMediaOwnershipResponse.Bucket::getLabel)
                    .contains("image", "video");
            assertThat(res.getTopUploaders()).hasSize(1);
            assertThat(res.getTopUploaders().get(0).getUsername()).isEqualTo("shutterbug");
            assertThat(res.getTopUploaders().get(0).getStrangerCount()).isEqualTo(2L);
            assertThat(res.getRecent()).hasSize(1);
            assertThat(res.getRecent().get(0).isStrangerMode()).isTrue();
            assertThat(res.getUploadsSeries()).isNotEmpty();
            verify(auditLogger).write(eq("root"), eq("VIEW_MEDIA_STATS"), eq("MEDIA"), eq("30d"), any());
        }

        @Test
        @DisplayName("empty ledger yields zeroed totals and empty breakdowns (no NPE)")
        void emptyLedger() {
            when(mediaAssetRepository.count()).thenReturn(0L);
            when(mediaAssetRepository.sumBytes()).thenReturn(0L);
            when(mediaAssetRepository.countByOwnerIsNull()).thenReturn(0L);
            when(mediaAssetRepository.countDistinctOwners()).thenReturn(0L);
            when(mediaAssetRepository.aggregateByContext()).thenReturn(List.of());
            when(mediaAssetRepository.aggregateByType()).thenReturn(List.of());
            when(mediaAssetRepository.topUploaders(any(), any(Pageable.class))).thenReturn(List.of());
            when(userRepository.findAllById(List.of())).thenReturn(List.of());
            when(mediaAssetRepository.recentWithOwner(any(Pageable.class))).thenReturn(List.of());
            when(mediaAssetRepository.findUploadTimesSince(any())).thenReturn(List.of());

            var res = service.getMediaOwnership("30d", "root");

            assertThat(res.getTotalAssets()).isZero();
            assertThat(res.getByContext()).isEmpty();
            assertThat(res.getTopUploaders()).isEmpty();
            assertThat(res.getRecent()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserMedia")
    class GetUserMedia {

        @Test
        @DisplayName("maps a user's uploads + per-context summary and audits VIEW_USER_MEDIA")
        void mapsUserMedia() {
            User u = user("mia");
            when(userRepository.findByUuid(u.getUuid())).thenReturn(Optional.of(u));
            when(mediaAssetRepository.findByOwner_IdOrderByCreatedAtDesc(eq(u.getId()), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(
                            mediaAsset(u, MediaContext.STRANGER, "strangers/a.jpg", "image", 50L))));
            when(mediaAssetRepository.countByOwner_Id(u.getId())).thenReturn(1L);
            when(mediaAssetRepository.sumBytesByOwner(u.getId())).thenReturn(50L);
            when(mediaAssetRepository.aggregateByContextForOwner(u.getId())).thenReturn(List.<Object[]>of(
                    new Object[]{MediaContext.STRANGER, 1L, 50L}));

            var res = service.getUserMedia(u.getUuid().toString(), 0, 24, "root");

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getContext()).isEqualTo("STRANGER");
            assertThat(res.getItems().get(0).isStrangerMode()).isTrue();
            assertThat(res.getItems().get(0).getOwnerUsername()).isEqualTo("mia");
            assertThat(res.getItems().get(0).getKind()).isEqualTo("image");
            assertThat(res.getTotal()).isEqualTo(1L);
            assertThat(res.getTotalBytes()).isEqualTo(50L);
            assertThat(res.getByContext()).extracting(
                    AdminMediaOwnershipResponse.Bucket::getLabel)
                    .containsExactly("STRANGER");
            verify(auditLogger).write(eq("root"), eq("VIEW_USER_MEDIA"), eq("MEDIA"),
                    eq(u.getUuid().toString()), any());
        }

        @Test
        @DisplayName("absent user → NotFoundException TM_064")
        void absentUser() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUserMedia(id.toString(), 0, 24, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_064"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getChatMedia")
    class GetChatMedia {

        @Test
        @DisplayName("sources media from the chat's MessageAttachments with sender as owner; audits")
        void mapsChatMedia() {
            Chat c = chat(ChatType.PRIVATE);
            User sender = user("ned");
            MessageAttachment a = attachment(
                    c, sender, MessageType.IMAGE, "image/jpeg", "pic.jpg", "conversations/c/pic.jpg");
            when(chatRepository.findByUuidWithMembers(c.getUuid())).thenReturn(Optional.of(c));
            when(attachmentRepository.findByChatForAdmin(eq(c.getId()), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(a)));
            when(attachmentRepository.countByChatForAdmin(c.getId())).thenReturn(1L);
            when(attachmentRepository.sumFileSizeByChat(c.getId())).thenReturn(20L);

            var res = service.getChatMedia(c.getUuid().toString(), 0, 24, "root");

            assertThat(res.getItems()).hasSize(1);
            var item = res.getItems().get(0);
            assertThat(item.getKind()).isEqualTo("image");
            assertThat(item.getContext()).isEqualTo("CONVERSATION");
            assertThat(item.getContextId()).isEqualTo(c.getUuid().toString());
            assertThat(item.getOwnerUsername()).isEqualTo("ned");
            assertThat(item.getOriginalFileName()).isEqualTo("pic.jpg");   // decrypt is identity in tests
            assertThat(item.isStrangerMode()).isFalse();
            assertThat(res.getTotal()).isEqualTo(1L);
            assertThat(res.getTotalBytes()).isEqualTo(20L);
            verify(auditLogger).write(eq("root"), eq("VIEW_CHAT_MEDIA"), eq("MEDIA"),
                    eq(c.getUuid().toString()), any());
        }

        @Test
        @DisplayName("absent chat → NotFoundException TM_121")
        void absentChat() {
            UUID id = UUID.randomUUID();
            when(chatRepository.findByUuidWithMembers(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChatMedia(id.toString(), 0, 24, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }

        @Test
        @DisplayName("malformed chat uuid → NotFoundException TM_121")
        void malformedUuid() {
            assertThatThrownBy(() -> service.getChatMedia("xx", 0, 24, "root"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_121"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("reconcileStorage — orphan owner attribution (media_assets + path + unrecorded)")
    class ReconcileStorageOrphanOwner {

        private MediaStorage.StoredObject obj(String key, String ct) {
            return new MediaStorage.StoredObject("/media/" + key, key, 10, Instant.now(), ct);
        }

        private AdminStorageObjectView only() {
            return service.getStorageObjects(null, null, null, false, null, "newest", 0, 50, "root")
                    .getItems().get(0);
        }

        @Test
        @DisplayName("orphan with an upload record → owner + UPLOAD_RECORD + strangerMode")
        void attributedFromUploadRecord() {
            User uploader = user("ghost");
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(obj("strangers/x.jpg", "image/jpeg")));
            when(mediaAssetRepository.findByStorageKeyIn(any())).thenReturn(List.of(
                    mediaAsset(uploader, MediaContext.STRANGER, "strangers/x.jpg", "image", 10L)));

            var item = only();
            assertThat(item.isOrphan()).isTrue();
            assertThat(item.getOwnerSource()).isEqualTo("UPLOAD_RECORD");
            assertThat(item.isStrangerMode()).isTrue();
            assertThat(item.getSenderUsername()).isEqualTo("ghost");
        }

        @Test
        @DisplayName("legacy lobby orphan (no record) → owner parsed from the path, STORAGE_PATH")
        void attributedFromPath() {
            User uploader = user("lobbyist");
            String key = "lobby/" + uploader.getUuid() + "/y.mp4";
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(obj(key, "video/mp4")));
            when(mediaAssetRepository.findByStorageKeyIn(any())).thenReturn(List.of());
            when(userRepository.findByUuid(uploader.getUuid())).thenReturn(Optional.of(uploader));

            var item = only();
            assertThat(item.getOwnerSource()).isEqualTo("STORAGE_PATH");
            assertThat(item.getSenderUsername()).isEqualTo("lobbyist");
            assertThat(item.isStrangerMode()).isFalse();
        }

        @Test
        @DisplayName("legacy stranger orphan (no record, no path owner) → UNRECORDED, strangerMode, no owner")
        void unrecordedStranger() {
            when(attachmentRepository.findAll()).thenReturn(List.of());
            when(mediaStorage.list(null)).thenReturn(List.of(obj("strangers/z.jpg", "image/jpeg")));
            when(mediaAssetRepository.findByStorageKeyIn(any())).thenReturn(List.of());

            var item = only();
            assertThat(item.getOwnerSource()).isEqualTo("UNRECORDED");
            assertThat(item.isStrangerMode()).isTrue();
            assertThat(item.getSenderUsername()).isNull();
        }

        @Test
        @DisplayName("linked STRANGER chat media → strangerMode + MESSAGE_ATTACHMENT owner source")
        void linkedStrangerFlags() {
            User sender = user("anon");
            Chat c = chat(ChatType.STRANGER);
            MessageAttachment a = attachment(
                    c, sender, MessageType.IMAGE, "image/jpeg", "s.jpg", "strangers/s.jpg");
            when(attachmentRepository.findAll()).thenReturn(List.of(a));
            when(mediaStorage.list(null)).thenReturn(List.of(obj("strangers/s.jpg", "image/jpeg")));

            var item = only();
            assertThat(item.isLinked()).isTrue();
            assertThat(item.isStrangerMode()).isTrue();
            assertThat(item.getOwnerSource()).isEqualTo("MESSAGE_ATTACHMENT");
            assertThat(item.getSenderUsername()).isEqualTo("anon");
        }
    }
}
