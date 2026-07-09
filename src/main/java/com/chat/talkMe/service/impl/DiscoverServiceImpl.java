package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.DiscoverLike;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.DiscoverProfileResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.DiscoverLikeRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.FriendRequestRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.service.DiscoverService;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.domain.FriendRequest;
import com.chat.talkMe.enums.FriendRequestStatus;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscoverServiceImpl implements DiscoverService {

    private final UserRepository userRepository;
    private final DiscoverLikeRepository discoverLikeRepository;
    private final FriendRepository friendRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final UserSettingRepository userSettingRepository;
    private final PresenceService presenceService;

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<DiscoverProfileResponse> getDiscover(
            String query,
            String interests,
            Double distance,
            Boolean verified,
            Boolean isOnline,
            String cursor,
            int limit,
            Integer minAge,
            Integer maxAge,
            String gender,
            String country,
            User currentUser
    ) {
        int page = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                page = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                // Ignore and use default
            }
        }

        // Online-first ordering is applied at the DB layer inside the Specification
        // below (via a CASE on the live online set), so it holds ACROSS pagination —
        // not just within a page. The old static Sort used the `onlineSortWeight`
        // column, which is never written, so it always collapsed to name-asc
        // (alphabetical). Presence is Redis-authoritative, hence the live set here.
        Pageable pageable = PageRequest.of(page, limit);

        // Apparent-online / apparent-away usernames (Invisible-masked) — exactly the
        // users shown with a green / amber dot. Used to bucket the list into
        // ONLINE → AWAY → offline tiers at the DB layer (holds across pagination).
        Set<String> onlineUsernames = presenceService.getOnlineUsernames();
        Set<String> awayUsernames = presenceService.getAwayUsernames();

        Set<Interest> interestEnums = new HashSet<>();
        if (interests != null && !interests.isBlank()) {
            for (String interestStr : interests.split(",")) {
                try {
                    interestEnums.add(Interest.valueOf(interestStr.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    // Ignore invalid enums
                }
            }
        }

        Specification<User> spec = (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Exclude currentUser
            predicates.add(cb.notEqual(root.get("id"), currentUser.getId()));

            // Exclude guest users
            predicates.add(cb.equal(root.get("isGuest"), false));

            // Exclude soft-deleted / deactivated accounts (there is no global
            // @SQLRestriction on User, so this must be applied explicitly).
            predicates.add(cb.equal(root.get("isDeleted"), false));

            // Search query filter
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)
                ));
            }

            // Verified filter
            if (verified != null) {
                predicates.add(cb.equal(root.get("isVerified"), verified));
            }

            // Interests filter
            if (!interestEnums.isEmpty()) {
                predicates.add(root.join("interests").in(interestEnums));
            }

            // Age filters
            if (minAge != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("age"), minAge));
            }
            if (maxAge != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("age"), maxAge));
            }

            // Gender filter
            if (gender != null && !gender.isBlank() && !gender.equalsIgnoreCase("all") && !gender.equalsIgnoreCase("any")) {
                predicates.add(cb.equal(cb.lower(root.get("gender")), gender.toLowerCase().trim()));
            }

            // Country filter
            if (country != null && !country.isBlank() && !country.equalsIgnoreCase("all") && !country.equalsIgnoreCase("any")) {
                predicates.add(cb.equal(cb.lower(root.get("country")), country.toLowerCase().trim()));
            }

            // Ordering: ONLINE first, then AWAY (idle), then everyone else ranked by
            // how recently they were last active (last_seen_at desc). Skip on the COUNT
            // query (Long result type), where an ORDER BY is invalid and unnecessary.
            if (q.getResultType() != Long.class) {
                // Durable last-seen as a scalar (@Formula subquery). Ordering by the
                // inverse presence association isn't emitted by Hibernate, so we use
                // this scalar — which is. last_seen_at is written when a user goes
                // offline, so it's the accurate "recently active" signal for them.
                jakarta.persistence.criteria.Expression<java.time.Instant> lastSeen =
                        root.get("presenceLastSeenAt");

                // Presence tier: ONLINE = 0 (top), AWAY/idle = 1, everyone else = 2.
                // Both live sets are Redis-authoritative (Invisible-masked); we pin by
                // them, NOT by last_seen_at, because a live user's DB last_seen_at is
                // stale until they actually go offline.
                jakarta.persistence.criteria.Expression<Integer> presenceRank;
                if (onlineUsernames.isEmpty() && awayUsernames.isEmpty()) {
                    presenceRank = cb.literal(2);
                } else {
                    jakarta.persistence.criteria.CriteriaBuilder.Case<Integer> tier = cb.<Integer>selectCase();
                    if (!onlineUsernames.isEmpty()) {
                        tier = tier.when(root.get("username").in(onlineUsernames), 0);
                    }
                    if (!awayUsernames.isEmpty()) {
                        tier = tier.when(root.get("username").in(awayUsernames), 1);
                    }
                    presenceRank = tier.otherwise(2);
                }

                // Nulls-last: users with no presence row (never seen) sort after those
                // with a real last-seen, instead of first (Postgres puts NULLs first on
                // DESC). Then most-recently-active first, name as the final tiebreaker.
                jakarta.persistence.criteria.Expression<Integer> lastSeenNullRank =
                        cb.<Integer>selectCase()
                            .when(cb.isNull(lastSeen), 1)
                            .otherwise(0);

                q.orderBy(
                        cb.asc(presenceRank),
                        cb.asc(lastSeenNullRank),
                        cb.desc(lastSeen),
                        cb.asc(root.get("name")));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<User> userPage = userRepository.findAll(spec, pageable);
        List<User> currentUserFriends = friendRepository.findFriendsByUser(currentUser);
        Set<Long> friendsOnlyIds = userPage.getContent().isEmpty()
                ? java.util.Collections.emptySet()
                : userSettingRepository.findFriendsOnlyUserIds(
                        userPage.getContent().stream().map(User::getId).collect(Collectors.toList()));

        List<DiscoverProfileResponse> items = userPage.getContent().stream()
                .map(u -> {
                    List<User> targetUserFriends = friendRepository.findFriendsByUser(u);
                    Set<Long> targetFriendIds = targetUserFriends.stream()
                            .map(User::getId)
                            .collect(Collectors.toSet());

                    int mutualCount = (int) currentUserFriends.stream()
                            .filter(friend -> targetFriendIds.contains(friend.getId()))
                            .count();

                    boolean online = presenceService.getStatus(u) == PresenceStatus.ONLINE;
                    boolean liked = discoverLikeRepository.existsByUserAndLikedUser(currentUser, u);
                    boolean isFriend = friendRepository.findByUserAndFriend(currentUser, u).isPresent();

                    java.util.Optional<FriendRequest> reqOpt = friendRequestRepository.findBySenderAndReceiver(currentUser, u);
                    boolean requestSent = false;
                    String pendingReqId = null;
                    if (reqOpt.isPresent() && reqOpt.get().getStatus() == FriendRequestStatus.PENDING) {
                        requestSent = true;
                        pendingReqId = reqOpt.get().getUuid().toString();
                    }

                    String locationStr = "";
                    if (u.getCity() != null) {
                        locationStr += u.getCity();
                    }
                    if (u.getCountry() != null) {
                        if (!locationStr.isEmpty()) {
                            locationStr += ", ";
                        }
                        locationStr += u.getCountry();
                    }

                    List<String> images = new ArrayList<>();
                    if (u.getProfileImage() != null) {
                        images.add(u.getProfileImage());
                    }

                    Set<String> userInterests = u.getInterests().stream()
                            .map(Enum::name)
                            .collect(Collectors.toSet());

                    return DiscoverProfileResponse.builder()
                            .id(u.getUuid().toString())
                            .name(u.getName())
                            .age(u.getAge())
                            .gender(u.getGender())
                            .username(u.getUsername())
                            .bio(u.getBio())
                            .location(locationStr)
                            .city(u.getCity())
                            .country(u.getCountry())
                            .distance("2 miles away")
                            .distanceKm(3.2)
                            .occupation(u.getOccupation())
                            .education(u.getEducation())
                            .interests(userInterests)
                            .images(images)
                            .isVerified(u.isVerified())
                            .isOnline(online)
                            .isLiked(liked)
                            .isFriend(isFriend)
                            .mutualFriendsCount(mutualCount)
                            .isRequestSent(requestSent)
                            .pendingRequestId(pendingReqId)
                            .messagingFriendsOnly(friendsOnlyIds.contains(u.getId()))
                            .build();
                })
                .filter(item -> isOnline == null || item.isOnline() == isOnline)
                .collect(Collectors.toList());

        return PaginatedResponse.<DiscoverProfileResponse>builder()
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
    @Transactional
    public void likeProfile(String userId, User currentUser) {
        User targetUser = userRepository.findByUuid(UUID.fromString(userId))
                .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId, "TM_USER_NOT_FOUND"));

        if (discoverLikeRepository.existsByUserAndLikedUser(currentUser, targetUser)) {
            return;
        }

        DiscoverLike like = DiscoverLike.builder()
                .user(currentUser)
                .likedUser(targetUser)
                .build();

        discoverLikeRepository.save(like);
    }

    @Override
    @Transactional
    public void unlikeProfile(String userId, User currentUser) {
        User targetUser = userRepository.findByUuid(UUID.fromString(userId))
                .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId, "TM_USER_NOT_FOUND"));

        discoverLikeRepository.findByUserAndLikedUser(currentUser, targetUser)
                .ifPresent(discoverLikeRepository::delete);
    }
}
