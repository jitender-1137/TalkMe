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

    /** A live 1:1 chat matching CHAT_UUID (not deleted). Chat's @Builder omits BaseEntity fields. */
    private static Chat privateChat() {
        Chat chat = Chat.builder().chatType(ChatType.PRIVATE).name(null).build();
        chat.setDeleted(false);
        return chat;
    }

    private static ChatMember memberFor(Chat chat, User user) {
        return ChatMember.builder().chat(chat).user(user).build();
    }

    /** An active membership row for {@code user} (never left, not banned). */
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

        /** Stubs a fully-populated live 1:1 with the given counts, then returns the built chat. */
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
}
