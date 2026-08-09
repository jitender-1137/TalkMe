package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.domain.RelationshipMilestone;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.RelationshipJourneyResponse;
import com.chat.talkMe.enums.MilestoneType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.GameSessionRepository;
import com.chat.talkMe.repository.MessageAttachmentRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.RelationshipMilestoneRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.RelationshipJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link RelationshipJourneyServiceImpl} — the two-user Relationship
 * Journey timeline + aggregate stats (feature #19).
 *
 * <p>Key invariants under test: (1) {@code getJourney} resolves + authorizes (self → empty pair,
 * non-friend → TM_821, bad/absent uuid → TM_820/TM_822), lazily materializes through the self
 * proxy, and never lets a materialize/stats failure break the read; (2) {@code materializeFor}
 * upserts friendship milestones, adds the one-month milestone only once it is due, derives
 * message/photo/game milestones from the shared chat, is idempotent (exists-check + swallowed
 * constraint violation), and isolates each source so one failing query never drops the rest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RelationshipJourneyServiceImpl (unit)")
class RelationshipJourneyServiceImplTest {

    @Mock
    private RelationshipMilestoneRepository milestoneRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageAttachmentRepository messageAttachmentRepository;
    @Mock
    private GameSessionRepository gameSessionRepository;
    @Mock
    private ObjectProvider<RelationshipJourneyService> selfProvider;
    @Mock
    private RelationshipJourneyService selfProxy;

    private RelationshipJourneyServiceImpl service;

    private User viewer;
    private User other;
    private final UUID otherUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        service = new RelationshipJourneyServiceImpl(milestoneRepository, friendRepository,
                userRepository, chatRepository, messageRepository, messageAttachmentRepository,
                gameSessionRepository, selfProvider);

        viewer = User.builder().username("alice").build();
        viewer.setId(1L);
        viewer.setUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        other = User.builder().username("bob").build();
        other.setId(2L);
        other.setUuid(otherUuid);
    }

    private User user(long id) {
        User u = User.builder().username("u" + id).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private Friend friendFormedAt(Instant createdAt) {
        Friend f = Friend.builder().build();
        f.setCreatedAt(createdAt);
        f.setDeleted(false);
        return f;
    }

    private Chat sharedChat() {
        Chat c = Chat.builder().build();
        c.setId(50L);
        c.setUuid(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        c.setCreatedAt(Instant.now().minus(200, ChronoUnit.DAYS));
        c.setDeleted(false);
        return c;
    }

    /**
     * Stub the pair as active friends (viewer→other direction), formed at {@code formedAt}.
     */
    private void friendsSince(Instant formedAt) {
        lenient().when(friendRepository.findByUserAndFriend(viewer, other))
                .thenReturn(Optional.of(friendFormedAt(formedAt)));
        lenient().when(friendRepository.findByUserAndFriend(other, viewer))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("getJourney")
    class GetJourney {

        @Test
        @DisplayName("malformed other-user uuid → BadRequestException TM_820")
        void badUuid() {
            assertThatThrownBy(() -> service.getJourney(viewer, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_820"));
        }

        @Test
        @DisplayName("other user not found → NotFoundException TM_822")
        void userNotFound() {
            when(userRepository.findByUuid(otherUuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getJourney(viewer, otherUuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_822"));
        }

        @Test
        @DisplayName("viewing your own journey → empty timeline, no authz/materialize")
        void selfViewEmptyTimeline() {
            User self = user(1L); // same id as viewer
            when(userRepository.findByUuid(self.getUuid())).thenReturn(Optional.of(self));

            RelationshipJourneyResponse res = service.getJourney(viewer, self.getUuid().toString());

            assertThat(res.getOtherUserUuid()).isEqualTo(self.getUuid().toString());
            assertThat(res.getMilestones()).isEmpty();
            assertThat(res.getStats()).isNull();
            verifyNoInteractions(friendRepository, selfProvider, milestoneRepository);
        }

        @Test
        @DisplayName("not an active friend → ForbiddenException TM_821")
        void notFriend() {
            when(userRepository.findByUuid(otherUuid)).thenReturn(Optional.of(other));
            when(friendRepository.findByUserAndFriend(viewer, other)).thenReturn(Optional.empty());
            when(friendRepository.findByUserAndFriend(other, viewer)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getJourney(viewer, otherUuid.toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_821"));
            verify(selfProvider, never()).getObject();
        }

        /**
         * Happy path: for an active friend the read triggers a lazy materialize through the self
         * proxy, then returns the persisted milestones plus fully-populated aggregate stats
         * (messages / photos / games / friends-since / first-message / days-known) sourced from the
         * shared chat.
         */
        @Test
        @DisplayName("friend → materializes via proxy, returns ordered milestones + stats")
        void nominal() {
            Instant formedAt = Instant.now().minus(100, ChronoUnit.DAYS);
            when(userRepository.findByUuid(otherUuid)).thenReturn(Optional.of(other));
            friendsSince(formedAt);
            when(selfProvider.getObject()).thenReturn(selfProxy);

            RelationshipMilestone m = RelationshipMilestone.builder()
                    .userAId(1L).userBId(2L).type(MilestoneType.BECAME_FRIENDS)
                    .achievedAt(formedAt).detail("You became friends").ref("friendship").build();
            when(milestoneRepository.findByUserAIdAndUserBIdOrderByAchievedAtAsc(1L, 2L))
                    .thenReturn(List.of(m));

            Chat chat = sharedChat();
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(chat));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(120L);
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(7L);
            when(gameSessionRepository.countByChatId(chat.getUuid().toString())).thenReturn(3L);
            Instant firstMsg = formedAt.plus(1, ChronoUnit.DAYS);
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(firstMsg);

            RelationshipJourneyResponse res = service.getJourney(viewer, otherUuid.toString());

            verify(selfProxy).materializeFor(viewer, other);
            assertThat(res.getOtherUserUuid()).isEqualTo(otherUuid.toString());
            assertThat(res.getMilestones()).hasSize(1);
            assertThat(res.getMilestones().get(0).getType()).isEqualTo(MilestoneType.BECAME_FRIENDS);
            assertThat(res.getMilestones().get(0).getLabel()).isEqualTo("You became friends");

            assertThat(res.getStats()).isNotNull();
            assertThat(res.getStats().getMessagesExchanged()).isEqualTo(120L);
            assertThat(res.getStats().getPhotosShared()).isEqualTo(7L);
            assertThat(res.getStats().getGamesPlayed()).isEqualTo(3L);
            assertThat(res.getStats().getFriendsSince()).isEqualTo(formedAt);
            assertThat(res.getStats().getFirstMessageAt()).isEqualTo(firstMsg);
            assertThat(res.getStats().getDaysKnown()).isGreaterThanOrEqualTo(99L);
        }

        @Test
        @DisplayName("no shared chat → stats present with zero counts and null firstMessageAt")
        void nominalNoSharedChat() {
            Instant formedAt = Instant.now().minus(10, ChronoUnit.DAYS);
            when(userRepository.findByUuid(otherUuid)).thenReturn(Optional.of(other));
            friendsSince(formedAt);
            when(selfProvider.getObject()).thenReturn(selfProxy);
            when(milestoneRepository.findByUserAIdAndUserBIdOrderByAchievedAtAsc(1L, 2L))
                    .thenReturn(List.of());
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            RelationshipJourneyResponse res = service.getJourney(viewer, otherUuid.toString());

            assertThat(res.getMilestones()).isEmpty();
            assertThat(res.getStats()).isNotNull();
            assertThat(res.getStats().getMessagesExchanged()).isZero();
            assertThat(res.getStats().getPhotosShared()).isZero();
            assertThat(res.getStats().getGamesPlayed()).isZero();
            assertThat(res.getStats().getFirstMessageAt()).isNull();
        }

        @Test
        @DisplayName("materialize failure is swallowed — read still returns the timeline")
        void materializeFailureSwallowed() {
            when(userRepository.findByUuid(otherUuid)).thenReturn(Optional.of(other));
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(selfProvider.getObject()).thenThrow(new RuntimeException("proxy down"));
            when(milestoneRepository.findByUserAIdAndUserBIdOrderByAchievedAtAsc(1L, 2L))
                    .thenReturn(List.of());
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            RelationshipJourneyResponse res = service.getJourney(viewer, otherUuid.toString());

            assertThat(res.getOtherUserUuid()).isEqualTo(otherUuid.toString());
            assertThat(res.getMilestones()).isEmpty();
        }

        @Test
        @DisplayName("stats compute failure is swallowed — timeline returned with null stats")
        void statsFailureSwallowed() {
            when(userRepository.findByUuid(otherUuid)).thenReturn(Optional.of(other));
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(selfProvider.getObject()).thenReturn(selfProxy);
            when(milestoneRepository.findByUserAIdAndUserBIdOrderByAchievedAtAsc(1L, 2L))
                    .thenReturn(List.of());
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L))
                    .thenThrow(new RuntimeException("db down"));

            RelationshipJourneyResponse res = service.getJourney(viewer, otherUuid.toString());

            assertThat(res.getStats()).isNull();
            assertThat(res.getMilestones()).isEmpty();
        }
    }

    @Nested
    @DisplayName("materializeFor")
    class MaterializeFor {

        @Test
        @DisplayName("null user → no-op, nothing written")
        void nullUser() {
            service.materializeFor(null, other);
            verifyNoInteractions(friendRepository, milestoneRepository, chatRepository);
        }

        @Test
        @DisplayName("user with null id → no-op")
        void nullId() {
            User noId = User.builder().username("x").build(); // id stays null
            service.materializeFor(noId, other);
            verifyNoInteractions(friendRepository, milestoneRepository, chatRepository);
        }

        @Test
        @DisplayName("same user id → no self-relationship, no-op")
        void sameId() {
            service.materializeFor(viewer, user(1L));
            verifyNoInteractions(friendRepository, milestoneRepository, chatRepository);
        }

        @Test
        @DisplayName("not friends → returns before deriving any milestone")
        void notFriends() {
            when(friendRepository.findByUserAndFriend(viewer, other)).thenReturn(Optional.empty());
            when(friendRepository.findByUserAndFriend(other, viewer)).thenReturn(Optional.empty());

            service.materializeFor(viewer, other);

            verify(milestoneRepository, never()).save(any());
            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("recently-friended pair → only BECAME_FRIENDS (one-month not due yet)")
        void recentFriendsOnlyBecameFriends() {
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            service.materializeFor(viewer, other);

            ArgumentCaptor<RelationshipMilestone> captor =
                    ArgumentCaptor.forClass(RelationshipMilestone.class);
            verify(milestoneRepository, times(1)).save(captor.capture());
            RelationshipMilestone saved = captor.getValue();
            assertThat(saved.getType()).isEqualTo(MilestoneType.BECAME_FRIENDS);
            assertThat(saved.getUserAId()).isEqualTo(1L);
            assertThat(saved.getUserBId()).isEqualTo(2L);
            assertThat(saved.getRef()).isEqualTo("friendship");
        }

        @Test
        @DisplayName("friended >1 month ago → BECAME_FRIENDS + ONE_MONTH_FRIENDS")
        void oldFriendsAddsOneMonth() {
            friendsSince(Instant.now().minus(90, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            service.materializeFor(viewer, other);

            ArgumentCaptor<RelationshipMilestone> captor =
                    ArgumentCaptor.forClass(RelationshipMilestone.class);
            verify(milestoneRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(RelationshipMilestone::getType)
                    .containsExactlyInAnyOrder(
                            MilestoneType.BECAME_FRIENDS, MilestoneType.ONE_MONTH_FRIENDS);
        }

        @Test
        @DisplayName("shared chat with lots of activity → all message/photo/game milestones derived")
        void fullChatMilestones() {
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            Chat chat = sharedChat();
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(chat));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(500L);
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(Instant.now());
            when(messageRepository.findVisibleMessageTimes(eq(chat), any(Pageable.class)))
                    .thenReturn(List.of(Instant.now()));
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(4L);
            when(messageAttachmentRepository.findFirstImageAt(chat)).thenReturn(Instant.now());
            when(gameSessionRepository.countByChatId(chat.getUuid().toString())).thenReturn(9L);
            when(gameSessionRepository.findFirstGameAt(chat.getUuid().toString())).thenReturn(Instant.now());

            service.materializeFor(viewer, other);

            ArgumentCaptor<RelationshipMilestone> captor =
                    ArgumentCaptor.forClass(RelationshipMilestone.class);
            verify(milestoneRepository, times(6)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(RelationshipMilestone::getType)
                    .containsExactlyInAnyOrder(
                            MilestoneType.BECAME_FRIENDS,
                            MilestoneType.FIRST_MESSAGE,
                            MilestoneType.MESSAGES_50,
                            MilestoneType.MESSAGES_500,
                            MilestoneType.FIRST_PHOTO_SHARED,
                            MilestoneType.GAMES_PLAYED);
        }

        @Test
        @DisplayName("all milestones already present → idempotent, nothing saved")
        void idempotentNoSaves() {
            friendsSince(Instant.now().minus(90, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(true);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            service.materializeFor(viewer, other);

            verify(milestoneRepository, never()).save(any());
        }

        @Test
        @DisplayName("race duplicate on save → DataIntegrityViolation swallowed, no throw")
        void duplicateSaveSwallowed() {
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());
            when(milestoneRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("dup"));

            service.materializeFor(viewer, other); // must not propagate

            verify(milestoneRepository).save(any());
        }

        @Test
        @DisplayName("one failing chat source does not drop the friendship milestone")
        void sourceFailureIsolated() {
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            Chat chat = sharedChat();
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(chat));
            // message + photo + game queries all blow up; friendship milestone already written.
            when(messageRepository.countVisibleByChat(chat)).thenThrow(new RuntimeException("boom"));
            when(messageAttachmentRepository.countImagesByChat(chat)).thenThrow(new RuntimeException("boom"));
            when(gameSessionRepository.countByChatId(chat.getUuid().toString()))
                    .thenThrow(new RuntimeException("boom"));

            service.materializeFor(viewer, other);

            ArgumentCaptor<RelationshipMilestone> captor =
                    ArgumentCaptor.forClass(RelationshipMilestone.class);
            verify(milestoneRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(MilestoneType.BECAME_FRIENDS);
        }

        @Test
        @DisplayName("shared chat exists but is soft-deleted → treated as no chat")
        void deletedChatIgnored() {
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            Chat chat = sharedChat();
            chat.setDeleted(true);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(chat));

            service.materializeFor(viewer, other);

            verify(milestoneRepository, times(1)).save(any());
            verify(messageRepository, never()).countVisibleByChat(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Branch-coverage backfill: null-guard arms, directional friendship, count arms
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("branch backfill")
    class BranchBackfill {

        private Friend deletedFriendAt(Instant createdAt) {
            Friend f = Friend.builder().build();
            f.setCreatedAt(createdAt);
            f.setDeleted(true);
            return f;
        }

        /**
         * Asserts exactly one milestone was persisted and returns it for further assertions.
         */
        private RelationshipMilestone captureSingleSave() {
            ArgumentCaptor<RelationshipMilestone> captor =
                    ArgumentCaptor.forClass(RelationshipMilestone.class);
            verify(milestoneRepository, times(1)).save(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("second user null → no-op (line 138 userB == null)")
        void secondUserNull() {
            service.materializeFor(viewer, null);
            verifyNoInteractions(friendRepository, milestoneRepository, chatRepository);
        }

        @Test
        @DisplayName("second user has null id → no-op (line 138 userB.getId() == null)")
        void secondUserNullId() {
            User noId = User.builder().username("x").build(); // id stays null
            service.materializeFor(viewer, noId);
            verifyNoInteractions(friendRepository, milestoneRepository, chatRepository);
        }

        @Test
        @DisplayName("forward row deleted, reverse active → uses reverse (line 260 isDeleted, 253/254)")
        void forwardDeletedReverseActive() {
            Instant reverseAt = Instant.now().minus(5, ChronoUnit.DAYS);
            when(friendRepository.findByUserAndFriend(viewer, other))
                    .thenReturn(Optional.of(deletedFriendAt(Instant.now().minus(3, ChronoUnit.DAYS))));
            when(friendRepository.findByUserAndFriend(other, viewer))
                    .thenReturn(Optional.of(friendFormedAt(reverseAt)));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            service.materializeFor(viewer, other);

            RelationshipMilestone saved = captureSingleSave();
            assertThat(saved.getType()).isEqualTo(MilestoneType.BECAME_FRIENDS);
            assertThat(saved.getAchievedAt()).isEqualTo(reverseAt);
        }

        @Test
        @DisplayName("both rows active, reverse earlier → reverse wins (line 253 isBefore true)")
        void reverseEarlierWins() {
            Instant forwardAt = Instant.now().minus(5, ChronoUnit.DAYS);
            Instant reverseAt = Instant.now().minus(6, ChronoUnit.DAYS); // earlier
            when(friendRepository.findByUserAndFriend(viewer, other))
                    .thenReturn(Optional.of(friendFormedAt(forwardAt)));
            when(friendRepository.findByUserAndFriend(other, viewer))
                    .thenReturn(Optional.of(friendFormedAt(reverseAt)));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            service.materializeFor(viewer, other);

            assertThat(captureSingleSave().getAchievedAt()).isEqualTo(reverseAt);
        }

        @Test
        @DisplayName("both rows active, reverse later → forward kept (line 253 isBefore false)")
        void reverseLaterKeepsForward() {
            Instant forwardAt = Instant.now().minus(6, ChronoUnit.DAYS); // earlier
            Instant reverseAt = Instant.now().minus(5, ChronoUnit.DAYS); // later
            when(friendRepository.findByUserAndFriend(viewer, other))
                    .thenReturn(Optional.of(friendFormedAt(forwardAt)));
            when(friendRepository.findByUserAndFriend(other, viewer))
                    .thenReturn(Optional.of(friendFormedAt(reverseAt)));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of());

            service.materializeFor(viewer, other);

            assertThat(captureSingleSave().getAchievedAt()).isEqualTo(forwardAt);
        }

        @Test
        @DisplayName("shared chat with zero activity → no message/photo/game milestones (n < threshold arms)")
        void zeroActivityChat() {
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            Chat chat = sharedChat();
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(chat));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(0L);
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(0L);
            when(gameSessionRepository.countByChatId(chat.getUuid().toString())).thenReturn(0L);

            service.materializeFor(viewer, other);

            // Only the friendship milestone; no first-message/photo/game derivation.
            verify(milestoneRepository, times(1)).save(any());
            verify(messageRepository, never()).findFirstMessageAt(any());
        }

        @Test
        @DisplayName("first message with null timestamp → upsert defaults achievedAt to now (line 234)")
        void firstMessageNullTimestamp() {
            friendsSince(Instant.now().minus(5, ChronoUnit.DAYS));
            when(milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            Chat chat = sharedChat();
            when(chatRepository.findPrivateChatBetweenUsers(1L, 2L)).thenReturn(List.of(chat));
            when(messageRepository.countVisibleByChat(chat)).thenReturn(1L); // ≥1 but <50
            when(messageRepository.findFirstMessageAt(chat)).thenReturn(null); // null achievedAt
            when(messageAttachmentRepository.countImagesByChat(chat)).thenReturn(0L);
            when(gameSessionRepository.countByChatId(chat.getUuid().toString())).thenReturn(0L);

            service.materializeFor(viewer, other);

            ArgumentCaptor<RelationshipMilestone> captor =
                    ArgumentCaptor.forClass(RelationshipMilestone.class);
            verify(milestoneRepository, times(2)).save(captor.capture()); // BECAME_FRIENDS + FIRST_MESSAGE
            RelationshipMilestone firstMsg = captor.getAllValues().stream()
                    .filter(m -> m.getType() == MilestoneType.FIRST_MESSAGE)
                    .findFirst().orElseThrow();
            assertThat(firstMsg.getAchievedAt()).isNotNull(); // defaulted to Instant.now()
        }
    }
}
