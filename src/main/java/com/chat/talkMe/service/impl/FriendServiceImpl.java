package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.FriendRequestResponse;
import com.chat.talkMe.enums.FriendRequestStatus;
import com.chat.talkMe.exception.*;
import com.chat.talkMe.mapper.FriendRequestMapper;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.*;
import com.chat.talkMe.service.FriendService;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

    private final UserRepository userRepository;
    private final FriendRepository friendRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final BlockUserRepository blockUserRepository;
    private final FriendRequestMapper friendRequestMapper;
    private final UserMapper userMapper;
    private final PresenceService presenceService;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    private void broadcastFriendEvent(User user, String eventType) {
        try {
            java.util.Map<String, String> payload = new java.util.HashMap<>();
            payload.put("event", eventType);
            messagingTemplate.convertAndSendToUser(user.getUsername(), "/queue/friends", payload);
        } catch (Exception e) {
            log.error("Failed to broadcast friend event to user {}", user.getUsername(), e);
        }
    }

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
        java.util.Optional<FriendRequest> existingRequestOpt = friendRequestRepository.findBySenderAndReceiver(currentUser, receiver);
        if (existingRequestOpt.isPresent()) {
            FriendRequest existingRequest = existingRequestOpt.get();
            if (existingRequest.getStatus() == FriendRequestStatus.ACCEPTED) {
                // If status is ACCEPTED but they are not friends (checked above), 
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
        java.util.Optional<FriendRequest> reverseRequestOpt = friendRequestRepository.findBySenderAndReceiver(receiver, currentUser);
        if (reverseRequestOpt.isPresent() && reverseRequestOpt.get().getStatus() == FriendRequestStatus.PENDING) {
            // Auto-accept if the other user already sent one
            acceptFriendRequest(reverseRequestOpt.get().getUuid().toString(), currentUser);
            return friendRequestMapper.toResponse(reverseRequestOpt.get());
        }

        FriendRequest request = FriendRequest.builder()
                .sender(currentUser)
                .receiver(receiver)
                .status(FriendRequestStatus.PENDING)
                .build();

        request = friendRequestRepository.save(request);
        log.info("Friend request sent from {} to {}", currentUser.getUsername(), receiver.getUsername());
        broadcastFriendEvent(receiver, "friend_request_received");

        return friendRequestMapper.toResponse(request);
    }

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

    @Override
    @Transactional(readOnly = true)
    public List<AuthUserResponse> getFriends(User currentUser) {
        return friendRepository.findFriendsByUser(currentUser).stream()
                .map(friend -> {
                    AuthUserResponse response = userMapper.toAuthUserResponse(friend);
                    if (presenceService != null) {
                        response.setPresence(presenceService.getStatus(friend).name().toLowerCase());
                        // Live last-seen from Redis (DB value is stale — only written on OFFLINE).
                        java.time.Instant lastSeen = presenceService.getLastSeen(friend);
                        if (lastSeen != null) {
                            response.setLastSeen(lastSeen.toString());
                        }
                    }
                    return response;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FriendRequestResponse> getFriendRequests(User currentUser) {
        return friendRequestRepository.findByReceiverAndStatus(currentUser, FriendRequestStatus.PENDING).stream()
                .map(friendRequestMapper::toResponse)
                .collect(Collectors.toList());
    }

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
        friendRequestRepository.findBySenderAndReceiver(currentUser, friendUser).ifPresent(friendRequestRepository::delete);
        friendRequestRepository.findBySenderAndReceiver(friendUser, currentUser).ifPresent(friendRequestRepository::delete);

        broadcastFriendEvent(currentUser, "friend_removed");
        broadcastFriendEvent(friendUser, "friend_removed");
    }

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

        // Remove friendship if exists
        removeFriend(userUuid, currentUser);
    }

    @Override
    @Transactional
    public void unblockUser(String userUuid, User currentUser) {
        User target = userRepository.findByUuid(UUID.fromString(userUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));

        BlockUser block = blockUserRepository.findByUserAndBlocked(currentUser, target).orElse(null);
        if (block != null) {
            blockUserRepository.delete(block);
        }
    }
}
