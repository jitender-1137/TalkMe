package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.MatchReport;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.BlockedUserResponse;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.MutualFriendsResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.PublicProfileResponse;
import com.chat.talkMe.dto.response.ReputationResponse;
import com.chat.talkMe.dto.response.SmartProfileCardResponse;
import com.chat.talkMe.dto.response.UserResponse;
import com.chat.talkMe.enums.MessagingPrivacy;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.MatchReportRepository;
import com.chat.talkMe.repository.PostRepository;
import com.chat.talkMe.repository.UserFollowRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.service.CompatibilityService;
import com.chat.talkMe.service.NotificationService;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.service.ReputationRecorder;
import com.chat.talkMe.service.ReputationService;
import com.chat.talkMe.service.StorageService;
import com.chat.talkMe.service.StreakService;
import com.chat.talkMe.service.UserService;
import com.chat.talkMe.util.ProfileCompletion;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * User profile, identity and discovery service.
 *
 * <p>Handles fetching/updating the current user, username changes (case-insensitive uniqueness),
 * mood, avatar upload/removal (NSFW-moderated), user lookups (by id/username), the trimmed public
 * profile, the enriched smart profile card, people search, blocked-user and mutual-friend lists,
 * user reports, and the Redis-backed lobby list. Display name and avatar pass content moderation;
 * presence/last-seen/block flags are resolved live (Redis) with privacy masking, and enrichments
 * (reputation, streak, recent posts) are best-effort fail-open decoration.
 */
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
    private final NotificationService notificationService;
    private final ReputationRecorder reputationRecorder;
    private final ReputationService reputationService;
    private final CompatibilityService compatibilityService;
    private final StreakService streakService;

    /**
     * Load the caller's own profile with presence forced to online, current last-seen, and
     * follower/following/post counts populated. Read-only.
     *
     * @param currentUser the caller
     * @return the caller's user response
     * @throws com.chat.talkMe.exception.NotFoundException (TM_024) when the user no longer exists
     */
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

    /**
     * Apply a partial profile update (only non-null request fields change; blank "About me"
     * dropdowns clear their column). Moderates the display name, rejects country changes,
     * recomputes profile completion, and records a PROFILE_COMPLETED reputation event at 100%.
     *
     * @param request     the fields to update
     * @param currentUser the caller
     * @return the updated user response (presence online, counts populated)
     * @throws com.chat.talkMe.exception.NotFoundException (TM_024) when the user no longer exists
     * @throws com.chat.talkMe.exception.ContentModerationException when the display name is explicit
     * @throws com.chat.talkMe.exception.BadRequestException (TM_099) on an attempted country change
     */
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
        // Optional "About me" dropdowns: null ⇒ unchanged, blank ⇒ clear.
        if (request.getBodyType() != null) user.setBodyType(blankToNull(request.getBodyType()));
        if (request.getHairColor() != null) user.setHairColor(blankToNull(request.getHairColor()));
        if (request.getEyeColor() != null) user.setEyeColor(blankToNull(request.getEyeColor()));
        if (request.getRelationshipStatus() != null)
            user.setRelationshipStatus(blankToNull(request.getRelationshipStatus()));
        if (request.getChildren() != null) user.setChildren(blankToNull(request.getChildren()));
        if (request.getDrinking() != null) user.setDrinking(blankToNull(request.getDrinking()));
        if (request.getSmoking() != null) user.setSmoking(blankToNull(request.getSmoking()));
        if (request.getWorkout() != null) user.setWorkout(blankToNull(request.getWorkout()));
        if (request.getZodiac() != null) user.setZodiac(blankToNull(request.getZodiac()));
        if (request.getReligion() != null) user.setReligion(blankToNull(request.getReligion()));
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
        user.setProfileCompletion(ProfileCompletion.compute(user));

        user = userRepository.save(user);
        if (user.getProfileCompletion() >= 100) {
            reputationRecorder.record(user.getId(),
                    ReputationEventType.PROFILE_COMPLETED, String.valueOf(user.getId()));
        }

        UserResponse response = userMapper.toUserResponse(user);
        response.setPresence("online");
        response.setLastSeen(Instant.now().toString());
        populateUserCounts(response, user);
        return response;
    }

    /**
     * Empty/blank → null (so a cleared dropdown clears the column).
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_]{3,30}$");

    /**
     * Change the caller's username after format validation and case-insensitive uniqueness check
     * (soft-deleted names stay reserved; purged names are free). No-op rename when unchanged.
     *
     * @param newUsername the requested username (trimmed)
     * @param currentUser the caller
     * @return the updated user response
     * @throws com.chat.talkMe.exception.NotFoundException (TM_024) when the user no longer exists
     * @throws com.chat.talkMe.exception.BadRequestException (TM_002) on an invalid username format
     * @throws com.chat.talkMe.exception.ConflictException (TM_048) when the username is taken
     */
    @Override
    @Transactional
    public UserResponse changeUsername(String newUsername, User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        String next = newUsername == null ? "" : newUsername.trim();
        if (!USERNAME_PATTERN.matcher(next).matches()) {
            throw new BadRequestException(
                    "Username must be 3–30 characters — letters, numbers and underscores only.", "TM_002");
        }
        // Uniqueness is case-insensitive. A soft-deleted (recoverable) account still
        // holds its real username → reserved; a purged account was renamed to
        // "deleted_<uuid>" → its old name is free. So this check alone gives the
        // "reserve pending-deletion, free fully-deleted" rule. No-op if unchanged.
        if (!next.equalsIgnoreCase(user.getUsername())
                && userRepository.existsByUsernameIgnoreCase(next)) {
            throw new ConflictException(
                    "This username is already taken.", "TM_048");
        }
        user.setUsername(next);
        user = userRepository.save(user);

        UserResponse response = userMapper.toUserResponse(user);
        response.setPresence("online");
        response.setLastSeen(Instant.now().toString());
        populateUserCounts(response, user);
        return response;
    }

    /**
     * Whether {@code username} is free for the caller to take: valid format and either unchanged
     * from their current name or not already used (case-insensitive).
     *
     * @param username    the candidate username
     * @param currentUser the caller
     * @return true if the candidate is available to this user
     */
    @Override
    public boolean isUsernameAvailable(String username, User currentUser) {
        String candidate = username == null ? "" : username.trim();
        if (!USERNAME_PATTERN.matcher(candidate).matches()) return false;
        // Your current name counts as "available" (so the field validates while unchanged).
        if (candidate.equalsIgnoreCase(currentUser.getUsername())) return true;
        return !userRepository.existsByUsernameIgnoreCase(candidate);
    }

    /**
     * Set the caller's mood (parsed to the {@link Mood} enum), stamp the update time, and
     * recompute profile completion.
     *
     * @param moodValue   the mood value (case-insensitive enum name)
     * @param currentUser the caller
     * @return the updated user response
     * @throws com.chat.talkMe.exception.BadRequestException (TM_002) on an invalid mood value
     * @throws com.chat.talkMe.exception.NotFoundException (TM_024) when the user no longer exists
     */
    @Override
    @Transactional
    public UserResponse updateMood(String moodValue, User currentUser) {
        Mood mood;
        try {
            mood = Mood.valueOf(moodValue.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Invalid mood value: " + moodValue, "TM_002");
        }
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        user.setMood(mood);
        user.setMoodUpdatedAt(Instant.now());
        user.setProfileCompletion(ProfileCompletion.compute(user));
        user = userRepository.save(user);
        return userMapper.toUserResponse(user);
    }

    /**
     * Moderate (NSFW) and store a new avatar under {@code profiles/<userUuid>}, update the user,
     * and best-effort notify friends of the photo change (real accounts only; never blocks upload).
     *
     * @param file        the uploaded image
     * @param currentUser the caller
     * @return a single-entry map {@code {"avatarUrl": <url>}}
     * @throws com.chat.talkMe.exception.NotFoundException (TM_024) when the user no longer exists
     * @throws com.chat.talkMe.exception.ContentModerationException when the photo is explicit
     */
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

    /**
     * Clear the caller's profile image (sends no notification, unlike upload).
     *
     * @param currentUser the caller
     * @throws com.chat.talkMe.exception.NotFoundException (TM_024) when the user no longer exists
     */
    @Override
    @Transactional
    public void removeAvatar(User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));

        user.setProfileImage(null);
        userRepository.save(user);
    }

    /**
     * Fetch a user by UUID (or the literal "me"), populated with presence/block status, counts,
     * and a friendship flag relative to the caller. Read-only.
     *
     * @param userId      the target UUID string, or "me"
     * @param currentUser the caller (may be null in some flows)
     * @return the target user response
     * @throws com.chat.talkMe.exception.NotFoundException (TM_USER_NOT_FOUND) when the target is missing
     */
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

    /**
     * Build the PII-free public profile for a shareable {@code /@username} link. Only real, active
     * accounts are reachable (guests/banned/soft-deleted 404). Includes live presence and follower/
     * following/post counts, plus a fail-open cosmetic reputation summary. Read-only.
     *
     * @param username the target username (case-insensitive)
     * @return the trimmed public profile
     * @throws com.chat.talkMe.exception.NotFoundException (TM_USER_NOT_FOUND) when blank, missing,
     *                                                     or not a shareable account
     */
    @Override
    @Transactional(readOnly = true)
    public PublicProfileResponse getPublicProfileByUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new NotFoundException("Profile not found", "TM_USER_NOT_FOUND");
        }
        User u = userRepository.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new NotFoundException("Profile not found", "TM_USER_NOT_FOUND"));
        // A shareable profile must be a real, active account. Guests have no public identity,
        // and banned/soft-deleted accounts must not be reachable via a link.
        if (u.isGuest() || u.isBanned() || u.isDeleted()) {
            throw new NotFoundException("Profile not found", "TM_USER_NOT_FOUND");
        }

        // Reuse the mapper for the correctly-derived avatar/createdAt, then copy ONLY the safe
        // subset into the trimmed DTO (never the phone/roles/age on UserResponse).
        UserResponse base = userMapper.toUserResponse(u);
        PublicProfileResponse resp =
                PublicProfileResponse.builder()
                        .id(base.getId())
                        .name(base.getName())
                        .username(base.getUsername())
                        .avatar(base.getAvatar())
                        .bio(base.getBio())
                        .isVerified(base.isVerified())
                        .createdAt(base.getCreatedAt())
                        .presence(presenceService.getStatus(u).name().toLowerCase())
                        .followersCount(userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(u, "ACCEPTED"))
                        .followingCount(userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(u, "ACCEPTED"))
                        .postsCount(postRepository.countVisibleByUser(u))
                        .build();

        // Cosmetic reputation summary — fail-open (decoration only; a link must still render
        // if the reputation lookup hiccups).
        try {
            ReputationResponse rep =
                    reputationService.getFor(u.getUuid().toString());
            resp.setLevel(rep.getLevel());
            resp.setStarRank(rep.getStarRank());
            resp.setPrestigeCount(rep.getPrestigeCount());
        } catch (Exception ignored) {
            // leave level=0/starRank=null — client shows no rep chip
        }
        return resp;
    }

    /**
     * Build the enriched smart profile card for a target: reuses the mapped user response, mutual-
     * friend count, and a compatibility score (viewer re-loaded as a managed entity to avoid a
     * LazyInit), plus fail-open online-streak and recent-public-post enrichments. Read-only.
     *
     * @param userId      the target UUID string
     * @param currentUser the viewer
     * @return the assembled smart profile card
     * @throws com.chat.talkMe.exception.NotFoundException (TM_024) when the target is missing
     */
    @Override
    @Transactional(readOnly = true)
    public SmartProfileCardResponse getSmartProfileCard(String userId, User currentUser) {
        User target = userRepository.findByUuid(UUID.fromString(userId))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        // Reuse the fully-mapped UserResponse (presence, lastSeen, all string sets) as the base.
        UserResponse ur = getUserById(userId, currentUser);
        int mutual = getMutualFriends(userId, currentUser).getCount();
        // Re-load the viewer as a MANAGED entity within this readOnly tx — the security
        // principal is detached, so scoring against it would hit a LazyInit on personality.
        User viewer = userRepository.findById(currentUser.getId()).orElse(currentUser);
        CompatibilityScore compat =
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
            Instant since = Instant.now()
                    .minus(30, ChronoUnit.DAYS);
            long c = postRepository.countRecentPublicByUser(target, since);
            if (c > 0) {
                recentPublicPosts = (int) c;
            }
        } catch (Exception e) {
            log.debug("Smart card recent-posts count failed for {}: {}", userId, e.getMessage());
        }

        return SmartProfileCardResponse.builder()
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

    /**
     * Page-cursor search over username/name/email (case-insensitive LIKE), excluding self and any
     * guest or soft-deleted accounts, sorted by name. Each hit is enriched with presence/block
     * status and counts. The cursor is the next page index. Read-only.
     *
     * @param query       the search term (>= 2 chars)
     * @param limit       page size
     * @param cursor      the page index as a string (null/blank = first page)
     * @param currentUser the caller (excluded from results)
     * @return a paginated page of matching users
     * @throws com.chat.talkMe.exception.BadRequestException (TM_070) when the query is too short
     */
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
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.notEqual(root.get("id"), currentUser.getId()));
            // Never surface soft-deleted / deletion-requested accounts (both carry
            // isDeleted=true) or guest sessions in people search / discover.
            preds.add(cb.equal(root.get("isDeleted"), false));
            preds.add(cb.equal(root.get("isGuest"), false));
            preds.add(cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)));
            return cb.and(preds.toArray(new Predicate[0]));
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

    /**
     * List the users the caller has blocked (single, non-paginated page). Read-only.
     *
     * @param currentUser the caller
     * @return the blocked users wrapped as a paginated response
     */
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

    /**
     * File a moderation report against a target user, deduping to one PENDING report per
     * reporter/target pair.
     *
     * @param userId      the reported user's UUID string
     * @param reason      the report reason (defaults to "other" when null)
     * @param description free-text details
     * @param currentUser the reporter
     * @throws com.chat.talkMe.exception.NotFoundException (TM_USER_NOT_FOUND) when the target is missing
     * @throws com.chat.talkMe.exception.ConflictException (TM_182) when a pending report already exists
     */
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
            throw new ConflictException(
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

    /**
     * Compute the mutual friends between the caller and a target user (intersection of both
     * friend lists), each enriched with presence/block status. Read-only.
     *
     * @param userId      the target user's UUID string
     * @param currentUser the caller
     * @return the mutual friends and their count
     * @throws com.chat.talkMe.exception.NotFoundException (TM_USER_NOT_FOUND) when the target is missing
     */
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

    /**
     * Fill in block status, messaging-privacy flags (friends-only + whether the viewer can
     * message), and live presence/last-seen (Redis) with Invisible/Hide-last-seen masking; the
     * owner sees their own real last-seen.
     *
     * @param response    the response to mutate
     * @param currentUser the viewer (may be null)
     * @param targetUser  the user being described
     */
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
            Instant own = presenceService.getLastSeen(targetUser);
            response.setLastSeen(own != null ? own.toString() : null);
        } else {
            // Others: apparent last-seen, nulled for Invisible / Hide-last-seen
            // (single privacy rule in PresenceService — previously this missed
            // hide-last-seen, leaking the timestamp).
            Instant apparent = presenceService.getApparentLastSeen(targetUser);
            response.setLastSeen(apparent != null ? apparent.toString() : null);
        }
    }

    /**
     * Populate follower, following (accepted, non-deleted) and visible-post counts on the response.
     *
     * @param response the response to mutate
     * @param user     the user whose counts are computed
     */
    private void populateUserCounts(UserResponse response, User user) {
        long followers = userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(user, "ACCEPTED");
        long following = userFollowRepository.countByFollowerAndStatusAndIsDeletedFalse(user, "ACCEPTED");
        long posts = postRepository.countVisibleByUser(user);
        response.setFollowersCount(followers);
        response.setFollowingCount(following);
        response.setPostsCount(posts);
    }

    /**
     * List users currently in the lobby (from the Redis {@code lobby:users} set), excluding the
     * caller, each enriched with presence/block status and counts. Read-only.
     *
     * @param currentUser the caller (excluded; may be null)
     * @return the lobby users (empty when the set is empty)
     */
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
