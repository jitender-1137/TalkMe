package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.SecretCrush;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.SecretCrushMatchResponse;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.enums.SecretCrushStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.SecretCrushRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.CompatibilityService;
import com.chat.talkMe.service.NotificationService;
import com.chat.talkMe.service.ReputationRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link SecretCrushServiceImpl} (feature #9). Covers the full
 * add/withdraw/list lifecycle: target resolution (invalid uuid / not-found), self + guest +
 * banned guards, the symmetric block short-circuit, the active-crush cap, the idempotent
 * re-surface of an already-matched pair, the one-sided-crush (no reciprocal) path, and the
 * mutual-match side effects (both rows flipped to MATCHED, symmetric notify + reputation).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecretCrushServiceImpl (unit)")
class SecretCrushServiceImplTest {

    @Mock
    private SecretCrushRepository secretCrushRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CompatibilityService compatibilityService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ReputationRecorder reputationRecorder;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private BlockUserRepository blockUserRepository;

    private SecretCrushServiceImpl service;

    private User crusher;
    private User target;

    @BeforeEach
    void setUp() {
        service = new SecretCrushServiceImpl(secretCrushRepository, userRepository, compatibilityService,
                notificationService, reputationRecorder, messagingTemplate, blockUserRepository);
        crusher = user(1L, "alice");
        target = user(2L, "bob");
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

    private SecretCrush crush(User c, User t, SecretCrushStatus status) {
        SecretCrush sc = SecretCrush.builder().crusher(c).target(t).status(status).build();
        sc.setUuid(UUID.randomUUID());
        return sc;
    }

    private CompatibilityScore score(int overall) {
        return CompatibilityScore.builder().overall(overall).build();
    }

    /**
     * Resolve the target uuid to the target user and clear both block directions.
     */
    private void resolvableTargetWithNoBlocks() {
        when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
        when(blockUserRepository.existsByUserAndBlocked(crusher, target)).thenReturn(false);
        when(blockUserRepository.existsByUserAndBlocked(target, crusher)).thenReturn(false);
    }

    @Nested
    @DisplayName("addCrush")
    class AddCrush {

        @Test
        @DisplayName("invalid uuid string → BadRequest TM_913")
        void invalidUuid() {
            assertThatThrownBy(() -> service.addCrush(crusher, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_913"));
            verifyNoInteractions(secretCrushRepository);
        }

        @Test
        @DisplayName("target uuid not found → NotFound TM_404")
        void targetNotFound() {
            UUID uuid = UUID.randomUUID();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addCrush(crusher, uuid.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_404"));
        }

        @Test
        @DisplayName("crushing on yourself → BadRequest TM_910")
        void selfCrush() {
            when(userRepository.findByUuid(crusher.getUuid())).thenReturn(Optional.of(crusher));

            assertThatThrownBy(() -> service.addCrush(crusher, crusher.getUuid().toString()))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_910"));
            verify(secretCrushRepository, never()).save(any());
        }

        @Test
        @DisplayName("target is a guest → BadRequest TM_911")
        void guestTarget() {
            target.setGuest(true);
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));

            assertThatThrownBy(() -> service.addCrush(crusher, target.getUuid().toString()))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_911"));
        }

        @Test
        @DisplayName("target is banned → BadRequest TM_911")
        void bannedTarget() {
            target.setBanned(true);
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));

            assertThatThrownBy(() -> service.addCrush(crusher, target.getUuid().toString()))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_911"));
        }

        @Test
        @DisplayName("crusher has blocked the target → returns non-match, no crush created")
        void crusherBlockedTarget() {
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            when(blockUserRepository.existsByUserAndBlocked(crusher, target)).thenReturn(true);

            SecretCrushMatchResponse res = service.addCrush(crusher, target.getUuid().toString());

            assertThat(res.isMatched()).isFalse();
            assertThat(res.getPartnerUuid()).isNull();
            verify(secretCrushRepository, never()).save(any());
        }

        @Test
        @DisplayName("target has blocked the crusher → returns non-match, no crush created")
        void targetBlockedCrusher() {
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            when(blockUserRepository.existsByUserAndBlocked(crusher, target)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(target, crusher)).thenReturn(true);

            SecretCrushMatchResponse res = service.addCrush(crusher, target.getUuid().toString());

            assertThat(res.isMatched()).isFalse();
            verify(secretCrushRepository, never()).save(any());
        }

        @Test
        @DisplayName("already-matched pair → re-surfaces the match without re-notifying")
        void alreadyMatchedResurfaces() {
            resolvableTargetWithNoBlocks();
            SecretCrush existing = crush(crusher, target, SecretCrushStatus.MATCHED);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target))
                    .thenReturn(Optional.of(existing));
            when(compatibilityService.score(crusher, target)).thenReturn(score(88));

            SecretCrushMatchResponse res = service.addCrush(crusher, target.getUuid().toString());

            assertThat(res.isMatched()).isTrue();
            assertThat(res.getPartnerUuid()).isEqualTo(target.getUuid().toString());
            assertThat(res.getCompatibility().getOverall()).isEqualTo(88);
            // Idempotent re-surface: no writes, no notifications, no reputation.
            verify(secretCrushRepository, never()).save(any());
            verifyNoInteractions(notificationService, reputationRecorder, messagingTemplate);
        }

        @Test
        @DisplayName("new one-sided crush, no reciprocal → saves ACTIVE, returns non-match")
        void newCrushNoReciprocal() {
            resolvableTargetWithNoBlocks();
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target)).thenReturn(Optional.empty());
            when(secretCrushRepository.countByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE)).thenReturn(3L);
            when(secretCrushRepository.findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            SecretCrushMatchResponse res = service.addCrush(crusher, target.getUuid().toString());

            assertThat(res.isMatched()).isFalse();
            assertThat(res.getPartnerUuid()).isNull();
            ArgumentCaptor<SecretCrush> saved = ArgumentCaptor.forClass(SecretCrush.class);
            verify(secretCrushRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(SecretCrushStatus.ACTIVE);
            assertThat(saved.getValue().getCrusher()).isEqualTo(crusher);
            assertThat(saved.getValue().getTarget()).isEqualTo(target);
            verifyNoInteractions(notificationService, reputationRecorder);
        }

        @Test
        @DisplayName("new crush at the active cap (20) → TooManyRequests TM_912")
        void newCrushAtCap() {
            resolvableTargetWithNoBlocks();
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target)).thenReturn(Optional.empty());
            when(secretCrushRepository.countByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE)).thenReturn(20L);

            assertThatThrownBy(() -> service.addCrush(crusher, target.getUuid().toString()))
                    .isInstanceOfSatisfying(TooManyRequestsException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_912"));
            verify(secretCrushRepository, never()).save(any());
        }

        @Test
        @DisplayName("reactivating a WITHDRAWN crush (under cap) → flips to ACTIVE, non-match")
        void reactivateWithdrawn() {
            resolvableTargetWithNoBlocks();
            SecretCrush withdrawn = crush(crusher, target, SecretCrushStatus.WITHDRAWN);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target))
                    .thenReturn(Optional.of(withdrawn));
            when(secretCrushRepository.countByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE)).thenReturn(5L);
            when(secretCrushRepository.findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            SecretCrushMatchResponse res = service.addCrush(crusher, target.getUuid().toString());

            assertThat(res.isMatched()).isFalse();
            assertThat(withdrawn.getStatus()).isEqualTo(SecretCrushStatus.ACTIVE);
            verify(secretCrushRepository).save(withdrawn);
        }

        @Test
        @DisplayName("reactivating a WITHDRAWN crush at the cap → TooManyRequests TM_912")
        void reactivateWithdrawnAtCap() {
            resolvableTargetWithNoBlocks();
            SecretCrush withdrawn = crush(crusher, target, SecretCrushStatus.WITHDRAWN);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target))
                    .thenReturn(Optional.of(withdrawn));
            when(secretCrushRepository.countByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE)).thenReturn(20L);

            assertThatThrownBy(() -> service.addCrush(crusher, target.getUuid().toString()))
                    .isInstanceOfSatisfying(TooManyRequestsException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_912"));
            verify(secretCrushRepository, never()).save(any());
        }

        @Test
        @DisplayName("existing ACTIVE crush skips the cap check and is not re-created")
        void existingActiveSkipsCap() {
            resolvableTargetWithNoBlocks();
            SecretCrush active = crush(crusher, target, SecretCrushStatus.ACTIVE);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target))
                    .thenReturn(Optional.of(active));
            when(secretCrushRepository.findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            SecretCrushMatchResponse res = service.addCrush(crusher, target.getUuid().toString());

            assertThat(res.isMatched()).isFalse();
            // Cap is never probed for an already-ACTIVE crush.
            verify(secretCrushRepository, never()).countByCrusherAndStatus(any(), any());
            verify(secretCrushRepository).save(active);
        }

        @Test
        @DisplayName("mutual crush → both rows MATCHED, symmetric notify + reputation")
        void mutualMatch() {
            resolvableTargetWithNoBlocks();
            SecretCrush mine = crush(crusher, target, SecretCrushStatus.ACTIVE);
            SecretCrush recip = crush(target, crusher, SecretCrushStatus.ACTIVE);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target)).thenReturn(Optional.of(mine));
            when(secretCrushRepository.findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(Optional.of(recip));
            when(compatibilityService.score(crusher, target)).thenReturn(score(91));

            SecretCrushMatchResponse res = service.addCrush(crusher, target.getUuid().toString());

            assertThat(res.isMatched()).isTrue();
            assertThat(res.getPartnerUuid()).isEqualTo(target.getUuid().toString());
            assertThat(res.getPartnerUsername()).isEqualTo("bob");
            assertThat(res.getCompatibility().getOverall()).isEqualTo(91);

            assertThat(mine.getStatus()).isEqualTo(SecretCrushStatus.MATCHED);
            assertThat(recip.getStatus()).isEqualTo(SecretCrushStatus.MATCHED);
            // mine saved once pre-probe + once on match; recip saved once on match.
            verify(secretCrushRepository, times(2)).save(mine);
            verify(secretCrushRepository).save(recip);

            // Symmetric notifications to both participants.
            verify(notificationService).createNotification(eq(crusher), anyString(), anyString(),
                    eq("SECRET_CRUSH_MATCHED"), eq(target.getUuid().toString()), eq(target), anyString());
            verify(notificationService).createNotification(eq(target), anyString(), anyString(),
                    eq("SECRET_CRUSH_MATCHED"), eq(crusher.getUuid().toString()), eq(crusher), anyString());
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/secret-crush"), any());
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/secret-crush"), any());

            // A positive connection for both sides.
            verify(reputationRecorder).record(eq(crusher.getId()),
                    eq(ReputationEventType.CONVERSATION_STARTED), eq(mine.getUuid().toString()));
            verify(reputationRecorder).record(eq(target.getId()),
                    eq(ReputationEventType.CONVERSATION_STARTED), eq(recip.getUuid().toString()));
        }
    }

    @Nested
    @DisplayName("withdrawCrush")
    class WithdrawCrush {

        @Test
        @DisplayName("invalid uuid → BadRequest TM_913")
        void invalidUuid() {
            assertThatThrownBy(() -> service.withdrawCrush(crusher, "nope"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_913"));
        }

        @Test
        @DisplayName("no crush row → silent no-op (no save, no messaging)")
        void noCrushNoop() {
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target)).thenReturn(Optional.empty());

            service.withdrawCrush(crusher, target.getUuid().toString());

            verify(secretCrushRepository, never()).save(any());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("one-sided ACTIVE crush → WITHDRAWN, no partner demotion or push")
        void withdrawActive() {
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            SecretCrush active = crush(crusher, target, SecretCrushStatus.ACTIVE);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target)).thenReturn(Optional.of(active));

            service.withdrawCrush(crusher, target.getUuid().toString());

            assertThat(active.getStatus()).isEqualTo(SecretCrushStatus.WITHDRAWN);
            verify(secretCrushRepository).save(active);
            verify(secretCrushRepository, never())
                    .findByCrusherAndTargetAndStatus(any(), any(), any());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("MATCHED crush withdrawn → partner demoted to ACTIVE + UNMATCHED push")
        void withdrawMatchedDemotesPartner() {
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            SecretCrush mine = crush(crusher, target, SecretCrushStatus.MATCHED);
            SecretCrush recip = crush(target, crusher, SecretCrushStatus.MATCHED);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target)).thenReturn(Optional.of(mine));
            when(secretCrushRepository.findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.MATCHED))
                    .thenReturn(Optional.of(recip));

            service.withdrawCrush(crusher, target.getUuid().toString());

            assertThat(mine.getStatus()).isEqualTo(SecretCrushStatus.WITHDRAWN);
            assertThat(recip.getStatus()).isEqualTo(SecretCrushStatus.ACTIVE);
            verify(secretCrushRepository).save(mine);
            verify(secretCrushRepository).save(recip);
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/secret-crush"), any());
        }

        @Test
        @DisplayName("MATCHED crush withdrawn but partner row gone → still pushes UNMATCHED")
        void withdrawMatchedPartnerMissing() {
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            SecretCrush mine = crush(crusher, target, SecretCrushStatus.MATCHED);
            when(secretCrushRepository.findByCrusherAndTarget(crusher, target)).thenReturn(Optional.of(mine));
            when(secretCrushRepository.findByCrusherAndTargetAndStatus(target, crusher, SecretCrushStatus.MATCHED))
                    .thenReturn(Optional.empty());

            service.withdrawCrush(crusher, target.getUuid().toString());

            assertThat(mine.getStatus()).isEqualTo(SecretCrushStatus.WITHDRAWN);
            verify(secretCrushRepository).save(mine);
            // Only the caller's own row is saved (no partner row to demote).
            verify(secretCrushRepository, times(1)).save(any());
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/secret-crush"), any());
        }
    }

    @Nested
    @DisplayName("listMine")
    class ListMine {

        @Test
        @DisplayName("no crushes → empty list, compatibility never scored")
        void empty() {
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(List.of());
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.MATCHED))
                    .thenReturn(List.of());

            assertThat(service.listMine(crusher)).isEmpty();
            verifyNoInteractions(compatibilityService);
        }

        @Test
        @DisplayName("only active crushes → non-match entries, no compatibility")
        void onlyActive() {
            target.setMood(Mood.FLIRT);
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(List.of(crush(crusher, target, SecretCrushStatus.ACTIVE)));
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.MATCHED))
                    .thenReturn(List.of());

            List<SecretCrushMatchResponse> out = service.listMine(crusher);

            assertThat(out).hasSize(1);
            SecretCrushMatchResponse e = out.get(0);
            assertThat(e.isMatched()).isFalse();
            assertThat(e.getCompatibility()).isNull();
            assertThat(e.getPartnerUuid()).isEqualTo(target.getUuid().toString());
            assertThat(e.getPartnerUsername()).isEqualTo("bob");
            assertThat(e.getPartnerMood()).isEqualTo("FLIRT");
            assertThat(e.getPartnerCountry()).isEqualTo("US");
            verifyNoInteractions(compatibilityService);
        }

        @Test
        @DisplayName("matched crushes → match entries carrying compatibility")
        void matchedWithCompatibility() {
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(List.of());
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.MATCHED))
                    .thenReturn(List.of(crush(crusher, target, SecretCrushStatus.MATCHED)));
            when(compatibilityService.score(crusher, target)).thenReturn(score(77));

            List<SecretCrushMatchResponse> out = service.listMine(crusher);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).isMatched()).isTrue();
            assertThat(out.get(0).getCompatibility().getOverall()).isEqualTo(77);
            assertThat(out.get(0).getPartnerMood()).isNull();
        }

        @Test
        @DisplayName("mixed active + matched → both surfaced, active first")
        void mixed() {
            User other = user(3L, "carol");
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.ACTIVE))
                    .thenReturn(List.of(crush(crusher, target, SecretCrushStatus.ACTIVE)));
            when(secretCrushRepository.findByCrusherAndStatus(crusher, SecretCrushStatus.MATCHED))
                    .thenReturn(List.of(crush(crusher, other, SecretCrushStatus.MATCHED)));
            when(compatibilityService.score(crusher, other)).thenReturn(score(60));

            List<SecretCrushMatchResponse> out = service.listMine(crusher);

            assertThat(out).hasSize(2);
            assertThat(out.get(0).isMatched()).isFalse();
            assertThat(out.get(0).getPartnerUsername()).isEqualTo("bob");
            assertThat(out.get(1).isMatched()).isTrue();
            assertThat(out.get(1).getPartnerUsername()).isEqualTo("carol");
        }
    }
}
