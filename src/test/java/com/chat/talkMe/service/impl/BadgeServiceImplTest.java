package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BadgeEndorsement;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserBadge;
import com.chat.talkMe.dto.response.BadgeResponse;
import com.chat.talkMe.enums.BadgeType;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.BadgeEndorsementRepository;
import com.chat.talkMe.repository.UserBadgeRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.ReputationRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link BadgeServiceImpl} — peer-endorseable cosmetic badges
 * (feature #30).
 *
 * <p>Key invariants: (1) guests/banned endorsers, self-endorsement and endorsing an
 * inactive account are all rejected; (2) re-endorsing the same peer/type (or losing the
 * unique-constraint race) is an idempotent no-op that returns current state; (3) a badge is
 * awarded once distinct endorsers cross the threshold, stamping {@code awardedAt} and
 * recording BADGE_EARNED exactly once; (4) every accepted endorsement records
 * ENDORSEMENT_RECEIVED; (5) reputation recording never breaks endorsement (swallowed).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeServiceImpl (unit)")
class BadgeServiceImplTest {

    @Mock
    private UserBadgeRepository userBadgeRepository;
    @Mock
    private BadgeEndorsementRepository badgeEndorsementRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReputationRecorder reputationRecorder;

    private BadgeServiceImpl service;

    private static final BadgeType TYPE = BadgeType.GREAT_LISTENER;

    @BeforeEach
    void setUp() {
        service = new BadgeServiceImpl(userBadgeRepository, badgeEndorsementRepository,
                userRepository, reputationRecorder);
    }

    private User newUser(long id, UUID uuid) {
        User u = User.builder().username("u" + id).name("Name" + id).build();
        u.setId(id);
        u.setUuid(uuid);
        return u;
    }

    private UserBadge badge(User user, BadgeType type, int count, Instant awardedAt) {
        UserBadge b = UserBadge.builder()
                .user(user).badgeType(type).endorsementCount(count).build();
        b.setAwardedAt(awardedAt);
        return b;
    }

    // ── listBadges ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listBadges")
    class ListBadges {

        @Test
        @DisplayName("maps every persisted badge to its DTO (earned + unearned)")
        void mapsAllBadges() {
            UUID uuid = UUID.randomUUID();
            User user = newUser(1L, uuid);
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(user));
            Instant awarded = Instant.parse("2026-01-02T03:04:05Z");
            when(userBadgeRepository.findByUser(user)).thenReturn(List.of(
                    badge(user, BadgeType.GREAT_LISTENER, 5, awarded),
                    badge(user, BadgeType.FUNNY, 1, null)));

            List<BadgeResponse> out = service.listBadges(uuid.toString());

            assertThat(out).hasSize(2);
            BadgeResponse earned = out.get(0);
            assertThat(earned.getType()).isEqualTo("GREAT_LISTENER");
            assertThat(earned.getLabel()).isEqualTo("Great Listener");
            assertThat(earned.getEndorsementCount()).isEqualTo(5);
            assertThat(earned.isEarned()).isTrue();
            assertThat(earned.getAwardedAt()).isEqualTo(awarded.toString());

            BadgeResponse unearned = out.get(1);
            assertThat(unearned.getType()).isEqualTo("FUNNY");
            assertThat(unearned.isEarned()).isFalse();
            assertThat(unearned.getAwardedAt()).isNull();
        }

        @Test
        @DisplayName("no badges → empty list (no NPE)")
        void emptyWhenNone() {
            UUID uuid = UUID.randomUUID();
            User user = newUser(1L, uuid);
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(user));
            when(userBadgeRepository.findByUser(user)).thenReturn(List.of());

            assertThat(service.listBadges(uuid.toString())).isEmpty();
        }

        @Test
        @DisplayName("malformed uuid → BadRequestException TM_922")
        void invalidUuid() {
            assertThatThrownBy(() -> service.listBadges("not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_922"));
        }

        @Test
        @DisplayName("unknown user → NotFoundException TM_404")
        void userNotFound() {
            UUID uuid = UUID.randomUUID();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listBadges(uuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_404"));
        }
    }

    // ── endorse ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("endorse")
    class Endorse {

        @Test
        @DisplayName("null badge type → BadRequestException TM_920")
        void nullBadgeType() {
            User endorser = newUser(1L, UUID.randomUUID());

            assertThatThrownBy(() -> service.endorse(endorser, UUID.randomUUID().toString(), null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_920"));
        }

        @Test
        @DisplayName("guest endorser → ForbiddenException TM_923")
        void guestEndorser() {
            User endorser = User.builder().username("g").name("G").isGuest(true).build();
            endorser.setId(1L);

            assertThatThrownBy(() -> service.endorse(endorser, UUID.randomUUID().toString(), TYPE))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_923"));
        }

        @Test
        @DisplayName("banned endorser → ForbiddenException TM_923")
        void bannedEndorser() {
            User endorser = User.builder().username("b").name("B").banned(true).build();
            endorser.setId(1L);

            assertThatThrownBy(() -> service.endorse(endorser, UUID.randomUUID().toString(), TYPE))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_923"));
        }

        @Test
        @DisplayName("malformed recipient uuid → BadRequestException TM_922")
        void invalidRecipientUuid() {
            User endorser = newUser(1L, UUID.randomUUID());

            assertThatThrownBy(() -> service.endorse(endorser, "bad-uuid", TYPE))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_922"));
        }

        @Test
        @DisplayName("unknown recipient → NotFoundException TM_404")
        void recipientNotFound() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            when(userRepository.findByUuid(rid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.endorse(endorser, rid.toString(), TYPE))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_404"));
        }

        @Test
        @DisplayName("self-endorsement → BadRequestException TM_921")
        void selfEndorsement() {
            UUID uuid = UUID.randomUUID();
            User endorser = newUser(7L, uuid);
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(endorser));

            assertThatThrownBy(() -> service.endorse(endorser, uuid.toString(), TYPE))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_921"));
        }

        @Test
        @DisplayName("recipient is a guest → BadRequestException TM_924")
        void recipientGuest() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = User.builder().username("r").name("R").isGuest(true).build();
            recipient.setId(2L);
            recipient.setUuid(rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));

            assertThatThrownBy(() -> service.endorse(endorser, rid.toString(), TYPE))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_924"));
        }

        @Test
        @DisplayName("recipient is banned → BadRequestException TM_924")
        void recipientBanned() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = User.builder().username("r").name("R").banned(true).build();
            recipient.setId(2L);
            recipient.setUuid(rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));

            assertThatThrownBy(() -> service.endorse(endorser, rid.toString(), TYPE))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_924"));
        }

        @Test
        @DisplayName("recipient is soft-deleted → BadRequestException TM_924")
        void recipientDeleted() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            recipient.setDeleted(true);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));

            assertThatThrownBy(() -> service.endorse(endorser, rid.toString(), TYPE))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_924"));
        }

        @Test
        @DisplayName("already endorsed this peer/type → idempotent no-op returning current state")
        void alreadyEndorsedReturnsExistingBadge() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));
            when(badgeEndorsementRepository
                    .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, TYPE))
                    .thenReturn(true);
            UserBadge existing = badge(recipient, TYPE, 4, Instant.parse("2026-01-01T00:00:00Z"));
            when(userBadgeRepository.findByUserAndBadgeType(recipient, TYPE))
                    .thenReturn(Optional.of(existing));

            BadgeResponse res = service.endorse(endorser, rid.toString(), TYPE);

            assertThat(res.getEndorsementCount()).isEqualTo(4);
            assertThat(res.isEarned()).isTrue();
            verify(badgeEndorsementRepository, never()).save(any());
            verify(userBadgeRepository, never()).save(any());
            verify(reputationRecorder, never()).record(any(), any(), any());
        }

        @Test
        @DisplayName("already endorsed but no badge row yet → default un-earned state via count query")
        void alreadyEndorsedNoBadgeRow() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));
            when(badgeEndorsementRepository
                    .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, TYPE))
                    .thenReturn(true);
            when(userBadgeRepository.findByUserAndBadgeType(recipient, TYPE))
                    .thenReturn(Optional.empty());
            when(badgeEndorsementRepository.countByRecipientAndBadgeType(recipient, TYPE))
                    .thenReturn(2L);

            BadgeResponse res = service.endorse(endorser, rid.toString(), TYPE);

            assertThat(res.getType()).isEqualTo("GREAT_LISTENER");
            assertThat(res.getEndorsementCount()).isEqualTo(2);
            assertThat(res.isEarned()).isFalse();
            verify(badgeEndorsementRepository, never()).save(any());
        }

        @Test
        @DisplayName("unique-constraint race on save → treated as no-op, current state returned")
        void raceOnSaveReturnsCurrentState() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));
            when(badgeEndorsementRepository
                    .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, TYPE))
                    .thenReturn(false);
            when(badgeEndorsementRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("dup"));
            UserBadge existing = badge(recipient, TYPE, 3, Instant.parse("2026-01-01T00:00:00Z"));
            when(userBadgeRepository.findByUserAndBadgeType(recipient, TYPE))
                    .thenReturn(Optional.of(existing));

            BadgeResponse res = service.endorse(endorser, rid.toString(), TYPE);

            assertThat(res.getEndorsementCount()).isEqualTo(3);
            verify(userBadgeRepository, never()).save(any());
            verify(reputationRecorder, never()).record(any(), any(), any());
        }

        @Test
        @DisplayName("new endorsement below threshold → saves count, records ENDORSEMENT_RECEIVED only")
        void newEndorsementBelowThreshold() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));
            when(badgeEndorsementRepository
                    .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, TYPE))
                    .thenReturn(false);
            when(badgeEndorsementRepository.countByRecipientAndBadgeType(recipient, TYPE))
                    .thenReturn(1L);
            when(userBadgeRepository.findByUserAndBadgeType(recipient, TYPE))
                    .thenReturn(Optional.empty());
            when(userBadgeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BadgeResponse res = service.endorse(endorser, rid.toString(), TYPE);

            // Endorsement row persisted with correct triple.
            ArgumentCaptor<BadgeEndorsement> eCap = ArgumentCaptor.forClass(BadgeEndorsement.class);
            verify(badgeEndorsementRepository).save(eCap.capture());
            assertThat(eCap.getValue().getEndorser()).isSameAs(endorser);
            assertThat(eCap.getValue().getRecipient()).isSameAs(recipient);
            assertThat(eCap.getValue().getBadgeType()).isEqualTo(TYPE);

            // Badge row saved with count=1 and NOT awarded.
            ArgumentCaptor<UserBadge> bCap = ArgumentCaptor.forClass(UserBadge.class);
            verify(userBadgeRepository).save(bCap.capture());
            assertThat(bCap.getValue().getEndorsementCount()).isEqualTo(1);
            assertThat(bCap.getValue().getAwardedAt()).isNull();

            assertThat(res.isEarned()).isFalse();
            assertThat(res.getEndorsementCount()).isEqualTo(1);

            verify(reputationRecorder).record(2L, ReputationEventType.ENDORSEMENT_RECEIVED,
                    "badge:GREAT_LISTENER:from:1");
            verify(reputationRecorder, never())
                    .record(any(), eq(ReputationEventType.BADGE_EARNED), any());
        }

        @Test
        @DisplayName("crossing the threshold (first award) → stamps awardedAt, records both events")
        void firstAwardCrossesThreshold() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));
            when(badgeEndorsementRepository
                    .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, TYPE))
                    .thenReturn(false);
            when(badgeEndorsementRepository.countByRecipientAndBadgeType(recipient, TYPE))
                    .thenReturn(3L);
            UserBadge existing = badge(recipient, TYPE, 2, null); // not yet awarded
            when(userBadgeRepository.findByUserAndBadgeType(recipient, TYPE))
                    .thenReturn(Optional.of(existing));
            when(userBadgeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BadgeResponse res = service.endorse(endorser, rid.toString(), TYPE);

            ArgumentCaptor<UserBadge> bCap = ArgumentCaptor.forClass(UserBadge.class);
            verify(userBadgeRepository).save(bCap.capture());
            assertThat(bCap.getValue().getEndorsementCount()).isEqualTo(3);
            assertThat(bCap.getValue().getAwardedAt()).isNotNull();

            assertThat(res.isEarned()).isTrue();
            assertThat(res.getEndorsementCount()).isEqualTo(3);

            verify(reputationRecorder).record(2L, ReputationEventType.ENDORSEMENT_RECEIVED,
                    "badge:GREAT_LISTENER:from:1");
            verify(reputationRecorder).record(2L, ReputationEventType.BADGE_EARNED,
                    "badge:GREAT_LISTENER");
        }

        @Test
        @DisplayName("endorsement beyond an already-earned badge → no re-award, only ENDORSEMENT_RECEIVED")
        void beyondThresholdAlreadyEarned() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));
            when(badgeEndorsementRepository
                    .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, TYPE))
                    .thenReturn(false);
            when(badgeEndorsementRepository.countByRecipientAndBadgeType(recipient, TYPE))
                    .thenReturn(4L);
            Instant awarded = Instant.parse("2026-01-01T00:00:00Z");
            UserBadge existing = badge(recipient, TYPE, 3, awarded); // already earned
            when(userBadgeRepository.findByUserAndBadgeType(recipient, TYPE))
                    .thenReturn(Optional.of(existing));
            when(userBadgeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BadgeResponse res = service.endorse(endorser, rid.toString(), TYPE);

            ArgumentCaptor<UserBadge> bCap = ArgumentCaptor.forClass(UserBadge.class);
            verify(userBadgeRepository).save(bCap.capture());
            assertThat(bCap.getValue().getEndorsementCount()).isEqualTo(4);
            // awardedAt unchanged (still the original timestamp — not re-stamped).
            assertThat(bCap.getValue().getAwardedAt()).isEqualTo(awarded);

            assertThat(res.isEarned()).isTrue();
            verify(reputationRecorder).record(2L, ReputationEventType.ENDORSEMENT_RECEIVED,
                    "badge:GREAT_LISTENER:from:1");
            verify(reputationRecorder, never())
                    .record(any(), eq(ReputationEventType.BADGE_EARNED), any());
        }

        @Test
        @DisplayName("reputation recorder failure is swallowed — endorsement still succeeds")
        void reputationFailureSwallowed() {
            User endorser = newUser(1L, UUID.randomUUID());
            UUID rid = UUID.randomUUID();
            User recipient = newUser(2L, rid);
            when(userRepository.findByUuid(rid)).thenReturn(Optional.of(recipient));
            when(badgeEndorsementRepository
                    .existsByEndorserAndRecipientAndBadgeType(endorser, recipient, TYPE))
                    .thenReturn(false);
            when(badgeEndorsementRepository.countByRecipientAndBadgeType(recipient, TYPE))
                    .thenReturn(1L);
            when(userBadgeRepository.findByUserAndBadgeType(recipient, TYPE))
                    .thenReturn(Optional.empty());
            when(userBadgeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            Mockito.doThrow(new RuntimeException("ledger down"))
                    .when(reputationRecorder).record(any(), any(), any());

            BadgeResponse res = service.endorse(endorser, rid.toString(), TYPE);

            assertThat(res).isNotNull();
            assertThat(res.getEndorsementCount()).isEqualTo(1);
        }
    }
}
