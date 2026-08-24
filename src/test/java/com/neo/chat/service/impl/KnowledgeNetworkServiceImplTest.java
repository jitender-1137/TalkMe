package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserExperience;
import com.neo.chat.dto.request.UpdateExperiencesRequest;
import com.neo.chat.dto.response.ExperienceResponse;
import com.neo.chat.dto.response.KnowledgePersonResponse;
import com.neo.chat.dto.response.KnowledgeSearchPageResponse;
import com.neo.chat.enums.ExperienceCategory;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserExperienceRepository;
import com.neo.chat.repository.UserRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link KnowledgeNetworkServiceImpl} (Human Knowledge Network).
 *
 * <p><b>Scope boundary:</b> the search JPA {@link Specification} lambda is never executed by a
 * mocked repository, so it is out of scope here (integration-only). These tests stub
 * {@code findAll(spec, pageable)} with canned rows and assert the service's ranking (ONLINE →
 * AWAY → offline), distinct-person dedupe, cursor/page mapping, and card projection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeNetworkServiceImpl (unit)")
class KnowledgeNetworkServiceImplTest {

    @Mock
    private UserExperienceRepository experienceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private PresenceService presenceService;

    private KnowledgeNetworkServiceImpl service;

    private User viewer;

    @BeforeEach
    void setUp() {
        service = new KnowledgeNetworkServiceImpl(
                experienceRepository, userRepository, blockUserRepository, presenceService);
        viewer = user(1L, "viewer");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static User user(Long id, String username) {
        User u = User.builder()
                .username(username)
                .name("Name-" + username)
                .profileImage("https://cdn/" + username + ".png")
                .country("IN")
                .city("Delhi")
                .build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private static UserExperience exp(User owner, String tag, ExperienceCategory cat) {
        UserExperience e = UserExperience.builder()
                .user(owner)
                .tag(tag)
                .category(cat)
                .note("note-" + tag)
                .openToQuestions(true)
                .build();
        e.setUuid(UUID.randomUUID());
        return e;
    }

    // ── getMine ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMine")
    class GetMine {

        @Test
        void returnsEmptyWhenNoExperiences() {
            when(experienceRepository.findByUser(viewer)).thenReturn(List.of());
            assertThat(service.getMine(viewer)).isEmpty();
        }

        @Test
        void mapsOwnExperiencesIncludingUuid() {
            UserExperience e = exp(viewer, "Moved to Canada", ExperienceCategory.RELOCATION);
            when(experienceRepository.findByUser(viewer)).thenReturn(List.of(e));

            List<ExperienceResponse> out = service.getMine(viewer);

            assertThat(out).hasSize(1);
            ExperienceResponse r = out.get(0);
            assertThat(r.getTag()).isEqualTo("Moved to Canada");
            assertThat(r.getCategory()).isEqualTo("RELOCATION");
            assertThat(r.getNote()).isEqualTo("note-Moved to Canada");
            assertThat(r.isOpenToQuestions()).isTrue();
            assertThat(r.getUuid()).isEqualTo(e.getUuid().toString());
        }
    }

    // ── updateExperiences ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateExperiences")
    class UpdateExperiences {

        private UpdateExperiencesRequest req(UpdateExperiencesRequest.ExperienceItem... items) {
            return UpdateExperiencesRequest.builder()
                    .experiences(new ArrayList<>(List.of(items)))
                    .build();
        }

        private UpdateExperiencesRequest.ExperienceItem item(
                String tag, ExperienceCategory cat, String note, Boolean open) {
            return UpdateExperiencesRequest.ExperienceItem.builder()
                    .tag(tag).category(cat).note(note).openToQuestions(open).build();
        }

        @Test
        void replacesSetTrimsDedupesAndDefaults() {
            when(experienceRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            UpdateExperiencesRequest request = req(
                    item("  Moved to Canada  ", ExperienceCategory.RELOCATION, "  did it 2021 ", null),
                    item("moved to canada", ExperienceCategory.TRAVEL, null, false), // dup (case) -> skipped
                    item("Java developer", null, "  ", true));                        // null cat -> OTHER, blank note -> null

            List<ExperienceResponse> out = service.updateExperiences(viewer, request);

            // Old set dropped, then new set inserted.
            verify(experienceRepository).deleteByUser(viewer);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UserExperience>> captor = ArgumentCaptor.forClass(List.class);
            verify(experienceRepository).saveAll(captor.capture());
            List<UserExperience> saved = captor.getValue();

            assertThat(saved).hasSize(2);
            UserExperience first = saved.get(0);
            assertThat(first.getTag()).isEqualTo("Moved to Canada");        // trimmed
            assertThat(first.getCategory()).isEqualTo(ExperienceCategory.RELOCATION);
            assertThat(first.getNote()).isEqualTo("did it 2021");           // trimmed
            assertThat(first.isOpenToQuestions()).isTrue();                 // null -> default true
            assertThat(first.getUser()).isSameAs(viewer);

            UserExperience second = saved.get(1);
            assertThat(second.getTag()).isEqualTo("Java developer");
            assertThat(second.getCategory()).isEqualTo(ExperienceCategory.OTHER); // null -> OTHER
            assertThat(second.getNote()).isNull();                                // blank -> null

            assertThat(out).hasSize(2);
        }

        @Test
        void emptyRequestClearsAll() {
            when(experienceRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            List<ExperienceResponse> out =
                    service.updateExperiences(viewer, UpdateExperiencesRequest.builder().build());

            verify(experienceRepository).deleteByUser(viewer);
            assertThat(out).isEmpty();
        }

        @Test
        void rejectsBlankTagWithTm942() {
            UpdateExperiencesRequest request = req(item("   ", ExperienceCategory.LIFE, null, true));

            assertThatThrownBy(() -> service.updateExperiences(viewer, request))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_942"));

            verify(experienceRepository, never()).deleteByUser(any());
            verify(experienceRepository, never()).saveAll(anyList());
        }

        @Test
        void rejectsTooLongTagWithTm942() {
            String longTag = "x".repeat(81);
            UpdateExperiencesRequest request = req(item(longTag, ExperienceCategory.LIFE, null, true));

            assertThatThrownBy(() -> service.updateExperiences(viewer, request))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_942"));
        }

        @Test
        void rejectsTooLongNoteWithTm942() {
            UpdateExperiencesRequest request =
                    req(item("Valid tag", ExperienceCategory.LIFE, "n".repeat(281), true));

            assertThatThrownBy(() -> service.updateExperiences(viewer, request))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_942"));
        }

        @Test
        void rejectsOverCapWithTm943() {
            List<UpdateExperiencesRequest.ExperienceItem> many = new ArrayList<>();
            for (int i = 0; i < KnowledgeNetworkServiceImpl.MAX_EXPERIENCES + 1; i++) {
                many.add(item("tag-" + i, ExperienceCategory.OTHER, null, true));
            }
            UpdateExperiencesRequest request =
                    UpdateExperiencesRequest.builder().experiences(many).build();

            assertThatThrownBy(() -> service.updateExperiences(viewer, request))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_943"));

            verify(experienceRepository, never()).deleteByUser(any());
        }
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("search")
    class Search {

        @SuppressWarnings("unchecked")
        private void stubFindAll(Page<UserExperience> page) {
            when(experienceRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);
        }

        @Test
        void ranksOnlineThenAwayThenOfflineAndMapsMatchedExperience() {
            User online = user(2L, "onlineUser");
            User away = user(3L, "awayUser");
            User offline = user(4L, "offlineUser");

            // DB order deliberately NOT ranked (offline first) to prove the service re-ranks.
            List<UserExperience> rows = List.of(
                    exp(offline, "Visited Japan", ExperienceCategory.TRAVEL),
                    exp(away, "Studied abroad", ExperienceCategory.EDUCATION),
                    exp(online, "Moved to Canada", ExperienceCategory.RELOCATION));
            stubFindAll(new PageImpl<>(rows, PageRequest.of(0, 20), 3));

            when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("onlineUser"));
            when(presenceService.getAwayUsernames()).thenReturn(Set.of("awayUser"));

            KnowledgeSearchPageResponse page = service.search(viewer, "moved", null, null, 20);

            assertThat(page.getItems()).extracting(KnowledgePersonResponse::getUsername)
                    .containsExactly("onlineUser", "awayUser", "offlineUser");
            assertThat(page.getItems()).extracting(KnowledgePersonResponse::getPresence)
                    .containsExactly("ONLINE", "AWAY", "OFFLINE");

            KnowledgePersonResponse first = page.getItems().get(0);
            assertThat(first.getUserUuid()).isEqualTo(online.getUuid().toString());
            assertThat(first.getName()).isEqualTo("Name-onlineUser");
            assertThat(first.getMatchedExperience().getTag()).isEqualTo("Moved to Canada");
            assertThat(first.getMatchedExperience().getCategory()).isEqualTo("RELOCATION");
            // matched-experience projection omits the row uuid
            assertThat(first.getMatchedExperience().getUuid()).isNull();

            assertThat(page.isHasMore()).isFalse();
            assertThat(page.getNextCursor()).isNull();
        }

        @Test
        void collapsesDuplicatePeopleKeepingFirstMatchedTag() {
            User p = user(2L, "polyglot");
            List<UserExperience> rows = List.of(
                    exp(p, "Java developer", ExperienceCategory.TECHNOLOGY),
                    exp(p, "Python developer", ExperienceCategory.TECHNOLOGY));
            stubFindAll(new PageImpl<>(rows, PageRequest.of(0, 20), 2));
            when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            when(presenceService.getAwayUsernames()).thenReturn(Set.of());

            KnowledgeSearchPageResponse page = service.search(viewer, "developer", null, null, 20);

            assertThat(page.getItems()).hasSize(1);
            assertThat(page.getItems().get(0).getMatchedExperience().getTag())
                    .isEqualTo("Java developer");
        }

        @Test
        void setsNextCursorWhenMorePagesRemain() {
            User p = user(2L, "someone");
            List<UserExperience> rows = List.of(exp(p, "Visited Japan", ExperienceCategory.TRAVEL));
            // total 50, page size 20, page 0 -> hasNext true
            stubFindAll(new PageImpl<>(rows, PageRequest.of(0, 20), 50));
            when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            when(presenceService.getAwayUsernames()).thenReturn(Set.of());

            KnowledgeSearchPageResponse page = service.search(viewer, null, ExperienceCategory.TRAVEL, null, 20);

            assertThat(page.isHasMore()).isTrue();
            assertThat(page.getNextCursor()).isEqualTo("1");
        }

        @Test
        void parsesCursorIntoRequestedPageAndClampsLimit() {
            stubFindAll(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));
            when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            when(presenceService.getAwayUsernames()).thenReturn(Set.of());

            service.search(viewer, "x", null, "2", 999); // limit clamped to MAX_LIMIT

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(experienceRepository).findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize())
                    .isEqualTo(KnowledgeNetworkServiceImpl.MAX_LIMIT);
        }

        @Test
        void fetchesBothBlockDirectionsForExclusion() {
            stubFindAll(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
            when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            when(presenceService.getAwayUsernames()).thenReturn(Set.of());

            service.search(viewer, "x", null, null, 20);

            verify(blockUserRepository).findByUser(viewer);
            verify(blockUserRepository).findByBlocked(viewer);
        }
    }

    // ── askPerson ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("askPerson")
    class AskPerson {

        @Test
        void returnsTargetCardWithPresenceWhenAllowed() {
            User target = user(2L, "expert");
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            when(blockUserRepository.existsByUserAndBlocked(viewer, target)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(target, viewer)).thenReturn(false);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("expert"));

            KnowledgePersonResponse card = service.askPerson(viewer, target.getUuid().toString());

            assertThat(card.getUserUuid()).isEqualTo(target.getUuid().toString());
            assertThat(card.getUsername()).isEqualTo("expert");
            assertThat(card.getPresence()).isEqualTo("ONLINE");
            assertThat(card.getMatchedExperience()).isNull();
        }

        @Test
        void rejectsInvalidUuidWithTm944() {
            assertThatThrownBy(() -> service.askPerson(viewer, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_944"));
            verify(userRepository, never()).findByUuid(any());
        }

        @Test
        void rejectsUnknownTargetWithTm946() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.askPerson(viewer, id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_946"));
        }

        @Test
        void rejectsWhenBlockedEitherDirectionWithTm945() {
            User target = user(2L, "expert");
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            when(blockUserRepository.existsByUserAndBlocked(viewer, target)).thenReturn(true);

            assertThatThrownBy(() -> service.askPerson(viewer, target.getUuid().toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_945"));
        }
    }
}
