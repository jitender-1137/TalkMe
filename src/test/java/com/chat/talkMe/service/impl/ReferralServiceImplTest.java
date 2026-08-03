package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ReferralSummaryResponse;
import com.chat.talkMe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ReferralServiceImpl} — read-only referral attribution
 * summary. Exercises: exact count vs capped list (MAX_LISTED=50), DTO field mapping,
 * null-safe uuid/createdAt, empty result, and the PageRequest(0, 50) contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralServiceImpl (unit)")
class ReferralServiceImplTest {

    @Mock private UserRepository userRepository;

    private ReferralServiceImpl service;

    private User currentUser;

    @BeforeEach
    void setUp() {
        service = new ReferralServiceImpl(userRepository);
        currentUser = User.builder().username("inviter").name("Invy").build();
        currentUser.setId(1L);
    }

    private User referred(String uuid, String name, String username, String avatar, Instant createdAt) {
        User u = User.builder().name(name).username(username).profileImage(avatar).build();
        if (uuid != null) u.setUuid(UUID.fromString(uuid));
        u.setCreatedAt(createdAt); // BaseEntity setter; may be null to exercise the null path
        return u;
    }

    @Nested
    @DisplayName("getMySummary")
    class GetMySummary {

        @Test
        @DisplayName("nominal → username, exact count, and fully-mapped referral DTOs")
        void nominal() {
            Instant joined = Instant.parse("2026-01-15T10:00:00Z");
            User a = referred("11111111-1111-1111-1111-111111111111", "Alice", "alice", "a.png", joined);
            when(userRepository.countByReferredByAndIsDeletedFalse(currentUser)).thenReturn(3L);
            when(userRepository.findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(eq(currentUser), any(Pageable.class)))
                    .thenReturn(List.of(a));

            ReferralSummaryResponse res = service.getMySummary(currentUser);

            assertThat(res.getUsername()).isEqualTo("inviter");
            assertThat(res.getReferralCount()).isEqualTo(3L);
            assertThat(res.getReferrals()).hasSize(1);
            assertThat(res.getReferrals().get(0).getId()).isEqualTo("11111111-1111-1111-1111-111111111111");
            assertThat(res.getReferrals().get(0).getName()).isEqualTo("Alice");
            assertThat(res.getReferrals().get(0).getUsername()).isEqualTo("alice");
            assertThat(res.getReferrals().get(0).getAvatar()).isEqualTo("a.png");
            assertThat(res.getReferrals().get(0).getJoinedAt()).isEqualTo(joined.toString());
        }

        @Test
        @DisplayName("no joiners → count 0 and an empty (non-null) referral list")
        void empty() {
            when(userRepository.countByReferredByAndIsDeletedFalse(currentUser)).thenReturn(0L);
            when(userRepository.findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(eq(currentUser), any(Pageable.class)))
                    .thenReturn(List.of());

            ReferralSummaryResponse res = service.getMySummary(currentUser);

            assertThat(res.getReferralCount()).isZero();
            assertThat(res.getReferrals()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("null uuid / null createdAt map to null id / null joinedAt without NPE")
        void nullFieldsAreSafe() {
            User u = referred(null, "NoUuid", "nouuid", null, null);
            when(userRepository.countByReferredByAndIsDeletedFalse(currentUser)).thenReturn(1L);
            when(userRepository.findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(eq(currentUser), any(Pageable.class)))
                    .thenReturn(List.of(u));

            ReferralSummaryResponse res = service.getMySummary(currentUser);

            assertThat(res.getReferrals().get(0).getId()).isNull();
            assertThat(res.getReferrals().get(0).getJoinedAt()).isNull();
            assertThat(res.getReferrals().get(0).getName()).isEqualTo("NoUuid");
            assertThat(res.getReferrals().get(0).getAvatar()).isNull();
        }

        @Test
        @DisplayName("count is exact even when the listed rows are capped below it")
        void countIndependentOfListSize() {
            User a = referred("22222222-2222-2222-2222-222222222222", "A", "a", null, Instant.now());
            User b = referred("33333333-3333-3333-3333-333333333333", "B", "b", null, Instant.now());
            when(userRepository.countByReferredByAndIsDeletedFalse(currentUser)).thenReturn(100L);
            when(userRepository.findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(eq(currentUser), any(Pageable.class)))
                    .thenReturn(List.of(a, b));

            ReferralSummaryResponse res = service.getMySummary(currentUser);

            assertThat(res.getReferralCount()).isEqualTo(100L);
            assertThat(res.getReferrals()).hasSize(2);
        }

        @Test
        @DisplayName("lists the most-recent page: PageRequest(0, 50)")
        void requestsFirstPageCappedAt50() {
            when(userRepository.countByReferredByAndIsDeletedFalse(currentUser)).thenReturn(0L);
            when(userRepository.findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(eq(currentUser), any(Pageable.class)))
                    .thenReturn(List.of());

            service.getMySummary(currentUser);

            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(userRepository).findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(eq(currentUser), page.capture());
            assertThat(page.getValue().getPageNumber()).isZero();
            assertThat(page.getValue().getPageSize()).isEqualTo(50);
        }
    }
}
