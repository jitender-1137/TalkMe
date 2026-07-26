package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.MatchReport;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.BlockedUserResponse;
import com.chat.talkMe.dto.response.MutualFriendsResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.UserResponse;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.enums.MessagingPrivacy;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.MatchReportRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.repository.UserFollowRepository;
import com.chat.talkMe.repository.PostRepository;
import com.chat.talkMe.service.UserService;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final FriendRepository friendRepository;
    private final UserSettingRepository userSettingRepository;
    private final BlockUserRepository blockUserRepository;
    private final MatchReportRepository matchReportRepository;
    private final PresenceService presenceService;
    private final StorageService storageService;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final UserFollowRepository userFollowRepository;
    private final PostRepository postRepository;
    private final ContentModerationService moderationService;
    private final com.chat.talkMe.service.NotificationService notificationService;
    private final com.chat.talkMe.service.ReputationRecorder reputationRecorder;
    private final com.chat.talkMe.service.CompatibilityService compatibilityService;
    private final com.chat.talkMe.service.StreakService streakService;

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        UserResponse response = userMapper.toUserResponse(user);
        response.setPresence("online");
        response.setLastSeen(Instant.now().toString());
        populateUserCounts(response, user);
        return response;
    }

    @Override
    @Transactional
    public UserResponse updateProfile(UpdateProfileRequest request, User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));

        if (request.getName() != null) {
            // Display name is publicly visible everywhere — must stay clean.
            if (moderationService.moderateText(request.getName()).isExplicit()) {
                throw new ContentModerationException(
                        "Your display name contains content that violates our community guidelines.");
            }
            user.setName(request.getName());
        }
        if (request.getProfileImage() != null) {
            user.setProfileImage(request.getProfileImage());
        }
        if (request.getCountry() != null && !request.getCountry().equals(user.getCountry())) {
            throw new BadRequestException("Country cannot be updated", "TM_099");
        }
        if (request.getCity() != null) {
            user.setCity(request.getCity());
        }
        if (request.getMobileNumber() != null) {
            user.setMobileNumber(request.getMobileNumber());
        }
        if (request.getPhone() != null) {
            user.setMobileNumber(request.getPhone());
        }
        if (request.getAge() != null) {
            user.setAge(request.getAge());
        }
        if (request.getGender() != null && !request.getGender().isBlank()) {
            user.setGender(request.getGender());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getOccupation() != null) {
            user.setOccupation(request.getOccupation());
        }
        if (request.getEducation() != null) {
            user.setEducation(request.getEducation());
        }
        if (request.getInterests() != null) {
            user.getInterests().clear();
            user.getInterests().addAll(request.getInterests());
        }
        // ── Late-Night Social attributes ──
        if (request.getMood() != null) {
            user.setMood(request.getMood());
            user.setMoodUpdatedAt(Instant.now());
        }
        if (request.getConversationEnergy() != null) {
            user.setConversationEnergy(request.getConversationEnergy());
        }
        if (request.getLanguages() != null) {
            user.getLanguages().clear();
            user.getLanguages().addAll(request.getLanguages());
        }
        if (request.getLookingFor() != null) {
            user.getLookingFor().clear();
            user.getLookingFor().addAll(request.getLookingFor());
        }
        if (request.getPersonality() != null) {
            user.getPersonality().clear();
            for (var e : request.getPersonality().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    user.getPersonality().put(e.getKey(), Math.max(0, Math.min(100, e.getValue())));
                }
            }
        }
        if (request.getVoiceIntroUrl() != null) {
            user.setVoiceIntroUrl(request.getVoiceIntroUrl());
        }
        if (request.getVoiceIntroDurationMs() != null) {
            user.setVoiceIntroDurationMs(request.getVoiceIntroDurationMs());
        }
        user.setProfileCompletion(com.chat.talkMe.util.ProfileCompletion.compute(user));

        user = userRepository.save(user);
        if (user.getProfileCompletion() >= 100) {
            reputationRecorder.record(user.getId(),
                    com.chat.talkMe.enums.ReputationEventType.PROFILE_COMPLETED, String.valueOf(user.getId()));
        }

        UserResponse response = userMapper.toUserResponse(user);
        response.setPresence("online");
        response.setLastSeen(Instant.now().toString());
        populateUserCounts(response, user);
        return response;
    }

    @Override
    @Transactional
    public UserResponse updateMood(String moodValue, User currentUser) {
        com.chat.talkMe.enums.Mood mood;
        try {
            mood = com.chat.talkMe.enums.Mood.valueOf(moodValue.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Invalid mood value: " + moodValue, "TM_002");
        }
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        user.setMood(mood);
        user.setMoodUpdatedAt(Instant.now());
        user.setProfileCompletion(com.chat.talkMe.util.ProfileCompletion.compute(user));
        user = userRepository.save(user);
        return userMapper.toUserResponse(user);
    }

    @Override
    @Transactional
    public Map<String, String> uploadAvatar(MultipartFile file, User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));

        // Profile photos are publicly visible — reject NSFW before storing.
        if (moderationService.moderateUpload(file).isExplicit()) {
            throw new ContentModerationException(
                    "This profile photo violates our community guidelines and can't be used.");
        }

        // Avatars live under profiles/<userUuid> (server-derived id → traversal-safe).
        String avatarUrl = storageService.storeFile(file, "avatar", "profiles/" + user.getUuid());
        boolean isNewPhoto = user.getProfileImage() == null || !user.getProfileImage().equals(avatarUrl);
        user.setProfileImage(avatarUrl);
        userRepository.save(user);

        // Tell the user's friends they updated their profile photo (only real accounts
        // do this — guests have no friends graph). Removal is handled by removeAvatar,
        // which intentionally sends NO notification. Best-effort; never blocks the upload.
        if (isNewPhoto && !user.isGuest()) {
            try {
                notificationService.notifyFriends(
                        user,
                        "New profile photo",
                        user.getName() + " updated their profile photo.",
                        "PROFILE_PHOTO",
                        user.getUuid().toString(),
                        avatarUrl);
            } catch (Exception e) {
                log.warn("Failed to notify friends of profile-photo change for {}", user.getUsername(), e);
            }
        }

        return Map.of("avatarUrl", avatarUrl);
    }

    @Override
    @Transactional
    public void removeAvatar(User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));

        user.setProfileImage(null);
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(String userId, User currentUser) {
        User targetUser;
        if ("me".equalsIgnoreCase(userId)) {
            targetUser = currentUser;
        } else {
            targetUser = userRepository.findByUuid(UUID.fromString(userId))
                    .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId, "TM_USER_NOT_FOUND"));
        }

        UserResponse response = userMapper.toUserResponse(targetUser);
        populatePresenceAndBlockStatus(response, currentUser, targetUser);
        populateUserCounts(response, targetUser);
        // Friendship flag — cheap lookup (mirrors the canMessage check). Lets the client
        // gate friends-only UI (e.g. the relationship journey) instead of firing a
        // request that would 403. Self is never "a friend".
        boolean friend = currentUser != null
                && !currentUser.getId().equals(targetUser.getId())
                && friendRepository.findByUserAndFriend(currentUser, targetUser).isPresent();
        response.setFriend(friend);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public com.chat.talkMe.dto.response.SmartProfileCardResponse getSmartProfileCard(String userId, User currentUser) {
        User target = userRepository.findByUuid(UUID.fromString(userId))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        // Reuse the fully-mapped UserResponse (presence, lastSeen, all string sets) as the base.
        UserResponse ur = getUserById(userId, currentUser);
        int mutual = getMutualFriends(userId, currentUser).getCount();
        // Re-load the viewer as a MANAGED entity within this readOnly tx — the security
        // principal is detached, so scoring against it would hit a LazyInit on personality.
        User viewer = userRepository.findById(currentUser.getId()).orElse(currentUser);
        com.chat.talkMe.dto.response.CompatibilityScore compat =
                compatibilityService.score(viewer, target);

        // Best-effort, fail-open enrichments (P3.5) — the card must render even if either lookup
        // throws. Both are decoration only; null omits the pill/stat on the client.
        Integer onlineStreak = null;
        try {
            int s = streakService.getStreak(target).getCurrentStreak();
            if (s > 0) {
                onlineStreak = s;
            }
        } catch (Exception e) {
            log.debug("Smart card streak lookup failed for {}: {}", userId, e.getMessage());
        }
        Integer recentPublicPosts = null;
        try {
            java.time.Instant since = java.time.Instant.now()
                    .minus(30, java.time.temporal.ChronoUnit.DAYS);
            long c = postRepository.countRecentPublicByUser(target, since);
            if (c > 0) {
                recentPublicPosts = (int) c;
            }
        } catch (Exception e) {
            log.debug("Smart card recent-posts count failed for {}: {}", userId, e.getMessage());
        }

        return com.chat.talkMe.dto.response.SmartProfileCardResponse.builder()
                .id(ur.getId())
                .name(ur.getName())
                .username(ur.getUsername())
                .avatar(ur.getAvatar())
                .age(ur.getAge())
                .country(ur.getCountry())
                .city(ur.getCity())
                .mood(ur.getMood())
                .conversationEnergy(ur.getConversationEnergy())
                .interests(ur.getInterests())
                .lookingFor(ur.getLookingFor())
                .languages(ur.getLanguages())
                .voiceIntroUrl(ur.getVoiceIntroUrl())
                .voiceIntroDurationMs(ur.getVoiceIntroDurationMs())
                .profileCompletion(ur.getProfileCompletion())
                .presence(ur.getPresence())
                .lastSeen(ur.getLastSeen())
                .mutualFriendsCount(mutual)
                .onlineStreak(onlineStreak)
                .recentPublicPosts(recentPublicPosts)
                .compatibility(compat)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<UserResponse> searchUsers(String query, int limit, String cursor, User currentUser) {
        if (query == null || query.trim().length() < 2) {
            throw new BadRequestException("Query must be at least 2 characters", "TM_070");
        }

        int page = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                page = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                // Ignore and use default
            }
        }

        Pageable pageable = PageRequest.of(page, limit, Sort.by("name").ascending());

        Specification<User> spec = (root, q, cb) -> {
            String pattern = "%" + query.toLowerCase() + "%";
            List<jakarta.persistence.criteria.Predicate> preds = new java.util.ArrayList<>();
            preds.add(cb.notEqual(root.get("id"), currentUser.getId()));
            // Never surface soft-deleted / deletion-requested accounts (both carry
            // isDeleted=true) or guest sessions in people search / discover.
            preds.add(cb.equal(root.get("isDeleted"), false));
            preds.add(cb.equal(root.get("isGuest"), false));
            preds.add(cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)));
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<User> userPage = userRepository.findAll(spec, pageable);

        List<UserResponse> items = userPage.getContent().stream()
                .map(u -> {
                    UserResponse res = userMapper.toUserResponse(u);
                    populatePresenceAndBlockStatus(res, currentUser, u);
                    populateUserCounts(res, u);
                    return res;
                })
                .collect(Collectors.toList());

        return PaginatedResponse.<UserResponse>builder()
                .items(items)
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor(userPage.hasNext() ? String.valueOf(page + 1) : null)
                        .hasNext(userPage.hasNext())
                        .hasPrevious(userPage.hasPrevious())
                        .total(userPage.getTotalElements())
                        .build())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<BlockedUserResponse> getBlockedUsers(User currentUser) {
        List<BlockUser> blockedList = blockUserRepository.findByUser(currentUser);

        List<BlockedUserResponse> items = blockedList.stream()
                .map(b -> BlockedUserResponse.builder()
                        .id(b.getBlocked().getUuid().toString())
                        .name(b.getBlocked().getName())
                        .avatar(b.getBlocked().getProfileImage())
                        .blockedAt(b.getCreatedAt() != null ? b.getCreatedAt().toString() : Instant.now().toString())
                        .build())
                .collect(Collectors.toList());

        return PaginatedResponse.<BlockedUserResponse>builder()
                .items(items)
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor(null)
                        .hasNext(false)
                        .hasPrevious(false)
                        .total((long) items.size())
                        .build())
                .build();
    }

    @Override
    @Transactional
    public void reportUser(String userId, String reason, String description, User currentUser) {
        User targetUser = userRepository.findByUuid(UUID.fromString(userId))
                .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId, "TM_USER_NOT_FOUND"));

        // One OPEN report per (reporter → reported): don't let the same person pile up
        // duplicate pending reports. Once a moderator resolves/dismisses it, they can
        // report again if the behaviour recurs.
        if (matchReportRepository.existsByReporterIdAndReportedIdAndStatus(
                currentUser.getId(), targetUser.getId(), "PENDING")) {
            throw new com.chat.talkMe.exception.ConflictException(
                    "You've already reported this user — it's under review.", "TM_182");
        }

        MatchReport report = MatchReport.builder()
                .reporter(currentUser)
                .reported(targetUser)
                .reason(reason != null ? reason : "other")
                .details(description)
                .build();

        matchReportRepository.save(report);
    }

    @Override
    @Transactional(readOnly = true)
    public MutualFriendsResponse getMutualFriends(String userId, User currentUser) {
        User targetUser = userRepository.findByUuid(UUID.fromString(userId))
                .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId, "TM_USER_NOT_FOUND"));

        List<User> currentUserFriends = friendRepository.findFriendsByUser(currentUser);
        List<User> targetUserFriends = friendRepository.findFriendsByUser(targetUser);

        Set<Long> targetFriendIds = targetUserFriends.stream()
                .map(User::getId)
                .collect(Collectors.toSet());

        List<UserResponse> mutualUsers = currentUserFriends.stream()
                .filter(friend -> targetFriendIds.contains(friend.getId()))
                .map(friend -> {
                    UserResponse res = userMapper.toUserResponse(friend);
                    populatePresenceAndBlockStatus(res, currentUser, friend);
                    return res;
                })
                .collect(Collectors.toList());

        return MutualFriendsResponse.builder()
                .count(mutualUsers.size())
                .users(mutualUsers)
                .build();
    }

    private void populatePresenceAndBlockStatus(UserResponse response, User currentUser, User targetUser) {
        boolean isBlocked = false;
        if (currentUser != null) {
            isBlocked = blockUserRepository.existsByUserAndBlocked(currentUser, targetUser)
                    || blockUserRepository.existsByUserAndBlocked(targetUser, currentUser);
        }
        response.setBlocked(isBlocked);

        // "Who can message me": expose whether this user restricts messages to friends
        // (drives the lock badge on their avatar), and whether the viewer specifically
        // can message them. canMessage mirrors the hard enforcement in
        // MessageServiceImpl.sendMessage: blocked only when friends-only AND not a friend.
        MessagingPrivacy privacy = userSettingRepository.findByUser(targetUser)
                .map(UserSetting::getMessagingPrivacy)
                .orElse(MessagingPrivacy.EVERYONE);
        boolean friendsOnly = privacy == MessagingPrivacy.FRIENDS_ONLY;
        response.setMessagingFriendsOnly(friendsOnly);

        boolean canMessage = true;
        if (friendsOnly && currentUser != null && !currentUser.getId().equals(targetUser.getId())) {
            canMessage = friendRepository.findByUserAndFriend(currentUser, targetUser)
                    .map(f -> !f.isDeleted())
                    .orElse(false);
        }
        response.setCanMessage(canMessage);

        // Live status + last-seen come from Redis (the DB values are stale by design —
        // only written on OFFLINE). Status is Invisible-masked via getStatus.
        PresenceStatus apparentStatus = presenceService.getStatus(targetUser);
        response.setPresence(apparentStatus.name().toLowerCase());

        if (currentUser != null && currentUser.getId().equals(targetUser.getId())) {
            // Owner sees their own real last-seen.
            java.time.Instant own = presenceService.getLastSeen(targetUser);
            response.setLastSeen(own != null ? own.toString() : null);
        } else {
            // Others: apparent last-seen, nulled for Invisible / Hide-last-seen
            // (single privacy rule in PresenceService — previously this missed
            // hide-last-seen, leaking the timestamp).
            java.time.Instant apparent = presenceService.getApparentLastSeen(targetUser);
            response.setLastSeen(apparent != null ? apparent.toString() : null);
        }
    }

    private void populateUserCounts(UserResponse response, User user) {
        long followers = userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(user, "ACCEPTED");
        long following = userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(user, "ACCEPTED");
        long posts = postRepository.countVisibleByUser(user);
        response.setFollowersCount(followers);
        response.setFollowingCount(following);
        response.setPostsCount(posts);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getLobbyUsers(User currentUser) {
        Set<String> lobbyUsernames = redisTemplate.opsForSet().members("lobby:users");
        if (lobbyUsernames == null || lobbyUsernames.isEmpty()) {
            return Collections.emptyList();
        }

        List<User> lobbyUsers = userRepository.findAllByUsernameInExcludeSelf(lobbyUsernames, currentUser != null ? currentUser.getId() : null);
        return lobbyUsers.stream()
                .map(u -> {
                    UserResponse res = userMapper.toUserResponse(u);
                    populatePresenceAndBlockStatus(res, currentUser, u);
                    populateUserCounts(res, u);
                    return res;
                })
                .collect(Collectors.toList());
    }
}
