package com.neo.chat.schedule;

import com.neo.chat.domain.Friend;
import com.neo.chat.domain.User;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.service.RelationshipJourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link RelationshipJourneyJob} — nightly milestone derivation.
 *
 * <p>Invariants under test: (1) an empty friendship page is a clean no-op; (2) each valid unique
 * pair is materialized once; (3) the two directional rows of one friendship dedup to a single
 * materialize call; (4) deleted / null-side / null-id / self rows are skipped; (5) PARTIAL-FAILURE
 * ISOLATION — a throw on one pair never aborts the rest of the batch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RelationshipJourneyJob (unit)")
class RelationshipJourneyJobTest {

    @Mock
    private FriendRepository friendRepository;
    @Mock
    private RelationshipJourneyService relationshipJourneyService;

    private RelationshipJourneyJob job;

    @BeforeEach
    void setUp() {
        job = new RelationshipJourneyJob(friendRepository, relationshipJourneyService);
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private static Friend friend(User a, User b, boolean deleted) {
        Friend f = new Friend();
        f.setUser(a);
        f.setFriend(b);
        f.setDeleted(deleted);
        return f;
    }

    private void pageOf(Friend... friendships) {
        Page<Friend> page = new PageImpl<>(List.of(friendships));
        when(friendRepository.findAll(any(Pageable.class))).thenReturn(page);
    }

    @Nested
    @DisplayName("deriveMilestones")
    class DeriveMilestones {

        @Test
        @DisplayName("no-ops (never materializes) when there are no friendships")
        void shouldNoOpWhenEmpty() {
            when(friendRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            job.deriveMilestones();

            verifyNoInteractions(relationshipJourneyService);
        }

        @Test
        @DisplayName("materializes each valid unique pair exactly once")
        void shouldMaterializeEachValidPair() {
            User a = user(1L), b = user(2L), c = user(3L);
            pageOf(friend(a, b, false), friend(a, c, false));

            job.deriveMilestones();

            verify(relationshipJourneyService).materializeFor(a, b);
            verify(relationshipJourneyService).materializeFor(a, c);
        }

        @Test
        @DisplayName("dedups the two directional rows of one friendship into a single call")
        void shouldDedupDirectionalRows() {
            User a = user(1L), b = user(2L);
            // (a,b) and its mirror (b,a) normalize to the same 1:2 key.
            pageOf(friend(a, b, false), friend(b, a, false));

            job.deriveMilestones();

            verify(relationshipJourneyService, times(1)).materializeFor(any(User.class), any(User.class));
        }

        @Test
        @DisplayName("skips soft-deleted friendship rows")
        void shouldSkipDeletedRows() {
            User a = user(1L), b = user(2L);
            pageOf(friend(a, b, true));

            job.deriveMilestones();

            verifyNoInteractions(relationshipJourneyService);
        }

        @Test
        @DisplayName("skips rows with a null user or friend side")
        void shouldSkipNullSideRows() {
            User a = user(1L);
            pageOf(friend(a, null, false), friend(null, a, false));

            job.deriveMilestones();

            verifyNoInteractions(relationshipJourneyService);
        }

        @Test
        @DisplayName("skips rows whose ids are null or point at the same user")
        void shouldSkipNullIdAndSelfPairs() {
            User noId = user(null);
            User a = user(5L);
            User selfA = user(7L), selfB = user(7L);
            pageOf(friend(noId, a, false), friend(selfA, selfB, false));

            job.deriveMilestones();

            verifyNoInteractions(relationshipJourneyService);
        }

        @Test
        @DisplayName("isolates a per-pair failure and still materializes the rest")
        void shouldIsolatePerPairFailure() {
            User a = user(1L), b = user(2L), c = user(3L), d = user(4L);
            pageOf(friend(a, b, false), friend(c, d, false));
            doThrow(new RuntimeException("boom")).when(relationshipJourneyService).materializeFor(a, b);

            job.deriveMilestones();

            verify(relationshipJourneyService).materializeFor(a, b);
            verify(relationshipJourneyService).materializeFor(c, d);
        }
    }
}
