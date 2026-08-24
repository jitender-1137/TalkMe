package com.neo.chat.service.impl;

import com.neo.chat.domain.HelpAnswer;
import com.neo.chat.domain.HelpRequest;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.HelpAnswerResponse;
import com.neo.chat.dto.response.HelpFeedResponse;
import com.neo.chat.dto.response.HelpRequestResponse;
import com.neo.chat.enums.HelpCategory;
import com.neo.chat.enums.HelpStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.ServiceException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.HelpAnswerRepository;
import com.neo.chat.repository.HelpRequestRepository;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link CommunityHelpServiceImpl} (feature #9, COMMUNITY_HELP).
 *
 * <p>Covers post validation/city-defaulting/moderation/cap/TTL, feed mapping + paging, answer
 * moderation/block/closed handling + counter increment + WS broadcast, resolve asker-only +
 * idempotency, and the reaper. The feed's {@link Specification} lambda is integration-only; here
 * {@code findAll(spec, pageable)} is stubbed with canned rows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityHelpServiceImpl (unit)")
class CommunityHelpServiceImplTest {

    @Mock
    private HelpRequestRepository helpRequestRepository;
    @Mock
    private HelpAnswerRepository helpAnswerRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private PresenceService presenceService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private CommunityHelpServiceImpl service;

    private User asker;
    private User answerer;

    @BeforeEach
    void setUp() {
        service = new CommunityHelpServiceImpl(helpRequestRepository, helpAnswerRepository,
                blockUserRepository, moderationService, presenceService, messagingTemplate);
        asker = user(1L, "alice", "Pune");
        answerer = user(2L, "bob", "Pune");
        // Presence is consulted while mapping responses; keep it lenient so tests that don't map
        // (pure error paths) don't trip strict-stub checks.
        lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
        lenient().when(presenceService.getAwayUsernames()).thenReturn(Set.of());
    }

    private User user(long id, String username, String city) {
        User u = new User();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        u.setUsername(username);
        u.setName(username + " Name");
        u.setProfileImage("https://cdn/" + username + ".jpg");
        u.setCity(city);
        return u;
    }

    private HelpRequest request(User a, HelpStatus status, Instant expiresAt) {
        HelpRequest r = HelpRequest.builder()
                .asker(a)
                .city(a.getCity())
                .category(HelpCategory.TRANSPORT)
                .body("Is the metro running?")
                .status(status)
                .expiresAt(expiresAt)
                .answerCount(0)
                .build();
        r.setUuid(UUID.randomUUID());
        r.setCreatedAt(Instant.now());
        return r;
    }

    // ── postHelp ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("postHelp")
    class PostHelp {

        @Test
        @DisplayName("clean post: defaults city to asker's, sets OPEN + TTL, persists")
        void shouldPostWithDefaultCity() {
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(helpRequestRepository.countByAskerAndStatusAndExpiresAtAfter(eq(asker), eq(HelpStatus.OPEN), any()))
                    .thenReturn(0L);
            when(helpRequestRepository.save(any(HelpRequest.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            HelpRequestResponse res =
                    service.postHelp(asker, null, HelpCategory.TRANSPORT, "  Is the metro running?  ");

            ArgumentCaptor<HelpRequest> captor = ArgumentCaptor.forClass(HelpRequest.class);
            verify(helpRequestRepository).save(captor.capture());
            HelpRequest saved = captor.getValue();
            assertThat(saved.getCity()).isEqualTo("Pune");
            assertThat(saved.getBody()).isEqualTo("Is the metro running?"); // trimmed
            assertThat(saved.getStatus()).isEqualTo(HelpStatus.OPEN);
            assertThat(saved.getExpiresAt()).isAfter(Instant.now());
            assertThat(res.getCity()).isEqualTo("Pune");
            assertThat(res.getStatus()).isEqualTo("OPEN");
            assertThat(res.getAsker().getUsername()).isEqualTo("alice");
            assertThat(res.getAsker().getPresence()).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("explicit city argument overrides the asker's own city")
        void shouldHonourExplicitCity() {
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(helpRequestRepository.countByAskerAndStatusAndExpiresAtAfter(any(), any(), any()))
                    .thenReturn(0L);
            when(helpRequestRepository.save(any(HelpRequest.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.postHelp(asker, " Mumbai ", null, "Any bus to airport?");

            ArgumentCaptor<HelpRequest> captor = ArgumentCaptor.forClass(HelpRequest.class);
            verify(helpRequestRepository).save(captor.capture());
            assertThat(captor.getValue().getCity()).isEqualTo("Mumbai");
            assertThat(captor.getValue().getCategory()).isEqualTo(HelpCategory.OTHER); // null → OTHER
        }

        @Test
        @DisplayName("blank body is rejected (TM_983), nothing saved")
        void shouldRejectBlankBody() {
            assertThatThrownBy(() -> service.postHelp(asker, "Pune", HelpCategory.OTHER, "   "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_983"));
            verify(helpRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("over-long body is rejected (TM_984)")
        void shouldRejectLongBody() {
            String tooLong = "x".repeat(501);
            assertThatThrownBy(() -> service.postHelp(asker, "Pune", HelpCategory.OTHER, tooLong))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_984"));
            verify(helpRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("no city anywhere is rejected (TM_985)")
        void shouldRejectMissingCity() {
            User cityless = user(3L, "carol", null);
            assertThatThrownBy(() -> service.postHelp(cityless, null, HelpCategory.OTHER, "Help?"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_985"));
            verify(helpRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit content is blocked (TM_987), nothing saved")
        void shouldBlockExplicit() {
            when(moderationService.moderateText(anyString()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.PROFANITY, 0.9, List.of("x")));

            assertThatThrownBy(() -> service.postHelp(asker, "Pune", HelpCategory.OTHER, "bad words"))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_987"));
            verify(helpRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("over the open-request cap is rejected (TM_986)")
        void shouldEnforceOpenCap() {
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(helpRequestRepository.countByAskerAndStatusAndExpiresAtAfter(any(), any(), any()))
                    .thenReturn((long) CommunityHelpServiceImpl.MAX_OPEN_PER_USER);

            assertThatThrownBy(() -> service.postHelp(asker, "Pune", HelpCategory.OTHER, "Help?"))
                    .isInstanceOfSatisfying(TooManyRequestsException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_986"));
            verify(helpRequestRepository, never()).save(any());
        }
    }

    // ── feed ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("feed")
    class Feed {

        @Test
        @DisplayName("maps rows and reports hasMore + nextCursor when another page exists")
        void shouldMapAndPage() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            Pageable pageable = PageRequest.of(0, 20);
            Page<HelpRequest> page = new PageImpl<>(List.of(r), pageable, 40); // total 40 → hasNext
            when(helpRequestRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);
            when(blockUserRepository.findByUser(any())).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(any())).thenReturn(List.of());

            HelpFeedResponse res = service.feed(answerer, "Pune", null, null, 20);

            assertThat(res.getItems()).hasSize(1);
            assertThat(res.getItems().get(0).getBody()).isEqualTo("Is the metro running?");
            assertThat(res.isHasMore()).isTrue();
            assertThat(res.getNextCursor()).isEqualTo("1");
        }

        @Test
        @DisplayName("last page: hasMore false, null cursor")
        void shouldReportLastPage() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<HelpRequest> page = new PageImpl<>(List.of(), pageable, 0);
            when(helpRequestRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);
            when(blockUserRepository.findByUser(any())).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(any())).thenReturn(List.of());

            HelpFeedResponse res = service.feed(answerer, "Pune", HelpCategory.TRANSPORT, null, 0);

            assertThat(res.getItems()).isEmpty();
            assertThat(res.isHasMore()).isFalse();
            assertThat(res.getNextCursor()).isNull();
        }

        @Test
        @DisplayName("no city anywhere is rejected (TM_985)")
        void shouldRejectMissingCity() {
            User cityless = user(3L, "carol", null);
            assertThatThrownBy(() -> service.feed(cityless, null, null, null, 20))
                    .isInstanceOfSatisfying(ServiceException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_985"));
        }
    }

    // ── answer ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("answer")
    class Answer {

        @Test
        @DisplayName("clean answer: increments count, persists, broadcasts to asker + city topic")
        void shouldAnswer() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(helpAnswerRepository.save(any(HelpAnswer.class))).thenAnswer(inv -> {
                HelpAnswer a = inv.getArgument(0);
                a.setUuid(UUID.randomUUID());
                a.setCreatedAt(Instant.now());
                return a;
            });
            when(helpRequestRepository.save(any(HelpRequest.class))).thenAnswer(inv -> inv.getArgument(0));

            HelpAnswerResponse res =
                    service.answer(answerer, r.getUuid().toString(), "  Take the 42 bus  ");

            assertThat(res.getBody()).isEqualTo("Take the 42 bus");
            assertThat(res.getAnswerer().getUsername()).isEqualTo("bob");
            assertThat(res.getRequestUuid()).isEqualTo(r.getUuid().toString());
            assertThat(r.getAnswerCount()).isEqualTo(1);

            verify(messagingTemplate).convertAndSendToUser(eq("alice"),
                    eq("/queue/community-help"), any());
            verify(messagingTemplate).convertAndSend(eq("/topic/community-help/Pune"), (Object) any());
        }

        @Test
        @DisplayName("malformed uuid is rejected (TM_983)")
        void shouldRejectBadUuid() {
            assertThatThrownBy(() -> service.answer(answerer, "not-a-uuid", "hi"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_983"));
            verify(helpAnswerRepository, never()).save(any());
        }

        @Test
        @DisplayName("unknown request is not found (TM_988)")
        void shouldRejectMissingRequest() {
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.answer(answerer, UUID.randomUUID().toString(), "hi"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_988"));
        }

        @Test
        @DisplayName("closed/expired request is no longer answerable (TM_988)")
        void shouldRejectClosedRequest() {
            HelpRequest r = request(asker, HelpStatus.RESOLVED, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.answer(answerer, r.getUuid().toString(), "hi"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_988"));
            verify(helpAnswerRepository, never()).save(any());
        }

        @Test
        @DisplayName("blocked participant cannot answer (TM_989)")
        void shouldRejectBlocked() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));
            when(blockUserRepository.existsByUserAndBlocked(answerer, asker)).thenReturn(true);

            assertThatThrownBy(() -> service.answer(answerer, r.getUuid().toString(), "hi"))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_989"));
            verify(helpAnswerRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit answer is blocked (TM_987)")
        void shouldBlockExplicitAnswer() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(moderationService.moderateText(anyString()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.ABUSE, 0.9, List.of("x")));

            assertThatThrownBy(() -> service.answer(answerer, r.getUuid().toString(), "bad"))
                    .isInstanceOfSatisfying(ContentModerationException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_987"));
            verify(helpAnswerRepository, never()).save(any());
        }
    }

    // ── getAnswers ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAnswers")
    class GetAnswers {

        private HelpAnswer answer(HelpRequest r, User by, String body) {
            HelpAnswer a = HelpAnswer.builder()
                    .helpRequest(r)
                    .answerer(by)
                    .body(body)
                    .build();
            a.setUuid(UUID.randomUUID());
            a.setCreatedAt(Instant.now());
            return a;
        }

        @Test
        @DisplayName("returns the request's answers mapped oldest-first with answerer info")
        void shouldReturnMappedAnswers() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));
            when(helpAnswerRepository.findByHelpRequestAndIsDeletedFalseOrderByCreatedAtAsc(r))
                    .thenReturn(List.of(answer(r, answerer, "Take the 42 bus"),
                            answer(r, asker, "or the metro")));

            List<HelpAnswerResponse> res = service.getAnswers(answerer, r.getUuid().toString());

            assertThat(res).hasSize(2);
            assertThat(res.get(0).getBody()).isEqualTo("Take the 42 bus");
            assertThat(res.get(0).getRequestUuid()).isEqualTo(r.getUuid().toString());
            assertThat(res.get(0).getAnswerer().getUsername()).isEqualTo("bob");
            assertThat(res.get(1).getAnswerer().getUsername()).isEqualTo("alice");
        }

        @Test
        @DisplayName("empty when the request has no answers")
        void shouldReturnEmptyList() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));
            when(helpAnswerRepository.findByHelpRequestAndIsDeletedFalseOrderByCreatedAtAsc(r))
                    .thenReturn(List.of());

            assertThat(service.getAnswers(answerer, r.getUuid().toString())).isEmpty();
        }

        @Test
        @DisplayName("unknown request is not found (TM_988)")
        void shouldRejectMissingRequest() {
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAnswers(answerer, UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_988"));
            verify(helpAnswerRepository, never())
                    .findByHelpRequestAndIsDeletedFalseOrderByCreatedAtAsc(any());
        }
    }

    // ── markResolved ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markResolved")
    class MarkResolved {

        @Test
        @DisplayName("asker resolves an open request → RESOLVED, persisted")
        void shouldResolve() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));
            when(helpRequestRepository.save(any(HelpRequest.class))).thenAnswer(inv -> inv.getArgument(0));

            HelpRequestResponse res = service.markResolved(asker, r.getUuid().toString());

            assertThat(res.getStatus()).isEqualTo("RESOLVED");
            assertThat(r.getStatus()).isEqualTo(HelpStatus.RESOLVED);
            verify(helpRequestRepository).save(r);
        }

        @Test
        @DisplayName("already-resolved is idempotent: no second save")
        void shouldBeIdempotent() {
            HelpRequest r = request(asker, HelpStatus.RESOLVED, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));

            HelpRequestResponse res = service.markResolved(asker, r.getUuid().toString());

            assertThat(res.getStatus()).isEqualTo("RESOLVED");
            verify(helpRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-asker cannot resolve (TM_989)")
        void shouldRejectNonAsker() {
            HelpRequest r = request(asker, HelpStatus.OPEN, Instant.now().plusSeconds(3600));
            when(helpRequestRepository.findByUuid(any())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.markResolved(answerer, r.getUuid().toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_989"));
            verify(helpRequestRepository, never()).save(any());
        }
    }

    // ── reapExpired ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reapExpired")
    class ReapExpired {

        @Test
        @DisplayName("no expired requests → 0, no save")
        void shouldNoOp() {
            when(helpRequestRepository.findByStatusAndExpiresAtBefore(eq(HelpStatus.OPEN), any()))
                    .thenReturn(List.of());

            assertThat(service.reapExpired(Instant.now())).isZero();
            verify(helpRequestRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("flips each expired OPEN request to RESOLVED and returns the count")
        void shouldReap() {
            HelpRequest r1 = request(asker, HelpStatus.OPEN, Instant.now().minusSeconds(10));
            HelpRequest r2 = request(asker, HelpStatus.OPEN, Instant.now().minusSeconds(20));
            when(helpRequestRepository.findByStatusAndExpiresAtBefore(eq(HelpStatus.OPEN), any()))
                    .thenReturn(List.of(r1, r2));

            int count = service.reapExpired(Instant.now());

            assertThat(count).isEqualTo(2);
            assertThat(r1.getStatus()).isEqualTo(HelpStatus.RESOLVED);
            assertThat(r2.getStatus()).isEqualTo(HelpStatus.RESOLVED);
            verify(helpRequestRepository).saveAll(List.of(r1, r2));
        }
    }
}
