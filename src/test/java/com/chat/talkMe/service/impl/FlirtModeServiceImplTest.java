package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatFlirtMode;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.FlirtModeResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatFlirtModeRepository;
import com.chat.talkMe.repository.ChatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    /** A flirt-mode row keyed low=10 / high=20 with the given per-side flags (active derived). */
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
            verify(flirtModeRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            ChatFlirtMode finalRow = saved.getValue();
            assertThat(finalRow.getLowUserId()).isEqualTo(LOW_ID);
            assertThat(finalRow.getHighUserId()).isEqualTo(HIGH_ID);
            assertThat(finalRow.isEnabledByLow()).isTrue();
            assertThat(finalRow.isEnabledByHigh()).isFalse();
            assertThat(finalRow.isActive()).isFalse();
            // Create path was taken via the self-proxy.
            verify(chatRepository).getReferenceById(CHAT_PK);
            verify(self, org.mockito.Mockito.atLeastOnce()).getObject();
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
}
