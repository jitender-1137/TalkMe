package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConversationSummaryResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageAttachmentRepository;
import com.chat.talkMe.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ConversationSummaryServiceImpl} — the "Our Story" 1:1
 * summary engine (feature #3.3). No Spring context, no DB, no Redis: every collaborator is a
 * Mockito mock and the arithmetic/headline logic is exercised directly.
 *
 * <p>Coverage:
 * <ul>
 *   <li>UUID / chat-existence gate ({@code NotFoundException TM_024}).</li>
 *   <li>Membership (IDOR) gate: no member row, a member who has left, a banned member
 *       ({@code ForbiddenException TM_026}).</li>
 *   <li>1:1-only rule: a multi-party chat ({@code GROUP}) is rejected ({@code ForbiddenException TM_026}).</li>
 *   <li>Happy path: counts flow through to totalMessages/myMessages/theirMessages, the other
 *       participant is resolved onto the card, and a non-null headline is generated.</li>
 *   <li>Shared-interest intersection: only the overlapping {@link Interest} enums, prettified.</li>
 *   <li>Resilience: an attachment/active-days count that blows up is swallowed to 0 (safeCount).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationSummaryServiceImpl (unit)")
class ConversationSummaryServiceImplTest {

    private static final String CHAT_UUID = "11111111-1111-1111-1111-111111111111";
    private static final long ME_ID = 1L;
    private static final long OTHER_ID = 2L;

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageAttachmentRepository messageAttachmentRepository;

    @InjectMocks
    private ConversationSummaryServiceImpl service;

    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = User.builder()
                .username("alice").email("a@e.com").name("Alice")
                .interests(Set.of(Interest.MUSIC, Interest.GAMING, Interest.TRAVEL))
                .build();
        me.setId(ME_ID);

        other = User.builder()
                .username("bob").email("b@e.com").name("Bob")
                .profileImage("https://cdn.example.com/bob.png")
                .interests(Set.of(Interest.GAMING, Interest.TRAVEL, Interest.SPORTS))
                .build();
        other.setId(OTHER_ID);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * A live 1:1 chat matching CHAT_UUID (not deleted). Chat's @Builder omits BaseEntity fields.
     */
    private static Chat privateChat() {
        Chat chat = Chat.builder().chatType(ChatType.PRIVATE).name(null).build();
        chat.setDeleted(false);
        return chat;
    }

    private static ChatMember memberFor(Chat chat, User user) {
        return ChatMember.builder().chat(chat).user(user).build();
    }

    /**
     * An active membership row for {@code user} (never left, not banned).
     */
    private static ChatMember activeMember(Chat chat, User user) {
        return ChatMember.builder().chat(chat).user(user).leftAt(null).isBanned(false).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Chat existence / UUID gate
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("chat existence gate")
    class ChatGate {

        @Test
        void shouldThrowNotFoundWhenChatMissing() {
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.summarize(me, CHAT_UUID))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Chat not found");

            verifyNoInteractions(chatMemberRepository, messageRepository, messageAttachmentRepository);
        }

        @Test
        void shouldThrowNotFoundWhenChatSoftDeleted() {
            Chat deleted = privateChat();
            deleted.setDeleted(true);
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(deleted));

            assertThatThrownBy(() -> service.summarize(me, CHAT_UUID))
                    .isInstanceOf(NotFoundException.class);

            verifyNoInteractions(chatMemberRepository, messageRepository, messageAttachmentRepository);
        }

        /**
         * A non-UUID chat id is mapped to {@code NotFoundException} (TM_024) at the parse step, so no
         * repository is ever consulted.
         */
        @Test
        void shouldThrowNotFoundOnMalformedUuidWithoutTouchingRepositories() {
            // UUID.fromString throws before the repo is queried; the impl maps it to TM_024.
            assertThatThrownBy(() -> service.summarize(me, "not-a-uuid"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Chat not found");

            verifyNoInteractions(chatRepository, chatMemberRepository,
                    messageRepository, messageAttachmentRepository);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Membership (IDOR) gate
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("membership gate")
    class MembershipGate {

        @Test
        void shouldThrowForbiddenWhenCallerHasNoMembership() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.summarize(me, CHAT_UUID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("not part of this conversation");

            // Rejected before any counting happens.
            verifyNoInteractions(messageRepository, messageAttachmentRepository);
        }

        @Test
        void shouldThrowForbiddenWhenMemberHasLeft() {
            Chat chat = privateChat();
            ChatMember left = ChatMember.builder()
                    .chat(chat).user(me).leftAt(Instant.now()).isBanned(false).build();
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.of(left));

            assertThatThrownBy(() -> service.summarize(me, CHAT_UUID))
                    .isInstanceOf(ForbiddenException.class);

            verifyNoInteractions(messageRepository, messageAttachmentRepository);
        }

        @Test
        void shouldThrowForbiddenWhenMemberIsBanned() {
            Chat chat = privateChat();
            ChatMember banned = ChatMember.builder()
                    .chat(chat).user(me).leftAt(null).isBanned(true).build();
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me)).thenReturn(Optional.of(banned));

            assertThatThrownBy(() -> service.summarize(me, CHAT_UUID))
                    .isInstanceOf(ForbiddenException.class);

            verifyNoInteractions(messageRepository, messageAttachmentRepository);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  1:1-only rule
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1:1-only rule")
    class OneToOneOnly {

        @Test
        void shouldThrowForbiddenForMultiPartyChat() {
            Chat group = Chat.builder().chatType(ChatType.GROUP).build();
            group.setDeleted(false);
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(group));
            when(chatMemberRepository.findByChatAndUser(group, me))
                    .thenReturn(Optional.of(activeMember(group, me)));

            assertThatThrownBy(() -> service.summarize(me, CHAT_UUID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("only for 1:1 chats");

            // The group check fires before the other-participant resolution / counting.
            verify(chatMemberRepository, never()).findByChat(any(Chat.class));
            verifyNoInteractions(messageRepository, messageAttachmentRepository);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Happy path — counts + participant resolution + headline
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        /**
         * Stubs a fully-populated live 1:1 with the given counts, then returns the built chat.
         */
        private Chat stubLivePairChat(long total, long myCount, long theirCount,
                                      long photos, long activeDays, Instant firstAt) {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me))
                    .thenReturn(Optional.of(activeMember(chat, me)));
            when(chatMemberRepository.findByChat(chat))
                    .thenReturn(List.of(memberFor(chat, me), memberFor(chat, other)));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(total);
            when(messageRepository.countVisibleByChatAndSender(chat, ME_ID)).thenReturn(myCount);
            when(messageRepository.countVisibleByChatAndSender(chat, OTHER_ID)).thenReturn(theirCount);
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(photos);
            when(messageRepository.countActiveDays(chat)).thenReturn(activeDays);
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(firstAt);
            return chat;
        }

        /**
         * Full happy-path assertion: per-sender counts, photo/active-day totals and firstMessageAt
         * flow onto the card, the non-caller participant is resolved onto the header, and a headline
         * mentioning the partner and message volume is produced. Also proves theirMessages comes from
         * the per-sender count on the resolved partner rather than the total-minus-mine fallback.
         */
        @Test
        void shouldComputeCountsResolveOtherAndBuildHeadline() {
            Instant firstAt = Instant.now().minus(Duration.ofDays(10));
            Chat chat = stubLivePairChat(42L, 25L, 17L, 5L, 9L, firstAt);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            assertThat(res.getChatUuid()).isEqualTo(CHAT_UUID);
            assertThat(res.getTotalMessages()).isEqualTo(42L);
            assertThat(res.getMyMessages()).isEqualTo(25L);
            assertThat(res.getTheirMessages()).isEqualTo(17L);
            assertThat(res.getPhotosShared()).isEqualTo(5L);
            assertThat(res.getActiveDays()).isEqualTo(9L);
            assertThat(res.getFirstMessageAt()).isEqualTo(firstAt);
            assertThat(res.getDaysKnown()).isGreaterThanOrEqualTo(9L);

            // The other participant is resolved onto the card header.
            assertThat(res.getOtherName()).isEqualTo("Bob");
            assertThat(res.getOtherUsername()).isEqualTo("bob");
            assertThat(res.getOtherAvatar()).isEqualTo("https://cdn.example.com/bob.png");

            // Headline is generated, non-null, and mentions the partner + volume.
            assertThat(res.getHeadline()).isNotNull().isNotBlank()
                    .contains("Bob").contains("42 messages");

            // theirMessages came from the per-sender count on the resolved partner,
            // not the total-minus-mine fallback.
            verify(messageRepository).countVisibleByChatAndSender(chat, OTHER_ID);
        }

        @Test
        void shouldReturnOnlyOverlappingInterestsPrettified() {
            stubLivePairChat(10L, 6L, 4L, 0L, 3L, Instant.now().minus(Duration.ofDays(2)));

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            // me = {MUSIC, GAMING, TRAVEL}, other = {GAMING, TRAVEL, SPORTS} ⇒ overlap {GAMING, TRAVEL}.
            assertThat(res.getSharedInterests())
                    .containsExactlyInAnyOrder("Gaming", "Travel")
                    .doesNotContain("Music", "Sports");
        }

        /**
         * When the partner cannot be resolved (findByChat returns only the caller), theirMessages is
         * derived as total minus mine, the header fields stay null, no interest intersection is done,
         * and the headline degrades to the generic "them" wording.
         */
        @Test
        void shouldFallBackToTotalMinusMineWhenOtherUnresolved() {
            // findByChat returns only the caller ⇒ resolveOther == null ⇒ theirMessages = total - mine,
            // header fields stay null, headline falls back to "them".
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me))
                    .thenReturn(Optional.of(activeMember(chat, me)));
            when(chatMemberRepository.findByChat(chat))
                    .thenReturn(List.of(memberFor(chat, me)));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(30L);
            when(messageRepository.countVisibleByChatAndSender(chat, ME_ID)).thenReturn(12L);
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(0L);
            when(messageRepository.countActiveDays(chat)).thenReturn(4L);
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(null);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            assertThat(res.getTheirMessages()).isEqualTo(18L); // 30 - 12
            assertThat(res.getOtherName()).isNull();
            assertThat(res.getSharedInterests()).isEmpty(); // no "other" ⇒ no intersection
            assertThat(res.getDaysKnown()).isZero();         // firstAt null
            assertThat(res.getHeadline()).isNotNull().contains("them");

            // The per-sender count is only run for the caller, never for a (missing) partner.
            verify(messageRepository).countVisibleByChatAndSender(chat, ME_ID);
            verify(messageRepository, never()).countVisibleByChatAndSender(chat, OTHER_ID);
        }

        @Test
        void shouldEmitGettingStartedHeadlineWhenNoMessages() {
            stubLivePairChat(0L, 0L, 0L, 0L, 0L, null);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            assertThat(res.getTotalMessages()).isZero();
            assertThat(res.getHeadline()).isEqualTo("Your story with Bob is just getting started.");
        }

        @Test
        void shouldSwallowAttachmentCountFailureToZero() {
            // safeCount wraps the image + active-day counts; a repository blow-up must not surface.
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me))
                    .thenReturn(Optional.of(activeMember(chat, me)));
            when(chatMemberRepository.findByChat(chat))
                    .thenReturn(List.of(memberFor(chat, me), memberFor(chat, other)));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(8L);
            when(messageRepository.countVisibleByChatAndSender(chat, ME_ID)).thenReturn(5L);
            when(messageRepository.countVisibleByChatAndSender(chat, OTHER_ID)).thenReturn(3L);
            when(messageAttachmentRepository.countImagesByChat(chat))
                    .thenThrow(new RuntimeException("db hiccup"));
            when(messageRepository.countActiveDays(chat))
                    .thenThrow(new RuntimeException("db hiccup"));
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(null);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            assertThat(res.getPhotosShared()).isZero();
            assertThat(res.getActiveDays()).isZero();
            assertThat(res.getTotalMessages()).isEqualTo(8L);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Branch-coverage backfill: compound guards + headline/join arms
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("branch backfill")
    class BranchBackfill {

        private User userWith(long id, String name, Set<Interest> interests) {
            User u = User.builder()
                    .username("u" + id).email(id + "@e.com").name(name)
                    .interests(interests).build();
            u.setId(id);
            return u;
        }

        /**
         * Stub a live 1:1 pair on the given (non-field) users with explicit counts.
         */
        private Chat stubPair(Chat chat, User meU, User otherU, long total, long my, long their,
                              long photos, long activeDays, Instant firstAt) {
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, meU))
                    .thenReturn(Optional.of(activeMember(chat, meU)));
            when(chatMemberRepository.findByChat(chat))
                    .thenReturn(List.of(memberFor(chat, meU), memberFor(chat, otherU)));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(total);
            when(messageRepository.countVisibleByChatAndSender(chat, meU.getId())).thenReturn(my);
            when(messageRepository.countVisibleByChatAndSender(chat, otherU.getId())).thenReturn(their);
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(photos);
            when(messageRepository.countActiveDays(chat)).thenReturn(activeDays);
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(firstAt);
            return chat;
        }

        @Test
        @DisplayName("null chatType passes the 1:1-only gate (isMultiParty not evaluated)")
        void nullChatTypePassesOneToOneGate() {
            Chat chat = Chat.builder().chatType(null).build();
            chat.setDeleted(false);
            stubPair(chat, me, other, 3L, 2L, 1L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            assertThat(res.getOtherName()).isEqualTo("Bob");
            assertThat(res.getHeadline()).isNotNull();
        }

        @Test
        @DisplayName("resolveOther skips a member row whose user is null")
        void resolveOtherSkipsNullUserMember() {
            Chat chat = privateChat();
            when(chatRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, me))
                    .thenReturn(Optional.of(activeMember(chat, me)));
            when(chatMemberRepository.findByChat(chat))
                    .thenReturn(List.of(memberFor(chat, null), memberFor(chat, other)));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(4L);
            when(messageRepository.countVisibleByChatAndSender(chat, ME_ID)).thenReturn(2L);
            when(messageRepository.countVisibleByChatAndSender(chat, OTHER_ID)).thenReturn(2L);
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(0L);
            when(messageRepository.countActiveDays(chat)).thenReturn(1L);
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(null);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            // The null-user row was skipped; the real partner is resolved onto the card.
            assertThat(res.getOtherName()).isEqualTo("Bob");
        }

        @Test
        @DisplayName("shared interests: caller has null interests → empty (line 122 mine == null)")
        void callerNullInterests() {
            User meU = userWith(ME_ID, "Alice", null);
            User otherU = userWith(OTHER_ID, "Bob", Set.of(Interest.GAMING));
            Chat chat = stubPair(privateChat(), meU, otherU, 5L, 3L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getSharedInterests()).isEmpty();
        }

        @Test
        @DisplayName("shared interests: partner has null interests → empty (line 122 theirs == null)")
        void partnerNullInterests() {
            User meU = userWith(ME_ID, "Alice", Set.of(Interest.GAMING));
            User otherU = userWith(OTHER_ID, "Bob", null);
            stubPair(privateChat(), meU, otherU, 5L, 3L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getSharedInterests()).isEmpty();
        }

        @Test
        @DisplayName("shared interests: caller has empty interests → empty (line 122 mine.isEmpty)")
        void callerEmptyInterests() {
            User meU = userWith(ME_ID, "Alice", Set.of());
            User otherU = userWith(OTHER_ID, "Bob", Set.of(Interest.GAMING));
            stubPair(privateChat(), meU, otherU, 5L, 3L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getSharedInterests()).isEmpty();
        }

        @Test
        @DisplayName("shared interests: partner has empty interests → empty (line 122 theirs.isEmpty)")
        void partnerEmptyInterests() {
            User meU = userWith(ME_ID, "Alice", Set.of(Interest.GAMING));
            User otherU = userWith(OTHER_ID, "Bob", Set.of());
            stubPair(privateChat(), meU, otherU, 5L, 3L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getSharedInterests()).isEmpty();
        }

        @Test
        @DisplayName("shared interests are capped at 6 (line 127 break)")
        void sharedInterestsCappedAtSix() {
            Set<Interest> seven = Set.of(Interest.SPORTS, Interest.MUSIC, Interest.MOVIES,
                    Interest.GAMING, Interest.TRAVEL, Interest.FOOD, Interest.CODING);
            User meU = userWith(ME_ID, "Alice", seven);
            User otherU = userWith(OTHER_ID, "Bob", seven);
            stubPair(privateChat(), meU, otherU, 20L, 10L, 10L, 0L, 3L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getSharedInterests()).hasSize(6);
        }

        @Test
        @DisplayName("headline uses 'them' when the partner exists but has a null name (line 135)")
        void headlinePartnerNullName() {
            User otherU = userWith(OTHER_ID, null, Set.of(Interest.SPORTS));
            stubPair(privateChat(), me, otherU, 4L, 2L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            assertThat(res.getHeadline()).contains("them").doesNotContain("null");
        }

        @Test
        @DisplayName("singular message/day/photo wording (lines 141/143/147)")
        void singularWording() {
            Instant firstAt = Instant.now().minus(Duration.ofDays(1));
            // total = 1, one photo, ~1 day known → all singular arms.
            stubPair(privateChat(), me, other, 1L, 1L, 0L, 1L, 1L, firstAt);

            ConversationSummaryResponse res = service.summarize(me, CHAT_UUID);

            assertThat(res.getHeadline())
                    .contains("1 message").doesNotContain("1 messages")
                    .contains("1 day").doesNotContain("1 days")
                    .contains("1 photo").doesNotContain("1 photos");
        }

        @Test
        @DisplayName("humanJoin single interest (line 158)")
        void humanJoinSingle() {
            User meU = userWith(ME_ID, "Alice", new LinkedHashSet<>(List.of(Interest.GAMING)));
            User otherU = userWith(OTHER_ID, "Bob",
                    Set.of(Interest.GAMING, Interest.SPORTS));
            stubPair(privateChat(), meU, otherU, 5L, 3L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getHeadline()).contains("You both love Gaming.");
        }

        @Test
        @DisplayName("humanJoin two interests → 'X and Y' (line 159)")
        void humanJoinPair() {
            User meU = userWith(ME_ID, "Alice",
                    new LinkedHashSet<>(List.of(Interest.GAMING, Interest.TRAVEL)));
            User otherU = userWith(OTHER_ID, "Bob",
                    Set.of(Interest.GAMING, Interest.TRAVEL, Interest.SPORTS));
            stubPair(privateChat(), meU, otherU, 5L, 3L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getHeadline()).contains("You both love Gaming and Travel.");
        }

        @Test
        @DisplayName("humanJoin three interests → 'X, Y and Z' (line 160)")
        void humanJoinThree() {
            User meU = userWith(ME_ID, "Alice",
                    new LinkedHashSet<>(List.of(Interest.GAMING, Interest.TRAVEL, Interest.FOOD)));
            User otherU = userWith(OTHER_ID, "Bob",
                    Set.of(Interest.GAMING, Interest.TRAVEL, Interest.FOOD, Interest.SPORTS));
            stubPair(privateChat(), meU, otherU, 5L, 3L, 2L, 0L, 1L, null);

            ConversationSummaryResponse res = service.summarize(meU, CHAT_UUID);

            assertThat(res.getHeadline()).contains("You both love Gaming, Travel and Food.");
        }
    }
}
