package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSkill;
import com.neo.chat.dto.request.UpdateSkillsRequest;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.SkillMatchResponse;
import com.neo.chat.dto.response.SkillProfileResponse;
import com.neo.chat.enums.SkillDirection;
import com.neo.chat.enums.SkillLevel;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSkillRepository;
import com.neo.chat.service.CompatibilityService;
import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link SkillExchangeServiceImpl} (feature SKILL_EXCHANGE).
 * Covers getMine mapping, updateSkills normalisation/cap/replace, findMatches discovery,
 * reciprocity, block/deleted skipping and ranking, and startStudySession validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillExchangeServiceImpl (unit)")
class SkillExchangeServiceImplTest {

    @Mock
    private UserSkillRepository userSkillRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CompatibilityService compatibilityService;
    @Mock
    private PresenceService presenceService;
    @Mock
    private BlockUserRepository blockUserRepository;

    private SkillExchangeServiceImpl service;

    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        service = new SkillExchangeServiceImpl(userSkillRepository, userRepository,
                compatibilityService, presenceService, blockUserRepository);
        me = user(1L, "alice");
        other = user(2L, "bob");
    }

    private User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        u.setUsername(username);
        u.setName(username + " Name");
        u.setProfileImage("https://cdn/" + username + ".jpg");
        u.setCountry("US");
        return u;
    }

    private UserSkill skill(User u, String name, SkillDirection dir) {
        UserSkill s = UserSkill.builder().user(u).name(name).direction(dir).build();
        s.setUuid(UUID.randomUUID());
        return s;
    }

    private UpdateSkillsRequest.SkillItem reqItem(String name, SkillLevel level) {
        return UpdateSkillsRequest.SkillItem.builder().name(name).level(level).build();
    }

    private CompatibilityScore score(int overall) {
        return CompatibilityScore.builder().overall(overall).build();
    }

    // ── getMine ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMine")
    class GetMine {

        @Test
        @DisplayName("maps offers and wants with levels")
        void mapsOffersAndWants() {
            UserSkill offer = skill(me, "JavaScript", SkillDirection.OFFER);
            offer.setLevel(SkillLevel.ADVANCED);
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER))
                    .thenReturn(List.of(offer));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "Hindi", SkillDirection.WANT)));

            SkillProfileResponse res = service.getMine(me);

            assertThat(res.getOffers()).hasSize(1);
            assertThat(res.getOffers().get(0).getName()).isEqualTo("JavaScript");
            assertThat(res.getOffers().get(0).getLevel()).isEqualTo("ADVANCED");
            assertThat(res.getWants()).hasSize(1);
            assertThat(res.getWants().get(0).getName()).isEqualTo("Hindi");
            assertThat(res.getWants().get(0).getLevel()).isNull();
        }

        @Test
        @DisplayName("no skills → empty lists")
        void empty() {
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER)).thenReturn(List.of());
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT)).thenReturn(List.of());

            SkillProfileResponse res = service.getMine(me);

            assertThat(res.getOffers()).isEmpty();
            assertThat(res.getWants()).isEmpty();
        }
    }

    // ── updateSkills ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateSkills")
    class UpdateSkills {

        @Test
        @DisplayName("happy path → deletes existing then saves trimmed offers + wants with levels")
        @SuppressWarnings("unchecked")
        void happyPath() {
            SkillProfileResponse res = service.updateSkills(me,
                    List.of(reqItem(" JavaScript ", SkillLevel.ADVANCED), reqItem("Python", null)),
                    List.of(reqItem("Hindi", SkillLevel.BEGINNER)));

            verify(userSkillRepository).deleteAllByUser(me);
            ArgumentCaptor<List<UserSkill>> saved = ArgumentCaptor.forClass(List.class);
            verify(userSkillRepository).saveAll(saved.capture());
            List<UserSkill> rows = saved.getValue();
            assertThat(rows).hasSize(3);
            assertThat(rows).allMatch(r -> r.getUser() == me);
            assertThat(rows).filteredOn(r -> r.getDirection() == SkillDirection.OFFER)
                    .extracting(UserSkill::getName, UserSkill::getLevel)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("JavaScript", SkillLevel.ADVANCED),
                            org.assertj.core.groups.Tuple.tuple("Python", null));
            assertThat(rows).filteredOn(r -> r.getDirection() == SkillDirection.WANT)
                    .extracting(UserSkill::getName, UserSkill::getLevel)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Hindi", SkillLevel.BEGINNER));

            // Response round-trips name + level.
            assertThat(res.getOffers())
                    .extracting(SkillProfileResponse.SkillItem::getName,
                            SkillProfileResponse.SkillItem::getLevel)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("JavaScript", "ADVANCED"),
                            org.assertj.core.groups.Tuple.tuple("Python", null));
            assertThat(res.getWants())
                    .extracting(SkillProfileResponse.SkillItem::getName,
                            SkillProfileResponse.SkillItem::getLevel)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Hindi", "BEGINNER"));
        }

        @Test
        @DisplayName("offer with ADVANCED level → persisted entity carries that level")
        @SuppressWarnings("unchecked")
        void persistsLevel() {
            service.updateSkills(me, List.of(reqItem("JavaScript", SkillLevel.ADVANCED)), List.of());

            ArgumentCaptor<List<UserSkill>> saved = ArgumentCaptor.forClass(List.class);
            verify(userSkillRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).singleElement()
                    .satisfies(r -> {
                        assertThat(r.getName()).isEqualTo("JavaScript");
                        assertThat(r.getLevel()).isEqualTo(SkillLevel.ADVANCED);
                    });
        }

        @Test
        @DisplayName("null level is allowed and stored as null")
        @SuppressWarnings("unchecked")
        void nullLevelAllowed() {
            service.updateSkills(me, List.of(reqItem("Python", null)), List.of());

            ArgumentCaptor<List<UserSkill>> saved = ArgumentCaptor.forClass(List.class);
            verify(userSkillRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).singleElement()
                    .satisfies(r -> assertThat(r.getLevel()).isNull());
        }

        @Test
        @DisplayName("case-insensitive de-dupe keeps first occurrence (name + level)")
        @SuppressWarnings("unchecked")
        void dedupe() {
            service.updateSkills(me,
                    List.of(reqItem("Java", SkillLevel.ADVANCED),
                            reqItem("java", SkillLevel.BEGINNER),
                            reqItem("JAVA ", null)),
                    List.of());

            ArgumentCaptor<List<UserSkill>> saved = ArgumentCaptor.forClass(List.class);
            verify(userSkillRepository).saveAll(saved.capture());
            assertThat(saved.getValue())
                    .extracting(UserSkill::getName, UserSkill::getLevel)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Java", SkillLevel.ADVANCED));
        }

        @Test
        @DisplayName("null lists → clears skills (delete + save empty)")
        @SuppressWarnings("unchecked")
        void nullListsClear() {
            SkillProfileResponse res = service.updateSkills(me, null, null);

            verify(userSkillRepository).deleteAllByUser(me);
            ArgumentCaptor<List<UserSkill>> saved = ArgumentCaptor.forClass(List.class);
            verify(userSkillRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).isEmpty();
            assertThat(res.getOffers()).isEmpty();
            assertThat(res.getWants()).isEmpty();
        }

        @Test
        @DisplayName("blank skill name → BadRequest TM_916, nothing written")
        void blankName() {
            assertThatThrownBy(() -> service.updateSkills(me,
                    List.of(reqItem("Java", SkillLevel.ADVANCED), reqItem("   ", null)), List.of()))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_916"));
            verify(userSkillRepository, never()).deleteAllByUser(any());
            verify(userSkillRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("skill name over 60 chars → BadRequest TM_916")
        void tooLongName() {
            String longName = "x".repeat(61);
            assertThatThrownBy(() -> service.updateSkills(me, List.of(reqItem(longName, null)), List.of()))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_916"));
            verify(userSkillRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("more than 20 skills in a direction → TooManyRequests TM_915")
        void overCap() {
            List<UpdateSkillsRequest.SkillItem> tooMany = java.util.stream.IntStream.rangeClosed(1, 21)
                    .mapToObj(i -> reqItem("skill" + i, null)).toList();
            assertThatThrownBy(() -> service.updateSkills(me, tooMany, List.of()))
                    .isInstanceOfSatisfying(TooManyRequestsException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_915"));
            verify(userSkillRepository, never()).saveAll(any());
        }
    }

    // ── findMatches ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findMatches")
    class FindMatches {

        @Test
        @DisplayName("no wants → empty, offerer lookup never runs")
        void noWants() {
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT)).thenReturn(List.of());

            assertThat(service.findMatches(me)).isEmpty();
            verify(userSkillRepository, never())
                    .findByDirectionAndNameIgnoreCaseExcludingUser(any(), any(), any());
        }

        @Test
        @DisplayName("wants but no offerers → empty")
        void noOfferers() {
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "JavaScript", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER)).thenReturn(List.of());
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "JavaScript", 1L)).thenReturn(List.of());

            assertThat(service.findMatches(me)).isEmpty();
        }

        @Test
        @DisplayName("one-way match → reciprocal false, teaches me but wants nothing I offer")
        void oneWay() {
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "JavaScript", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER))
                    .thenReturn(List.of(skill(me, "Hindi", SkillDirection.OFFER)));
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "JavaScript", 1L))
                    .thenReturn(List.of(skill(other, "JavaScript", SkillDirection.OFFER)));
            when(userSkillRepository.findByUserAndDirection(other, SkillDirection.WANT))
                    .thenReturn(List.of(skill(other, "French", SkillDirection.WANT)));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("bob"));
            when(compatibilityService.score(me, other)).thenReturn(score(50));

            List<SkillMatchResponse> res = service.findMatches(me);

            assertThat(res).hasSize(1);
            SkillMatchResponse m = res.get(0);
            assertThat(m.getUsername()).isEqualTo("bob");
            assertThat(m.getTheyCanTeachYou()).containsExactly("JavaScript");
            assertThat(m.getYouCanTeachThem()).isEmpty();
            assertThat(m.isReciprocal()).isFalse();
            assertThat(m.isOnline()).isTrue();
            assertThat(m.getCompatibility().getOverall()).isEqualTo(50);
        }

        @Test
        @DisplayName("reciprocal match → reciprocal true, youCanTeachThem populated (case-insensitive)")
        void reciprocal() {
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "JavaScript", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER))
                    .thenReturn(List.of(skill(me, "Hindi", SkillDirection.OFFER)));
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "JavaScript", 1L))
                    .thenReturn(List.of(skill(other, "JavaScript", SkillDirection.OFFER)));
            when(userSkillRepository.findByUserAndDirection(other, SkillDirection.WANT))
                    .thenReturn(List.of(skill(other, "hindi", SkillDirection.WANT)));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            when(compatibilityService.score(me, other)).thenReturn(score(70));

            List<SkillMatchResponse> res = service.findMatches(me);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).isReciprocal()).isTrue();
            // Reflects the caller's own casing of the offered skill.
            assertThat(res.get(0).getYouCanTeachThem()).containsExactly("Hindi");
            assertThat(res.get(0).isOnline()).isFalse();
        }

        @Test
        @DisplayName("blocked candidate (either direction) is skipped")
        void blockedSkipped() {
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "JavaScript", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER)).thenReturn(List.of());
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "JavaScript", 1L))
                    .thenReturn(List.of(skill(other, "JavaScript", SkillDirection.OFFER)));
            when(blockUserRepository.existsByUserAndBlocked(me, other)).thenReturn(true);

            assertThat(service.findMatches(me)).isEmpty();
            verify(userSkillRepository, never()).findByUserAndDirection(other, SkillDirection.WANT);
        }

        @Test
        @DisplayName("deleted / banned / guest offerers are skipped")
        void inactiveOfferersSkipped() {
            User deleted = user(3L, "carol");
            deleted.setDeleted(true);
            User banned = user(4L, "dave");
            banned.setBanned(true);
            User guest = user(5L, "erin");
            guest.setGuest(true);

            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "JavaScript", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER)).thenReturn(List.of());
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "JavaScript", 1L))
                    .thenReturn(List.of(
                            skill(deleted, "JavaScript", SkillDirection.OFFER),
                            skill(banned, "JavaScript", SkillDirection.OFFER),
                            skill(guest, "JavaScript", SkillDirection.OFFER)));

            assertThat(service.findMatches(me)).isEmpty();
        }

        @Test
        @DisplayName("reciprocal ranks above one-way regardless of compatibility")
        void reciprocalRanksFirst() {
            User oneWay = user(2L, "bob");     // offers JS, wants nothing I offer
            User recip = user(3L, "carol");    // offers Python, wants Hindi (which I offer)

            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(
                            skill(me, "JavaScript", SkillDirection.WANT),
                            skill(me, "Python", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER))
                    .thenReturn(List.of(skill(me, "Hindi", SkillDirection.OFFER)));
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "JavaScript", 1L))
                    .thenReturn(List.of(skill(oneWay, "JavaScript", SkillDirection.OFFER)));
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "Python", 1L))
                    .thenReturn(List.of(skill(recip, "Python", SkillDirection.OFFER)));
            when(userSkillRepository.findByUserAndDirection(oneWay, SkillDirection.WANT))
                    .thenReturn(List.of(skill(oneWay, "French", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(recip, SkillDirection.WANT))
                    .thenReturn(List.of(skill(recip, "Hindi", SkillDirection.WANT)));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            // one-way has HIGHER compatibility, but reciprocity must still win.
            when(compatibilityService.score(me, oneWay)).thenReturn(score(99));
            when(compatibilityService.score(me, recip)).thenReturn(score(10));

            List<SkillMatchResponse> res = service.findMatches(me);

            assertThat(res).hasSize(2);
            assertThat(res.get(0).getUsername()).isEqualTo("carol");
            assertThat(res.get(0).isReciprocal()).isTrue();
            assertThat(res.get(1).getUsername()).isEqualTo("bob");
            assertThat(res.get(1).isReciprocal()).isFalse();
        }

        @Test
        @DisplayName("presence failure fails open (everyone offline), match still returned")
        void presenceFailOpen() {
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "JavaScript", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER)).thenReturn(List.of());
            when(userSkillRepository.findByDirectionAndNameIgnoreCaseExcludingUser(
                    SkillDirection.OFFER, "JavaScript", 1L))
                    .thenReturn(List.of(skill(other, "JavaScript", SkillDirection.OFFER)));
            lenient().when(userSkillRepository.findByUserAndDirection(other, SkillDirection.WANT)).thenReturn(List.of());
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(presenceService.getOnlineUsernames()).thenThrow(new RuntimeException("redis down"));
            when(compatibilityService.score(me, other)).thenReturn(score(40));

            List<SkillMatchResponse> res = service.findMatches(me);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).isOnline()).isFalse();
        }
    }

    // ── startStudySession ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("startStudySession")
    class StartStudySession {

        @Test
        @DisplayName("invalid uuid → BadRequest TM_914")
        void invalidUuid() {
            assertThatThrownBy(() -> service.startStudySession(me, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_914"));
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("unknown uuid → NotFound TM_917")
        void notFound() {
            UUID uuid = UUID.randomUUID();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.startStudySession(me, uuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_917"));
        }

        @Test
        @DisplayName("deleted target → NotFound TM_917")
        void deletedTarget() {
            other.setDeleted(true);
            when(userRepository.findByUuid(other.getUuid())).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.startStudySession(me, other.getUuid().toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_917"));
        }

        @Test
        @DisplayName("starting with yourself → BadRequest TM_919")
        void self() {
            when(userRepository.findByUuid(me.getUuid())).thenReturn(Optional.of(me));

            assertThatThrownBy(() -> service.startStudySession(me, me.getUuid().toString()))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_919"));
        }

        @Test
        @DisplayName("blocked (either direction) → Forbidden TM_918")
        void blocked() {
            when(userRepository.findByUuid(other.getUuid())).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(me, other)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(other, me)).thenReturn(true);

            assertThatThrownBy(() -> service.startStudySession(me, other.getUuid().toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_918"));
        }

        @Test
        @DisplayName("happy path → returns partner card with skill alignment")
        void happyPath() {
            when(userRepository.findByUuid(other.getUuid())).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT))
                    .thenReturn(List.of(skill(me, "JavaScript", SkillDirection.WANT)));
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER))
                    .thenReturn(List.of(skill(me, "Hindi", SkillDirection.OFFER)));
            when(userSkillRepository.findByUser(other))
                    .thenReturn(List.of(
                            skill(other, "JavaScript", SkillDirection.OFFER),
                            skill(other, "Hindi", SkillDirection.WANT)));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("bob"));
            when(compatibilityService.score(me, other)).thenReturn(score(80));

            SkillMatchResponse res = service.startStudySession(me, other.getUuid().toString());

            assertThat(res.getUserUuid()).isEqualTo(other.getUuid().toString());
            assertThat(res.getUsername()).isEqualTo("bob");
            assertThat(res.getTheyCanTeachYou()).containsExactly("JavaScript");
            assertThat(res.getYouCanTeachThem()).containsExactly("Hindi");
            assertThat(res.isReciprocal()).isTrue();
            assertThat(res.isOnline()).isTrue();
            assertThat(res.getCompatibility().getOverall()).isEqualTo(80);
        }

        @Test
        @DisplayName("compatibility scoring failure is swallowed (null score), card still returned")
        void compatibilityFailOpen() {
            when(userRepository.findByUuid(other.getUuid())).thenReturn(Optional.of(other));
            when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.WANT)).thenReturn(List.of());
            when(userSkillRepository.findByUserAndDirection(me, SkillDirection.OFFER)).thenReturn(List.of());
            when(userSkillRepository.findByUser(other)).thenReturn(List.of());
            lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            when(compatibilityService.score(me, other)).thenThrow(new RuntimeException("boom"));

            SkillMatchResponse res = service.startStudySession(me, other.getUuid().toString());

            assertThat(res.getCompatibility()).isNull();
            assertThat(res.getTheyCanTeachYou()).isEmpty();
            assertThat(res.getYouCanTeachThem()).isEmpty();
            assertThat(res.isReciprocal()).isFalse();
        }
    }
}
