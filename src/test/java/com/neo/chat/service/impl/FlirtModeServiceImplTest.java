package com.neo.chat.service.impl;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatFlirtMode;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.FlirtModeResponse;
import com.neo.chat.enums.ChatType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatFlirtModeRepository;
import com.neo.chat.repository.ChatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link FlirtModeServiceImpl} (feature FLIRT_MODE).
 *
 * <p>No Spring context, no Redis, no DB. Every collaborator is mocked. The service is <b>not</b>
 * class-{@code @Transactional}: {@code enable}/{@code disable} route through {@code setConsentWithRetry}
 * → {@code applyConsentTx} via a self-proxy ({@link ObjectProvider}). We therefore exercise:
 * <ul>
 *   <li>{@code getState} — which drives the private {@code resolve} gate and the viewer-relative
 *       {@code responseFor} keying without needing the proxy;</li>
 *   <li>{@code applyConsentTx} directly (it is {@code public}) — the committed mutation, with the
 *       {@code ObjectProvider} self stubbed to return the instance under test so the lazy
 *       row-create path ({@code createRowInNewTx}) resolves.</li>
 * </ul>
 *
 * <p><b>Keying invariant under test.</b> Consent is stored deterministically by
 * {@code min(id)}/{@code max(id)} ({@code enabledByLow}/{@code enabledByHigh}). A row with
 * {@code enabledByLow=true, enabledByHigh=false} must read as {@code myEnabled=true} for the LOW-id
 * participant and {@code otherEnabled=true} (myEnabled=false) for the HIGH-id participant — the same
 * physical row, two mirror-image perspectives — and is {@code active} only when both are true.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlirtModeServiceImpl (unit)")
class FlirtModeServiceImplTest {

    private static final String CHAT_UUID = "11111111-1111-1111-1111-111111111111";
    private static final long CHAT_PK = 500L;

    // Deterministic low/high: 10 < 20, so id-10 is ALWAYS "low", id-20 is ALWAYS "high"
    // regardless of who initiates.
    private static final long LOW_ID = 10L;
    private static final long HIGH_ID = 20L;

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatFlirtModeRepository flirtModeRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ObjectProvider<FlirtModeServiceImpl> self;

    private FlirtModeServiceImpl service;

    private User lowUser;   // id 10
    private User highUser;  // id 20

    @BeforeEach
    void setUp() {
        service = new FlirtModeServiceImpl(chatRepository, flirtModeRepository, messagingTemplate, self);

        lowUser = userWithId(LOW_ID, "low_user");
        highUser = userWithId(HIGH_ID, "high_user");

        // Self-proxy resolves to the instance under test (drives the lazy create path); save echoes
        // its argument so createRowInNewTx returns the row it just built. Both are only needed by a
        // subset of tests, hence lenient.
        lenient().when(self.getObject()).thenReturn(service);
        lenient().when(flirtModeRepository.save(any(ChatFlirtMode.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private static User userWithId(long id, String username) {
        User u = User.builder().username(username).email(username + "@e.com").name(username).build();
        u.setId(id);
        return u;
    }

    private static Chat chatOf(ChatType type, User... members) {
        Chat chat = Chat.builder().chatType(type).build();
        chat.setId(CHAT_PK);
        for (User u : members) {
            chat.getMembers().add(ChatMember.builder().user(u).build());
        }
        return chat;
    }

    private static Chat privateChat(User a, User b) {
        return chatOf(ChatType.PRIVATE, a, b);
    }

    /**
     * A flirt-mode row keyed low=10 / high=20 with the given per-side flags (active derived).
     */
    private static ChatFlirtMode row(boolean enabledByLow, boolean enabledByHigh) {
        ChatFlirtMode r = ChatFlirtMode.builder()
                .lowUserId(LOW_ID)
                .highUserId(HIGH_ID)
                .enabledByLow(enabledByLow)
                .enabledByHigh(enabledByHigh)
                .build();
        r.recomputeActive();
        return r;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  getState → resolve() gating
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getState — resolve() gating")
    class ResolveGating {

        @Test
        void shouldRejectNonPrivateChatWithBadRequest() {
            // A GROUP chat is ineligible even though the caller IS a member.
            Chat group = chatOf(ChatType.GROUP, lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(group));

            assertThatThrownBy(() -> service.getState(lowUser, CHAT_UUID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_830"));

            // Rejected before ever touching the flirt-mode row.
            verify(flirtModeRepository, never()).findByChat(any());
        }

        @Test
        void shouldRejectNonMemberWithForbidden() {
            User stranger = userWithId(30L, "stranger");
            User another = userWithId(40L, "another");
            // Caller (lowUser, id 10) is NOT among the two members.
            Chat chat = privateChat(stranger, another);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));

            assertThatThrownBy(() -> service.getState(lowUser, CHAT_UUID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));

            verify(flirtModeRepository, never()).findByChat(any());
        }

        @Test
        void shouldRejectMalformedChatUuidWithBadRequest() {
            assertThatThrownBy(() -> service.getState(lowUser, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));

            // The uuid never parses, so the chat is never looked up.
            verify(chatRepository, never()).findByUuidWithMembers(any());
        }

        @Test
        void shouldRejectMissingChatWithNotFound() {
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getState(lowUser, CHAT_UUID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_101"));
        }

        @Test
        void shouldRejectPrivateChatWithNoOtherParticipant() {
            // Caller is the only member → member check passes but there is no "other".
            Chat solo = chatOf(ChatType.PRIVATE, lowUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(solo));

            assertThatThrownBy(() -> service.getState(lowUser, CHAT_UUID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_831"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  getState → viewer-relative response (keying correctness)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getState — viewer-relative keying")
    class Perspective {

        @Test
        void shouldReturnAllFalseWhenNoRowExists() {
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.empty());

            FlirtModeResponse res = service.getState(lowUser, CHAT_UUID);

            assertThat(res.getChatUuid()).isEqualTo(CHAT_UUID);
            assertThat(res.isMyEnabled()).isFalse();
            assertThat(res.isOtherEnabled()).isFalse();
            assertThat(res.isActive()).isFalse();
            // getState is read-only: it must never create a row.
            verify(flirtModeRepository, never()).save(any());
        }

        @Test
        void shouldReflectLowParticipantPerspective() {
            // Row: low opted in, high has not.
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(true, false)));

            // Viewer is the LOW-id participant (id 10).
            FlirtModeResponse res = service.getState(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isTrue();     // == enabledByLow
            assertThat(res.isOtherEnabled()).isFalse(); // == enabledByHigh
            assertThat(res.isActive()).isFalse();
        }

        @Test
        void shouldReflectHighParticipantPerspectiveAsMirrorImage() {
            // SAME logical row (low opted in, high has not) — but viewed by the HIGH-id participant,
            // the myEnabled/otherEnabled must swap.
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(true, false)));

            // Viewer is the HIGH-id participant (id 20).
            FlirtModeResponse res = service.getState(highUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isFalse();   // == enabledByHigh
            assertThat(res.isOtherEnabled()).isTrue(); // == enabledByLow
            assertThat(res.isActive()).isFalse();
        }

        @Test
        void shouldBeActiveForBothViewersWhenBothOptedIn() {
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(true, true)));

            FlirtModeResponse asLow = service.getState(lowUser, CHAT_UUID);
            assertThat(asLow.isMyEnabled()).isTrue();
            assertThat(asLow.isOtherEnabled()).isTrue();
            assertThat(asLow.isActive()).isTrue();

            FlirtModeResponse asHigh = service.getState(highUser, CHAT_UUID);
            assertThat(asHigh.isMyEnabled()).isTrue();
            assertThat(asHigh.isOtherEnabled()).isTrue();
            assertThat(asHigh.isActive()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  applyConsentTx → committed mutation (keying + active recompute)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("applyConsentTx — consent mutation")
    class ApplyConsent {

        @Test
        void lowParticipantEnableShouldSetEnabledByLowOnly() {
            Chat chat = privateChat(lowUser, highUser);
            ChatFlirtMode existing = row(false, false);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(existing));

            service.applyConsentTx(lowUser, CHAT_UUID, true);

            assertThat(existing.isEnabledByLow()).isTrue();
            assertThat(existing.isEnabledByHigh()).isFalse();
            assertThat(existing.isActive()).isFalse(); // only one side → not active
            verify(flirtModeRepository).save(existing);
            // Existing row → no lazy create.
            verify(chatRepository, never()).getReferenceById(any());
        }

        @Test
        void highParticipantEnableShouldSetEnabledByHighAndActivateWhenLowAlreadyOn() {
            Chat chat = privateChat(lowUser, highUser);
            ChatFlirtMode existing = row(true, false); // low already in
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(existing));

            service.applyConsentTx(highUser, CHAT_UUID, true);

            assertThat(existing.isEnabledByHigh()).isTrue();
            assertThat(existing.isEnabledByLow()).isTrue();  // untouched
            assertThat(existing.isActive()).isTrue();         // both now in → active
            verify(flirtModeRepository).save(existing);
        }

        @Test
        void lowParticipantDisableShouldRevertActive() {
            Chat chat = privateChat(lowUser, highUser);
            ChatFlirtMode existing = row(true, true); // currently active
            assertThat(existing.isActive()).isTrue();
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(existing));

            service.applyConsentTx(lowUser, CHAT_UUID, false);

            assertThat(existing.isEnabledByLow()).isFalse();
            assertThat(existing.isEnabledByHigh()).isTrue(); // partner's opt-in preserved
            assertThat(existing.isActive()).isFalse();        // either disabling reverts active
            verify(flirtModeRepository).save(existing);
        }

        @Test
        void shouldLazilyCreateRowOnFirstOptInThenApplyConsent() {
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            // No row yet → getOrCreateRow drives the REQUIRES_NEW create through the self-proxy.
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.empty());
            when(chatRepository.getReferenceById(CHAT_PK)).thenReturn(chat);

            service.applyConsentTx(lowUser, CHAT_UUID, true);

            // The new row must be keyed low=10/high=20 and carry the caller's (low) opt-in.
            ArgumentCaptor<ChatFlirtMode> saved = ArgumentCaptor.forClass(ChatFlirtMode.class);
            verify(flirtModeRepository, Mockito.atLeastOnce()).save(saved.capture());
            ChatFlirtMode finalRow = saved.getValue();
            assertThat(finalRow.getLowUserId()).isEqualTo(LOW_ID);
            assertThat(finalRow.getHighUserId()).isEqualTo(HIGH_ID);
            assertThat(finalRow.isEnabledByLow()).isTrue();
            assertThat(finalRow.isEnabledByHigh()).isFalse();
            assertThat(finalRow.isActive()).isFalse();
            // Create path was taken via the self-proxy.
            verify(chatRepository).getReferenceById(CHAT_PK);
            verify(self, Mockito.atLeastOnce()).getObject();
        }

        @Test
        void shouldStillRejectNonMemberOnMutation() {
            // resolve() guards mutations too, not just reads (IDOR).
            User stranger = userWithId(30L, "stranger");
            Chat chat = privateChat(lowUser, highUser); // caller `stranger` not a member
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));

            assertThatThrownBy(() -> service.applyConsentTx(stranger, CHAT_UUID, true))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));

            verify(flirtModeRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  enable / disable → setConsentWithRetry (retry loop + after-commit pushes)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("enable/disable — setConsentWithRetry + WS delivery")
    class SetConsentWithRetry {

        @Test
        void enableShouldApplyConsentAndPushToBothParticipantsAfterCommit() {
            // self-proxy resolves to the real service (lenient setUp), so applyConsentTx runs inline;
            // the two after-commit pushes must then be delivered per-user on /queue/flirt-mode.
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(false, false)));

            FlirtModeResponse res = service.enable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isTrue();
            assertThat(res.isOtherEnabled()).isFalse();
            verify(messagingTemplate).convertAndSendToUser(
                    eq("low_user"), eq("/queue/flirt-mode"), any());
            verify(messagingTemplate).convertAndSendToUser(
                    eq("high_user"), eq("/queue/flirt-mode"), any());
        }

        @Test
        void disableShouldApplyConsentAndDeliverPushes() {
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(true, true)));

            FlirtModeResponse res = service.disable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isFalse();  // low just disabled
            assertThat(res.isActive()).isFalse();
            verify(messagingTemplate, times(2))
                    .convertAndSendToUser(anyString(), eq("/queue/flirt-mode"), any());
        }

        @Test
        void shouldRetryOnOptimisticLockThenSucceed() {
            // First save loses the version race; the loop re-reads the committed row and re-applies,
            // so the caller's toggle still lands instead of 500ing.
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(false, false)));
            when(flirtModeRepository.save(any(ChatFlirtMode.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException("race", null))
                    .thenAnswer(inv -> inv.getArgument(0));

            FlirtModeResponse res = service.enable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isTrue();
            verify(flirtModeRepository, times(2)).save(any(ChatFlirtMode.class));
            // Pushes only fire on the successful commit (once), never on the rolled-back attempt.
            verify(messagingTemplate, times(2))
                    .convertAndSendToUser(anyString(), eq("/queue/flirt-mode"), any());
        }

        @Test
        void shouldRethrowAfterExhaustingRetryAttempts() {
            // Every attempt loses the race → after MAX_TOGGLE_ATTEMPTS the last exception surfaces.
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(false, false)));
            when(flirtModeRepository.save(any(ChatFlirtMode.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException("race", null));

            assertThatThrownBy(() -> service.enable(lowUser, CHAT_UUID))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);

            verify(flirtModeRepository, times(3)).save(any(ChatFlirtMode.class));
            // A never-committed mutation must never push cached state to either client.
            verify(messagingTemplate, never())
                    .convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void pushDeliveryFailureShouldNotFailTheToggle() {
            // WS delivery is best-effort/fail-open: a broker blip must not roll back a committed toggle.
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any())).thenReturn(Optional.of(row(false, false)));
            doThrow(new RuntimeException("stomp down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            FlirtModeResponse res = service.enable(lowUser, CHAT_UUID);

            assertThat(res.isMyEnabled()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  getOrCreateRow / createRowInNewTx — lazy-create race handling
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getOrCreateRow — race handling")
    class LazyCreateRace {

        @Test
        void shouldRecoverWinnersRowWhenCreateLosesUniqueConstraintRace() {
            // The lazy INSERT loses a unique-constraint race; getOrCreateRow swallows the violation
            // and re-reads the winner's row rather than propagating a 500.
            Chat chat = privateChat(lowUser, highUser);
            ChatFlirtMode winner = row(false, false);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any()))
                    .thenReturn(Optional.empty(), Optional.of(winner));
            when(chatRepository.getReferenceById(CHAT_PK)).thenReturn(chat);
            when(flirtModeRepository.save(any(ChatFlirtMode.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.applyConsentTx(lowUser, CHAT_UUID, true);

            // Consent landed on the recovered winner row, not a duplicate insert.
            assertThat(winner.isEnabledByLow()).isTrue();
            verify(chatRepository).getReferenceById(CHAT_PK);
        }

        @Test
        void shouldRethrowViolationWhenRowStillMissingAfterRace() {
            // Constraint violation but the row genuinely isn't there on re-read → surface it.
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(any()))
                    .thenReturn(Optional.empty(), Optional.empty());
            when(chatRepository.getReferenceById(CHAT_PK)).thenReturn(chat);
            when(flirtModeRepository.save(any(ChatFlirtMode.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> service.applyConsentTx(lowUser, CHAT_UUID, true))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void createRowInNewTxShouldBuildFreshRowKeyedLowHighAllDisabled() {
            Chat ref = privateChat(lowUser, highUser);
            when(chatRepository.getReferenceById(CHAT_PK)).thenReturn(ref);

            ChatFlirtMode created = service.createRowInNewTx(CHAT_PK, LOW_ID, HIGH_ID);

            assertThat(created.getLowUserId()).isEqualTo(LOW_ID);
            assertThat(created.getHighUserId()).isEqualTo(HIGH_ID);
            assertThat(created.isEnabledByLow()).isFalse();
            assertThat(created.isEnabledByHigh()).isFalse();
            assertThat(created.isActive()).isFalse();
            assertThat(created.getChat()).isSameAs(ref);
            verify(flirtModeRepository).save(any(ChatFlirtMode.class));
        }
    }

    @Test
    @DisplayName("getState looks up the flirt-mode row against the resolved chat")
    void getStateQueriesRowForResolvedChat() {
        Chat chat = privateChat(lowUser, highUser);
        when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
        when(flirtModeRepository.findByChat(any())).thenReturn(Optional.empty());

        service.getState(lowUser, CHAT_UUID);

        // The row is fetched by the exact Chat instance resolved from the uuid (no re-load).
        verify(flirtModeRepository).findByChat(eq(chat));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  sendKiss — live "blow a kiss" (requires ACTIVE flirt mode)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendKiss")
    class SendKiss {

        @Test
        @DisplayName("active flirt mode → pushes a flirt_kiss envelope to the OTHER participant")
        void pushesKissWhenActive() {
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(chat)).thenReturn(Optional.of(row(true, true)));

            service.sendKiss(lowUser, CHAT_UUID);

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSendToUser(
                    eq("high_user"), eq("/queue/flirt-mode"), payload.capture());
            assertThat(payload.getValue()).isInstanceOfSatisfying(Map.class, envelope -> {
                assertThat(envelope.get("event")).isEqualTo("flirt_kiss");
                assertThat(envelope.get("payload")).isInstanceOfSatisfying(Map.class, p -> {
                    assertThat(p.get("chatId")).isEqualTo(CHAT_UUID);
                    assertThat(p.get("fromName")).isEqualTo("low_user");
                });
            });
        }

        @Test
        @DisplayName("no flirt row → BadRequest TM_834, nothing pushed")
        void rejectsWhenNoRow() {
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(chat)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendKiss(lowUser, CHAT_UUID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_834"));
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("flirt row present but NOT active → BadRequest TM_834")
        void rejectsWhenInactive() {
            Chat chat = privateChat(lowUser, highUser);
            when(chatRepository.findByUuidWithMembers(any())).thenReturn(Optional.of(chat));
            when(flirtModeRepository.findByChat(chat)).thenReturn(Optional.of(row(true, false)));

            assertThatThrownBy(() -> service.sendKiss(lowUser, CHAT_UUID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_834"));
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }
    }
}
