package com.neo.chat.service.impl;

import com.neo.chat.cache.FriendCache;
import com.neo.chat.domain.DiscoverLike;
import com.neo.chat.domain.FriendRequest;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.DiscoverProfileResponse;
import com.neo.chat.dto.response.PaginatedResponse;
import com.neo.chat.enums.FriendRequestStatus;
import com.neo.chat.enums.Interest;
import com.neo.chat.enums.PresenceStatus;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.DiscoverLikeRepository;
import com.neo.chat.repository.FriendRequestRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link DiscoverServiceImpl}.
 *
 * <p>The {@code getDiscover} JPA {@link Specification} is never executed here (it only runs at
 * the DB layer), so the covered behaviour is: cursor parsing, the post-query enrichment mapping
 * (mutual friends, liked/friend/request flags, location string, friends-only badge, online flag)
 * and the {@code isOnline} post-filter. {@code likeProfile}/{@code unlikeProfile} cover the
 * not-found (TM_USER_NOT_FOUND), idempotent-repeat, and save/delete side-effect paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiscoverServiceImpl (unit)")
class DiscoverServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private DiscoverLikeRepository discoverLikeRepository;
    @Mock
    private FriendCache friendCache;
    @Mock
    private FriendRequestRepository friendRequestRepository;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private PresenceService presenceService;

    private DiscoverServiceImpl service;

    private User currentUser;

    @BeforeEach
    void setUp() {
        service = new DiscoverServiceImpl(userRepository, discoverLikeRepository, friendCache,
                friendRequestRepository, userSettingRepository, presenceService);
        currentUser = user(1L, "me", "Me");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static User user(Long id, String username, String name) {
        User u = User.builder().username(username).name(name).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private Page<User> page(List<User> content, int pageNo, int size, long total) {
        return new PageImpl<>(content, PageRequest.of(pageNo, size), total);
    }

    /**
     * Stub the two presence sets always read at the top of getDiscover.
     */
    private void stubPresenceSets() {
        lenient().when(presenceService.getOnlineUsernames()).thenReturn(Collections.emptySet());
        lenient().when(presenceService.getAwayUsernames()).thenReturn(Collections.emptySet());
    }

    @SuppressWarnings("unchecked")
    private void stubFindAll(Page<User> result) {
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(result);
    }

    private PaginatedResponse<DiscoverProfileResponse> callDefault() {
        return service.getDiscover(null, null, null, null, null, null, 10,
                null, null, null, null, currentUser);
    }

    @Nested
    @DisplayName("getDiscover")
    class GetDiscover {

        @Test
        @DisplayName("empty page → empty items, friends-only lookup skipped, zero total")
        void emptyPage() {
            stubPresenceSets();
            stubFindAll(page(List.of(), 0, 10, 0));
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of());

            PaginatedResponse<DiscoverProfileResponse> res = callDefault();

            assertThat(res.getItems()).isEmpty();
            assertThat(res.getPagination().getTotal()).isZero();
            assertThat(res.getPagination().isHasNext()).isFalse();
            assertThat(res.getPagination().getCursor()).isNull();
            verify(userSettingRepository, never()).findFriendsOnlyUserIds(anyCollection());
        }

        @Test
        @DisplayName("nominal single profile → maps every field, flags and mutual-friend count")
        void nominalMapping() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setAge(28);
            target.setGender("male");
            target.setBio("hi");
            target.setCity("Paris");
            target.setCountry("France");
            target.setOccupation("Dev");
            target.setEducation("Uni");
            target.setProfileImage("img.jpg");
            target.setVerified(true);
            target.setInterests(Set.of(Interest.MUSIC));

            stubFindAll(page(List.of(target), 0, 10, 1));

            // currentUser friends {target=2, 20}; target friends {20, 30} → 1 mutual (20),
            // and currentUser's set contains the target (id 2) → isFriend true.
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of(2L, 20L));
            when(friendCache.friendIds(target)).thenReturn(Set.of(20L, 30L));

            when(userSettingRepository.findFriendsOnlyUserIds(anyCollection())).thenReturn(Set.of(2L));
            when(presenceService.getStatus(target)).thenReturn(PresenceStatus.ONLINE);
            when(discoverLikeRepository.existsByUserAndLikedUser(currentUser, target)).thenReturn(true);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(currentUser, target))
                    .thenReturn(Optional.empty());

            PaginatedResponse<DiscoverProfileResponse> res = callDefault();

            assertThat(res.getItems()).hasSize(1);
            DiscoverProfileResponse dto = res.getItems().get(0);
            assertThat(dto.getId()).isEqualTo(target.getUuid().toString());
            assertThat(dto.getName()).isEqualTo("Bob");
            assertThat(dto.getAge()).isEqualTo(28);
            assertThat(dto.getUsername()).isEqualTo("bob");
            assertThat(dto.getLocation()).isEqualTo("Paris, France");
            assertThat(dto.getCity()).isEqualTo("Paris");
            assertThat(dto.getCountry()).isEqualTo("France");
            assertThat(dto.getInterests()).containsExactly("MUSIC");
            assertThat(dto.getImages()).containsExactly("img.jpg");
            assertThat(dto.isVerified()).isTrue();
            assertThat(dto.isOnline()).isTrue();
            assertThat(dto.isLiked()).isTrue();
            assertThat(dto.isFriend()).isTrue();
            assertThat(dto.getMutualFriendsCount()).isEqualTo(1);
            assertThat(dto.isRequestSent()).isFalse();
            assertThat(dto.getPendingRequestId()).isNull();
            assertThat(dto.getMessagingFriendsOnly()).isTrue();
        }

        @Test
        @DisplayName("pending friend request → isRequestSent true with pending request id")
        void pendingRequestFlag() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setInterests(Set.of());
            stubFindAll(page(List.of(target), 0, 10, 1));
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of());
            when(friendCache.friendIds(target)).thenReturn(Set.of());
            when(userSettingRepository.findFriendsOnlyUserIds(anyCollection())).thenReturn(Set.of());
            when(presenceService.getStatus(target)).thenReturn(PresenceStatus.OFFLINE);
            when(discoverLikeRepository.existsByUserAndLikedUser(currentUser, target)).thenReturn(false);

            FriendRequest req = FriendRequest.builder().status(FriendRequestStatus.PENDING).build();
            UUID reqUuid = UUID.randomUUID();
            req.setUuid(reqUuid);
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(currentUser, target))
                    .thenReturn(Optional.of(req));

            DiscoverProfileResponse dto = callDefault().getItems().get(0);

            assertThat(dto.isRequestSent()).isTrue();
            assertThat(dto.getPendingRequestId()).isEqualTo(reqUuid.toString());
            assertThat(dto.getMessagingFriendsOnly()).isFalse();
        }

        @Test
        @DisplayName("latest friend request not PENDING → isRequestSent stays false")
        void nonPendingRequestFlag() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setInterests(Set.of());
            stubFindAll(page(List.of(target), 0, 10, 1));
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of());
            when(friendCache.friendIds(target)).thenReturn(Set.of());
            when(userSettingRepository.findFriendsOnlyUserIds(anyCollection())).thenReturn(Set.of());
            when(presenceService.getStatus(target)).thenReturn(PresenceStatus.OFFLINE);
            when(discoverLikeRepository.existsByUserAndLikedUser(currentUser, target)).thenReturn(false);

            FriendRequest req = FriendRequest.builder().status(FriendRequestStatus.ACCEPTED).build();
            req.setUuid(UUID.randomUUID());
            when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(currentUser, target))
                    .thenReturn(Optional.of(req));

            DiscoverProfileResponse dto = callDefault().getItems().get(0);

            assertThat(dto.isRequestSent()).isFalse();
            assertThat(dto.getPendingRequestId()).isNull();
        }

        @Test
        @DisplayName("location string: country only when city is null")
        void locationCountryOnly() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setInterests(Set.of());
            target.setCity(null);
            target.setCountry("France");
            stubFindAll(page(List.of(target), 0, 10, 1));
            wireNeutralEnrichment(target);

            DiscoverProfileResponse dto = callDefault().getItems().get(0);

            assertThat(dto.getLocation()).isEqualTo("France");
        }

        @Test
        @DisplayName("location string: empty when both city and country are null")
        void locationEmpty() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setInterests(Set.of());
            target.setCity(null);
            target.setCountry(null);
            stubFindAll(page(List.of(target), 0, 10, 1));
            wireNeutralEnrichment(target);

            DiscoverProfileResponse dto = callDefault().getItems().get(0);

            assertThat(dto.getLocation()).isEmpty();
            assertThat(dto.getImages()).isEmpty(); // no profile image → no images
        }

        @Test
        @DisplayName("isOnline=true filters out offline profiles")
        void isOnlineFilterDropsOffline() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setInterests(Set.of());
            stubFindAll(page(List.of(target), 0, 10, 1));
            wireNeutralEnrichment(target); // getStatus → OFFLINE

            PaginatedResponse<DiscoverProfileResponse> res = service.getDiscover(
                    null, null, null, null, /*isOnline*/ true, null, 10, null, null, null, null, currentUser);

            assertThat(res.getItems()).isEmpty();
        }

        @Test
        @DisplayName("isOnline=false keeps offline profiles")
        void isOnlineFilterKeepsOffline() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setInterests(Set.of());
            stubFindAll(page(List.of(target), 0, 10, 1));
            wireNeutralEnrichment(target); // OFFLINE

            PaginatedResponse<DiscoverProfileResponse> res = service.getDiscover(
                    null, null, null, null, /*isOnline*/ false, null, 10, null, null, null, null, currentUser);

            assertThat(res.getItems()).hasSize(1);
        }

        @Test
        @DisplayName("numeric cursor selects that page; hasNext advances the cursor")
        void cursorAdvances() {
            stubPresenceSets();
            User target = user(2L, "bob", "Bob");
            target.setInterests(Set.of());
            // page 0 of size 2 with 5 total → hasNext true, hasPrevious false
            stubFindAll(page(List.of(target), 0, 2, 5));
            wireNeutralEnrichment(target);

            PaginatedResponse<DiscoverProfileResponse> res = service.getDiscover(
                    null, null, null, null, null, /*cursor*/ "0", 2, null, null, null, null, currentUser);

            assertThat(res.getPagination().isHasNext()).isTrue();
            assertThat(res.getPagination().isHasPrevious()).isFalse();
            assertThat(res.getPagination().getCursor()).isEqualTo("1");
            assertThat(res.getPagination().getTotal()).isEqualTo(5L);
        }

        @Test
        @DisplayName("non-numeric cursor falls back to page 0 (no exception)")
        void cursorInvalidFallsBack() {
            stubPresenceSets();
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of());
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            stubFindAll(page(List.of(), 0, 10, 0));

            service.getDiscover(null, null, null, null, null, /*cursor*/ "not-a-number", 10,
                    null, null, null, null, currentUser);

            verify(userRepository).findAll(any(Specification.class), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        }

        @Test
        @DisplayName("blank (whitespace) cursor falls back to page 0 without parsing")
        void cursorBlankFallsBack() {
            stubPresenceSets();
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of());
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            stubFindAll(page(List.of(), 0, 10, 0));

            // cursor != null but isBlank() → skip Integer.parseInt, stay on page 0
            service.getDiscover(null, null, null, null, null, /*cursor*/ "   ", 10,
                    null, null, null, null, currentUser);

            verify(userRepository).findAll(any(Specification.class), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        }

        @Test
        @DisplayName("blank (whitespace) interests string is ignored without splitting")
        void interestsBlankIgnored() {
            stubPresenceSets();
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of());
            stubFindAll(page(List.of(), 0, 10, 0));

            // interests != null but isBlank() → interestEnums stays empty, no split/parse
            PaginatedResponse<DiscoverProfileResponse> res = service.getDiscover(
                    null, /*interests*/ "   ", null, null, null, null, 10,
                    null, null, null, null, currentUser);

            assertThat(res.getItems()).isEmpty();
        }

        @Test
        @DisplayName("interests filter parses valid enums and ignores invalid tokens without throwing")
        void interestsParsingIsLenient() {
            stubPresenceSets();
            when(friendCache.friendIds(currentUser)).thenReturn(Set.of());
            stubFindAll(page(List.of(), 0, 10, 0));

            // "music" is valid (case-insensitive), "nonsense" is ignored by the catch branch
            PaginatedResponse<DiscoverProfileResponse> res = service.getDiscover(
                    null, /*interests*/ "music, nonsense", null, null, null, null, 10,
                    null, null, null, null, currentUser);

            assertThat(res.getItems()).isEmpty();
        }
    }

    /**
     * Wire the per-user enrichment lookups to neutral/false values (target is OFFLINE).
     */
    private void wireNeutralEnrichment(User target) {
        when(friendCache.friendIds(currentUser)).thenReturn(Set.of());
        when(friendCache.friendIds(target)).thenReturn(Set.of());
        when(userSettingRepository.findFriendsOnlyUserIds(anyCollection())).thenReturn(Set.of());
        when(presenceService.getStatus(target)).thenReturn(PresenceStatus.OFFLINE);
        when(discoverLikeRepository.existsByUserAndLikedUser(currentUser, target)).thenReturn(false);
        when(friendRequestRepository.findFirstBySenderAndReceiverOrderByIdDesc(currentUser, target))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("likeProfile")
    class LikeProfile {

        @Test
        @DisplayName("new like → persists a DiscoverLike(currentUser → target)")
        void savesLike() {
            User target = user(2L, "bob", "Bob");
            UUID uuid = target.getUuid();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(target));
            when(discoverLikeRepository.existsByUserAndLikedUser(currentUser, target)).thenReturn(false);

            service.likeProfile(uuid.toString(), currentUser);

            ArgumentCaptor<DiscoverLike> captor = ArgumentCaptor.forClass(DiscoverLike.class);
            verify(discoverLikeRepository).save(captor.capture());
            assertThat(captor.getValue().getUser()).isEqualTo(currentUser);
            assertThat(captor.getValue().getLikedUser()).isEqualTo(target);
        }

        @Test
        @DisplayName("already liked → idempotent no-op, nothing saved")
        void idempotentWhenAlreadyLiked() {
            User target = user(2L, "bob", "Bob");
            UUID uuid = target.getUuid();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(target));
            when(discoverLikeRepository.existsByUserAndLikedUser(currentUser, target)).thenReturn(true);

            service.likeProfile(uuid.toString(), currentUser);

            verify(discoverLikeRepository, never()).save(any());
        }

        @Test
        @DisplayName("target not found → NotFoundException TM_USER_NOT_FOUND")
        void notFound() {
            UUID uuid = UUID.randomUUID();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.likeProfile(uuid.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
            verify(discoverLikeRepository, never()).save(any());
        }

        @Test
        @DisplayName("malformed UUID → IllegalArgumentException, no repository lookup")
        void malformedUuid() {
            assertThatThrownBy(() -> service.likeProfile("not-a-uuid", currentUser))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(userRepository, never()).findByUuid(any());
        }
    }

    @Nested
    @DisplayName("unlikeProfile")
    class UnlikeProfile {

        @Test
        @DisplayName("existing like → deletes it")
        void deletesExistingLike() {
            User target = user(2L, "bob", "Bob");
            UUID uuid = target.getUuid();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(target));
            DiscoverLike like = DiscoverLike.builder().user(currentUser).likedUser(target).build();
            when(discoverLikeRepository.findByUserAndLikedUser(currentUser, target)).thenReturn(Optional.of(like));

            service.unlikeProfile(uuid.toString(), currentUser);

            verify(discoverLikeRepository).delete(like);
        }

        @Test
        @DisplayName("no existing like → no-op, nothing deleted")
        void noopWhenAbsent() {
            User target = user(2L, "bob", "Bob");
            UUID uuid = target.getUuid();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(target));
            when(discoverLikeRepository.findByUserAndLikedUser(currentUser, target)).thenReturn(Optional.empty());

            service.unlikeProfile(uuid.toString(), currentUser);

            verify(discoverLikeRepository, never()).delete(any());
        }

        @Test
        @DisplayName("target not found → NotFoundException TM_USER_NOT_FOUND")
        void notFound() {
            UUID uuid = UUID.randomUUID();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unlikeProfile(uuid.toString(), currentUser))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_USER_NOT_FOUND"));
            verify(discoverLikeRepository, never()).delete(any());
        }
    }
}
