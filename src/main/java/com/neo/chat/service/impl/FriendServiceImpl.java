package com.neo.chat.service.impl;

import com.neo.chat.cache.BlockCache;
import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.Friend;
import com.neo.chat.domain.FriendRequest;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.FriendRequestResponse;
import com.neo.chat.enums.FriendRequestStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ConflictException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.mapper.FriendRequestMapper;
import com.neo.chat.mapper.UserMapper;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.FriendRequestRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.FriendService;
import com.neo.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Friend requests, mutual friendships and blocking. Requests are reused across re-sends and
 * auto-accepted when a reverse pending request exists; new outbound requests are rate-limited per
 * day via a Redis counter (fail-open). Friend lifecycle events are pushed to each party over
 * WebSocket ({@code /user/queue/friends}). Blocking evicts the {@link BlockCache} and removes any
 * existing friendship.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

    private final UserRepository userRepository;
    private final FriendRepository friendRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final BlockUserRepository blockUserRepository;
    private final BlockCache blockCache;
    private final UserSettingRepository userSettingRepository;
    private final FriendRequestMapper friendRequestMapper;
    private final UserMapper userMapper;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;

    /**
     * Max NEW friend requests one user may originate per day (anti-spam).
     */
    private static final int FRIEND_REQUEST_DAILY_CAP = 50;

    /**
     * Enforce the per-user daily new-request cap via a Redis counter; throws 429 past the cap and
     * fails open on Redis errors.
     *
     * @param sender the user originating the request
     * @throws com.neo.chat.exception.TooManyRequestsException if the daily cap is exceeded
     */
    private void enforceFriendRequestQuota(User sender) {
        try {
            String key = "friendreq:" + sender.getId() + ":" + LocalDate.now();
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofDays(1));
            }
            if (count != null && count > FRIEND_REQUEST_DAILY_CAP) {
                throw new TooManyRequestsException(
                        "Daily friend-request limit reached. Try again tomorrow.", "TM_498");
            }
        } catch (TooManyRequestsException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Friend-request quota check failed (fail-open): {}", e.getMessage());
        }
    }

    /**
     * Best-effort push a friend lifecycle event to a user's personal WebSocket queue.
     *
     * @param user      the recipient
     * @param eventType the event name (e.g. friend_request_received)
     */
    private void broadcastFriendEvent(User user, String eventType) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("event", eventType);
            messagingTemplate.convertAndSendToUser(user.getUsername(), "/queue/friends", payload);
        } catch (Exception e) {
            log.error("Failed to broadcast friend event to user {}", user.getUsername(), e);
        }
    }

    /**
     * Send (or re-send) a friend request. Reuses any existing request row, auto-accepts when the
     * receiver already has a pending request to the caller, applies the daily quota only to
     * brand-new requests, and notifies the receiver over WebSocket. Guards against a concurrent
     * duplicate via a unique constraint.
     *
     * @param receiverUuid uuid of the user to befriend
     * @param currentUser  the sender
     * @return the resulting friend-request DTO
     * @throws com.neo.chat.exception.NotFoundException        if the receiver is missing
     * @throws com.neo.chat.exception.BadRequestException      if sending to self
     * @throws com.neo.chat.exception.ForbiddenException       if either party has blocked the other
     * @throws com.neo.chat.exception.ConflictException        if already friends
     * @throws com.neo.chat.exception.TooManyRequestsException if the daily request cap is exceeded
     */
    @Override
    @Transactional
    public FriendRequestResponse sendFriendRequest(String receiverUuid, User currentUser) {
        User receiver = userRepository.findByUuid(UUID.fromString(receiverUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));

        if (receiver.getId().equals(currentUser.getId())) {
            throw new BadRequestException("Cannot send friend request to yourself", "TM_097");
        }

        // Check blocks
        if (blockUserRepository.existsByUserAndBlocked(receiver, currentUser) ||
                blockUserRepository.existsByUserAndBlocked(currentUser, receiver)) {
            throw new ForbiddenException("Friend request blocked", "TM_103");
        }

        // Check if already friends
        if (friendRepository.findByUserAndFriend(currentUser, receiver).isPresent()) {
            throw new ConflictException("Already friends with this user", "TM_096"); // changed to TM_096 "Already processed"
        }

        // Check existing requests
        Optional<FriendRequest> existingRequestOpt = friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(currentUser, receiver);
        if (existingRequestOpt.isPresent()) {
            FriendRequest existingRequest = existingRequestOpt.get();
            if (existingRequest.getStatus() == FriendRequestStatus.ACCEPTED) {
                // If status is ACCEPTED, but they are not friends (checked above),
                // it means they unfriended. We can reuse the request by setting it to PENDING.
                existingRequest.setStatus(FriendRequestStatus.PENDING);
                existingRequest = friendRequestRepository.save(existingRequest);
                log.info("Friend request re-sent (was accepted before unfriending) from {} to {}", currentUser.getUsername(), receiver.getUsername());
                broadcastFriendEvent(receiver, "friend_request_received");
                return friendRequestMapper.toResponse(existingRequest);
            } else {
                // If it was PENDING, REJECTED, or CANCELLED, update to PENDING and update timestamp
                existingRequest.setStatus(FriendRequestStatus.PENDING);
                existingRequest = friendRequestRepository.save(existingRequest);
                log.info("Friend request re-sent/updated from {} to {}", currentUser.getUsername(), receiver.getUsername());
                broadcastFriendEvent(receiver, "friend_request_received");
                return friendRequestMapper.toResponse(existingRequest);
            }
        }

        // Check if the other user already sent a request to the current user
        Optional<FriendRequest> reverseRequestOpt = friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(receiver, currentUser);
        if (reverseRequestOpt.isPresent() && reverseRequestOpt.get().getStatus() == FriendRequestStatus.PENDING) {
            // Auto-accept if the other user already sent one
            acceptFriendRequest(reverseRequestOpt.get().getUuid().toString(), currentUser);
            return friendRequestMapper.toResponse(reverseRequestOpt.get());
        }

        // Anti-spam: bound brand-new outbound requests per day. Re-sends and
        // auto-accepts above reuse existing rows and don't reach here, so they
        // don't consume quota.
        enforceFriendRequestQuota(currentUser);

        FriendRequest request = FriendRequest.builder()
                .sender(currentUser)
                .receiver(receiver)
                .status(FriendRequestStatus.PENDING)
                .build();

        try {
            // saveAndFlush so the unique (sender_id, receiver_id) constraint is enforced
            // here, letting us catch a concurrent-duplicate race instead of 500ing.
            request = friendRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException dup) {
            // A concurrent request created the row first — reuse it (set PENDING) rather
            // than inserting a duplicate.
            request = friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(currentUser, receiver)
                    .orElseThrow(() -> dup);
            request.setStatus(FriendRequestStatus.PENDING);
            request = friendRequestRepository.save(request);
        }
        log.info("Friend request sent from {} to {}", currentUser.getUsername(), receiver.getUsername());
        broadcastFriendEvent(receiver, "friend_request_received");

        return friendRequestMapper.toResponse(request);
    }

    /**
     * Accept a pending friend request addressed to the caller: mark it ACCEPTED, create the mutual
     * friendship rows, and notify both parties.
     *
     * @param requestUuid uuid of the friend request
     * @param currentUser the receiver accepting it
     * @throws com.neo.chat.exception.NotFoundException  if the request is missing
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not the receiver
     * @throws com.neo.chat.exception.ConflictException  if the request is not PENDING
     */
    @Override
    @Transactional
    public void acceptFriendRequest(String requestUuid, User currentUser) {
        FriendRequest request = friendRequestRepository.findByUuid(UUID.fromString(requestUuid))
                .orElseThrow(() -> new NotFoundException("Friend request not found", "TM_094"));

        if (!request.getReceiver().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot accept request of another user", "TM_103");
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new ConflictException("Request already processed", "TM_096");
        }

        request.setStatus(FriendRequestStatus.ACCEPTED);
        friendRequestRepository.save(request);

        // Save mutual friendships
        Friend friend1 = Friend.builder().user(request.getSender()).friend(request.getReceiver()).build();
        Friend friend2 = Friend.builder().user(request.getReceiver()).friend(request.getSender()).build();
        friendRepository.save(friend1);
        friendRepository.save(friend2);

        broadcastFriendEvent(request.getSender(), "friend_request_accepted");
        broadcastFriendEvent(request.getReceiver(), "friend_request_accepted");

        log.info("Friend request accepted between {} and {}", request.getSender().getUsername(), request.getReceiver().getUsername());
    }

    /**
     * Reject a pending friend request addressed to the caller and notify both parties.
     *
     * @param requestUuid uuid of the friend request
     * @param currentUser the receiver rejecting it
     * @throws com.neo.chat.exception.NotFoundException  if the request is missing
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not the receiver
     * @throws com.neo.chat.exception.ConflictException  if the request is not PENDING
     */
    @Override
    @Transactional
    public void rejectFriendRequest(String requestUuid, User currentUser) {
        FriendRequest request = friendRequestRepository.findByUuid(UUID.fromString(requestUuid))
                .orElseThrow(() -> new NotFoundException("Friend request not found", "TM_094"));

        if (!request.getReceiver().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot reject request of another user", "TM_103");
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new ConflictException("Request already processed", "TM_096");
        }

        request.setStatus(FriendRequestStatus.REJECTED);
        friendRequestRepository.save(request);
        broadcastFriendEvent(request.getSender(), "friend_request_rejected");
        broadcastFriendEvent(request.getReceiver(), "friend_request_rejected");
    }

    /**
     * Cancel a friend request the caller sent, deleting the row and notifying the receiver.
     *
     * @param requestUuid uuid of the friend request
     * @param currentUser the sender cancelling it
     * @throws com.neo.chat.exception.NotFoundException  if the request is missing
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not the sender
     */
    @Override
    @Transactional
    public void cancelFriendRequest(String requestUuid, User currentUser) {
        FriendRequest request = friendRequestRepository.findByUuid(UUID.fromString(requestUuid))
                .orElseThrow(() -> new NotFoundException("Friend request not found", "TM_094"));

        if (!request.getSender().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot cancel request sent by another user", "TM_103");
        }

        friendRequestRepository.delete(request);
        broadcastFriendEvent(request.getReceiver(), "friend_request_cancelled");
    }

    /**
     * List the caller's friends, enriched with live presence, privacy-aware last-seen, and a
     * friends-only messaging flag (resolved in one batched settings query).
     *
     * @param currentUser the authenticated caller
     * @return the caller's friends as response DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public List<AuthUserResponse> getFriends(User currentUser) {
        List<User> friends = friendRepository.findFriendsByUser(currentUser);
        Set<Long> friendsOnlyIds = friends.isEmpty()
                ? Collections.emptySet()
                : userSettingRepository.findFriendsOnlyUserIds(
                friends.stream().map(User::getId).collect(Collectors.toList()));
        return friends.stream()
                .map(friend -> {
                    AuthUserResponse response = userMapper.toAuthUserResponse(friend);
                    if (presenceService != null) {
                        response.setPresence(presenceService.getStatus(friend).name().toLowerCase());
                        // Apparent last-seen: null for Invisible / Hide-last-seen
                        // (privacy rule centralized in PresenceService).
                        Instant lastSeen = presenceService.getApparentLastSeen(friend);
                        if (lastSeen != null) {
                            response.setLastSeen(lastSeen.toString());
                        }
                    }
                    response.setMessagingFriendsOnly(friendsOnlyIds.contains(friend.getId()));
                    return response;
                })
                .collect(Collectors.toList());
    }

    /**
     * List the caller's incoming pending friend requests, newest first.
     *
     * @param currentUser the authenticated caller (receiver)
     * @return pending friend requests as response DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public List<FriendRequestResponse> getFriendRequests(User currentUser) {
        return friendRequestRepository.findByReceiverAndStatusOrderByCreatedAtDesc(currentUser, FriendRequestStatus.PENDING).stream()
                .map(friendRequestMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Remove a friendship in both directions and delete any friend-request rows between the two
     * users (so they can cleanly re-add later), then notify both parties.
     *
     * @param friendUuid  uuid of the friend to remove
     * @param currentUser the authenticated caller
     * @throws com.neo.chat.exception.NotFoundException if the friend user is missing
     */
    @Override
    @Transactional
    public void removeFriend(String friendUuid, User currentUser) {
        User friendUser = userRepository.findByUuid(UUID.fromString(friendUuid))
                .orElseThrow(() -> new NotFoundException("Friend user not found", "TM_064"));

        Friend f1 = friendRepository.findByUserAndFriend(currentUser, friendUser).orElse(null);
        Friend f2 = friendRepository.findByUserAndFriend(friendUser, currentUser).orElse(null);

        if (f1 != null) friendRepository.delete(f1);
        if (f2 != null) friendRepository.delete(f2);

        // Clean up any friend requests so they can add each other again cleanly
        friendRequestRepository.deleteAll(friendRequestRepository.findAllBySenderAndReceiver(currentUser, friendUser));
        friendRequestRepository.deleteAll(friendRequestRepository.findAllBySenderAndReceiver(friendUser, currentUser));

        broadcastFriendEvent(currentUser, "friend_removed");
        broadcastFriendEvent(friendUser, "friend_removed");
    }

    /**
     * Block a user (idempotent): persist the block, evict the caller's {@link BlockCache} entry, and
     * remove any existing friendship between them.
     *
     * @param userUuid    uuid of the user to block
     * @param currentUser the authenticated caller
     * @throws com.neo.chat.exception.NotFoundException   if the target user is missing
     * @throws com.neo.chat.exception.BadRequestException if blocking self
     */
    @Override
    @Transactional
    public void blockUser(String userUuid, User currentUser) {
        User target = userRepository.findByUuid(UUID.fromString(userUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));

        if (target.getId().equals(currentUser.getId())) {
            throw new BadRequestException("Cannot block yourself", "TM_071");
        }

        if (blockUserRepository.existsByUserAndBlocked(currentUser, target)) {
            return; // Already blocked
        }

        BlockUser block = BlockUser.builder()
                .user(currentUser)
                .blocked(target)
                .build();
        blockUserRepository.save(block);
        blockCache.evict(currentUser.getId());

        // Remove friendship if exists
        removeFriend(userUuid, currentUser);
    }

    /**
     * Unblock a user (no-op if not blocked) and evict the caller's {@link BlockCache} entry.
     *
     * @param userUuid    uuid of the user to unblock
     * @param currentUser the authenticated caller
     * @throws com.neo.chat.exception.NotFoundException if the target user is missing
     */
    @Override
    @Transactional
    public void unblockUser(String userUuid, User currentUser) {
        User target = userRepository.findByUuid(UUID.fromString(userUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));

        BlockUser block = blockUserRepository.findByUserAndBlocked(currentUser, target).orElse(null);
        if (block != null) {
            blockUserRepository.delete(block);
            blockCache.evict(currentUser.getId());
        }
    }
}
