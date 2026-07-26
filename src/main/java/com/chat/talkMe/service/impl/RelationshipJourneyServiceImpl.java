package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.domain.RelationshipMilestone;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MilestoneResponse;
import com.chat.talkMe.dto.response.RelationshipJourneyResponse;
import com.chat.talkMe.dto.response.RelationshipStatsResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RelationshipJourneyServiceImpl implements RelationshipJourneyService {

    /** Dedupe refs per milestone source (part of the unique key with type). */
    private static final String REF_FRIENDSHIP = "friendship";
    private static final String REF_CHAT = "chat";   // message + photo milestones
    private static final String REF_GAME = "game";   // games milestone

    private final RelationshipMilestoneRepository milestoneRepository;
    private final FriendRepository friendRepository;
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final GameSessionRepository gameSessionRepository;

    /**
     * Provider for this bean's own Spring proxy. Used so the lazy on-read materialize goes
     * through the proxy (honouring {@code REQUIRES_NEW}) instead of a self-invocation that
     * would bypass the transactional advice. ObjectProvider resolves lazily, so injecting it
     * does not create a circular-dependency at startup.
     */
    private final ObjectProvider<RelationshipJourneyService> selfProvider;

    @Override
    @Transactional(readOnly = true)
    public RelationshipJourneyResponse getJourney(User viewer, String otherUserUuid) {
        User other = resolveUser(otherUserUuid);

        // A journey is a relationship between TWO users. Viewing your own is allowed by the
        // authz rule but has no pair — return an empty timeline rather than a degenerate self-pair.
        if (viewer.getId().equals(other.getId())) {
            return RelationshipJourneyResponse.builder()
                    .otherUserUuid(other.getUuid().toString())
                    .milestones(List.of())
                    .build();
        }

        // AUTHZ: only the two people in a relationship can see its journey.
        if (!isActiveFriend(viewer, other)) {
            throw new ForbiddenException("You can only view the journey of a friend", "TM_821");
        }

        // Lazily materialize so the timeline is populated even before the nightly job runs.
        // Routed through the proxy so it runs in its OWN (REQUIRES_NEW) transaction — a rare
        // race with the nightly job can then only roll back that inner tx, never taint this read.
        try {
            selfProvider.getObject().materializeFor(viewer, other);
        } catch (Exception e) {
            log.debug("[Journey] lazy materialize failed for {}/{}: {}",
                    viewer.getId(), other.getId(), e.getMessage());
        }

        long low = Math.min(viewer.getId(), other.getId());
        long high = Math.max(viewer.getId(), other.getId());
        List<MilestoneResponse> milestones =
                milestoneRepository.findByUserAIdAndUserBIdOrderByAchievedAtAsc(low, high).stream()
                        .map(MilestoneResponse::from)
                        .toList();

        // Aggregate stats are a read-only projection (counts drift constantly). A stat failure
        // must not break the timeline — fall back to null stats.
        RelationshipStatsResponse stats = null;
        try {
            stats = computeStats(viewer, other, low, high);
        } catch (Exception e) {
            log.debug("[Journey] stats compute failed for {}/{}: {}", low, high, e.getMessage());
        }

        return RelationshipJourneyResponse.builder()
                .otherUserUuid(other.getUuid().toString())
                .milestones(milestones)
                .stats(stats)
                .build();
    }

    private RelationshipStatsResponse computeStats(User viewer, User other, long low, long high) {
        Chat chat = resolveSharedChat(low, high);
        long messages = chat != null ? messageRepository.countVisibleByChat(chat) : 0L;
        long photos = chat != null ? messageAttachmentRepository.countImagesByChat(chat) : 0L;
        long games = chat != null ? gameSessionRepository.countByChatId(chat.getUuid().toString()) : 0L;
        Instant firstMessageAt = chat != null ? messageRepository.findFirstMessageAt(chat) : null;
        Instant friendsSince = activeFriendshipFormedAt(viewer, other);
        long daysKnown = friendsSince != null
                ? Math.max(0, ChronoUnit.DAYS.between(friendsSince, Instant.now())) : 0L;
        return RelationshipStatsResponse.builder()
                .messagesExchanged(messages)
                .photosShared(photos)
                .gamesPlayed(games)
                .friendsSince(friendsSince)
                .daysKnown(daysKnown)
                .firstMessageAt(firstMessageAt)
                .build();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void materializeFor(User userA, User userB) {
        if (userA == null || userB == null || userA.getId() == null || userB.getId() == null) {
            return;
        }
        if (userA.getId().equals(userB.getId())) {
            return; // no self-relationship
        }

        long low = Math.min(userA.getId(), userB.getId());
        long high = Math.max(userA.getId(), userB.getId());

        // Friendship-derived milestones.
        Instant friendedAt = activeFriendshipFormedAt(userA, userB);
        if (friendedAt == null) {
            return; // not (or no longer) friends → nothing to derive yet
        }
        upsert(low, high, MilestoneType.BECAME_FRIENDS, friendedAt, MilestoneType.BECAME_FRIENDS.getLabel());
        Instant oneMonth = friendedAt.plus(30, ChronoUnit.DAYS);
        if (!oneMonth.isAfter(Instant.now())) {
            upsert(low, high, MilestoneType.ONE_MONTH_FRIENDS, oneMonth, MilestoneType.ONE_MONTH_FRIENDS.getLabel());
        }

        // Conversation-derived milestones from the pair's shared 1:1 chat. Each source is isolated
        // so one failing query never drops the friendship milestones already written above.
        Chat chat = resolveSharedChat(low, high);
        if (chat != null) {
            try { materializeMessageMilestones(low, high, chat); }
            catch (Exception e) { log.debug("[Journey] msg milestones {}/{}: {}", low, high, e.getMessage()); }
            try { materializePhotoMilestones(low, high, chat); }
            catch (Exception e) { log.debug("[Journey] photo milestones {}/{}: {}", low, high, e.getMessage()); }
            try { materializeGameMilestones(low, high, chat); }
            catch (Exception e) { log.debug("[Journey] game milestones {}/{}: {}", low, high, e.getMessage()); }
        }
    }

    /** The earliest non-deleted PRIVATE/STRANGER chat the pair share (stable first-message anchor). */
    private Chat resolveSharedChat(long low, long high) {
        return chatRepository.findPrivateChatBetweenUsers(low, high).stream()
                .filter(c -> !c.isDeleted())
                .min(Comparator.comparing(Chat::getCreatedAt))
                .orElse(null);
    }

    private void materializeMessageMilestones(long low, long high, Chat chat) {
        long n = messageRepository.countVisibleByChat(chat);
        if (n >= 1) {
            upsert(low, high, MilestoneType.FIRST_MESSAGE, messageRepository.findFirstMessageAt(chat),
                    MilestoneType.FIRST_MESSAGE.getLabel(), REF_CHAT);
        }
        if (n >= 50) {
            upsert(low, high, MilestoneType.MESSAGES_50, nthMessageTime(chat, 50), "50 messages", REF_CHAT);
        }
        if (n >= 500) {
            upsert(low, high, MilestoneType.MESSAGES_500, nthMessageTime(chat, 500), "500 messages", REF_CHAT);
        }
    }

    private void materializePhotoMilestones(long low, long high, Chat chat) {
        if (messageAttachmentRepository.countImagesByChat(chat) >= 1) {
            upsert(low, high, MilestoneType.FIRST_PHOTO_SHARED,
                    messageAttachmentRepository.findFirstImageAt(chat),
                    MilestoneType.FIRST_PHOTO_SHARED.getLabel(), REF_CHAT);
        }
    }

    private void materializeGameMilestones(long low, long high, Chat chat) {
        String chatId = chat.getUuid().toString();
        long g = gameSessionRepository.countByChatId(chatId);
        if (g >= 1) {
            upsert(low, high, MilestoneType.GAMES_PLAYED, gameSessionRepository.findFirstGameAt(chatId),
                    g + " games", REF_GAME);
        }
    }

    private Instant nthMessageTime(Chat chat, int n) {
        return messageRepository.findVisibleMessageTimes(chat, PageRequest.of(n - 1, 1))
                .stream().findFirst().orElse(Instant.now());
    }

    /**
     * Insert a milestone if it is not already present. The exists-check plus the DB unique
     * constraint make this idempotent even under a race between the nightly job and a lazy
     * read on the same pair — a losing writer's constraint violation is swallowed (this method
     * runs in its own REQUIRES_NEW transaction, so the violation cannot taint a caller's tx).
     */
    private void upsert(long userAId, long userBId, MilestoneType type, Instant achievedAt, String detail) {
        upsert(userAId, userBId, type, achievedAt, detail, REF_FRIENDSHIP);
    }

    private void upsert(long userAId, long userBId, MilestoneType type, Instant achievedAt, String detail, String ref) {
        if (milestoneRepository.existsByUserAIdAndUserBIdAndTypeAndRef(userAId, userBId, type, ref)) {
            return;
        }
        RelationshipMilestone milestone = RelationshipMilestone.builder()
                .userAId(userAId)
                .userBId(userBId)
                .type(type)
                .achievedAt(achievedAt != null ? achievedAt : Instant.now())
                .detail(detail)
                .ref(ref)
                .build();
        try {
            milestoneRepository.save(milestone);
        } catch (DataIntegrityViolationException dup) {
            log.debug("[Journey] milestone {} already recorded for pair {}/{}", type, userAId, userBId);
        }
    }

    /**
     * The formation time of the active friendship between the pair (either direction), or
     * {@code null} if they are not currently friends. Uses the earliest of the two directional
     * rows when both exist.
     */
    private Instant activeFriendshipFormedAt(User a, User b) {
        Instant formedAt = activeCreatedAt(friendRepository.findByUserAndFriend(a, b).orElse(null));
        Instant reverse = activeCreatedAt(friendRepository.findByUserAndFriend(b, a).orElse(null));
        if (reverse != null && (formedAt == null || reverse.isBefore(formedAt))) {
            formedAt = reverse;
        }
        return formedAt;
    }

    private Instant activeCreatedAt(Friend friend) {
        return (friend != null && !friend.isDeleted()) ? friend.getCreatedAt() : null;
    }

    private boolean isActiveFriend(User a, User b) {
        return activeFriendshipFormedAt(a, b) != null;
    }

    private User resolveUser(String userUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid user id", "TM_820");
        }
        return userRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", "TM_822"));
    }
}
