package com.neo.chat.service;

import com.neo.chat.crypto.MessageCryptoService;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.Message;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSetting;
import com.neo.chat.dto.EmailUnreadPreview;
import com.neo.chat.repository.MessageRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link UnreadDigestService} — the daily "you have unread
 * messages" digest job.
 *
 * <p>Covers the scheduler checklist: nothing-to-do no-op (disabled / no candidates), the
 * per-user eligibility branches (missing user, no email, opted-out, no unread, watermark
 * already caught up, zero total), the happy-path send with watermark advance + decrypted
 * previews, and partial-failure isolation (one bad user never aborts the batch).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnreadDigestService (unit)")
class UnreadDigestServiceTest {

    private static final long USER_ID = 7L;
    private static final long CHAT_ID = 99L;

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private MessageCryptoService messageCryptoService;
    @Captor
    private ArgumentCaptor<List<EmailUnreadPreview>> previewsCaptor;

    private UnreadDigestService service;

    @BeforeEach
    void setUp() {
        service = new UnreadDigestService(messageRepository, userRepository, userSettingRepository,
                emailService, messageCryptoService);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxPreviews", 5);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:3000");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static User user(long id, String email, String name, Long watermark) {
        User u = User.builder().email(email).name(name).build();
        u.setId(id);
        u.setLastUnreadDigestMessageId(watermark);
        return u;
    }

    private Message message(long id, User sender, String content, Instant createdAt) {
        Message m = Message.builder().build();
        m.setId(id);
        m.setSender(sender);
        m.setContent(content);
        Chat chat = mock(Chat.class);
        lenient().when(chat.getId()).thenReturn(CHAT_ID);
        m.setChat(chat);
        m.setCreatedAt(createdAt);
        return m;
    }

    /**
     * Wires the full eligible-user happy path with a single unread from {@code Bob}.
     */
    private User wireEligibleUser(Long watermark, long newestId) {
        User u = user(USER_ID, "jane@example.com", "Jane", watermark);
        User bob = User.builder().name("Bob").build();
        bob.setId(500L);
        Message msg = message(newestId, bob, "cipher", Instant.now().minusSeconds(120));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userSettingRepository.findByUser(u)).thenReturn(Optional.empty()); // defaults to allowed
        when(messageRepository.findRecentUnreadForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(List.of(msg));
        return u;
    }

    // ── batch-level no-ops ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendDailyUnreadDigests — batch gates")
    class BatchGates {

        @Test
        @DisplayName("disabled flag → returns immediately, never queries candidates")
        void disabledNoop() {
            ReflectionTestUtils.setField(service, "enabled", false);

            service.sendDailyUnreadDigests();

            verify(messageRepository, never()).findUserIdsWithNewUnread();
            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("no candidate users → no email sent")
        void noCandidatesNoop() {
            when(messageRepository.findUserIdsWithNewUnread()).thenReturn(List.of());

            service.sendDailyUnreadDigests();

            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
            verify(userRepository, never()).findById(anyLong());
        }
    }

    // ── per-user eligibility skips ───────────────────────────────────────────────

    @Nested
    @DisplayName("sendForUser — eligibility skips (no email, no save)")
    class EligibilitySkips {

        @BeforeEach
        void oneCandidate() {
            when(messageRepository.findUserIdsWithNewUnread()).thenReturn(List.of(USER_ID));
        }

        @Test
        @DisplayName("user not found → skipped")
        void userNotFound() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            service.sendDailyUnreadDigests();

            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("user has blank email → skipped")
        void blankEmail() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, "  ", "Jane", null)));

            service.sendDailyUnreadDigests();

            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("user opted out of unread-digest emails → skipped")
        void optedOut() {
            User u = user(USER_ID, "jane@example.com", "Jane", null);
            UserSetting settings = mock(UserSetting.class);
            when(settings.isEmailUnreadMessages()).thenReturn(false);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.of(settings));

            service.sendDailyUnreadDigests();

            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
            verify(messageRepository, never()).findRecentUnreadForUser(anyLong(), any());
        }

        @Test
        @DisplayName("no recent unread rows → skipped")
        void noRecentUnread() {
            User u = user(USER_ID, "jane@example.com", "Jane", null);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.empty());
            when(messageRepository.findRecentUnreadForUser(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(List.of());

            service.sendDailyUnreadDigests();

            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
        }

        /**
         * The user's digest watermark is pre-set equal to the newest unread id (40), so the
         * newest-&lt;=-lastNotified guard fires: no email, no watermark save, and the total-unread
         * count is never even queried.
         */
        @Test
        @DisplayName("newest unread id already at/below the watermark → not re-notified")
        void alreadyNotified() {
            User bob = User.builder().name("Bob").build();
            bob.setId(500L);
            wireEligibleUserSkipCount(bob, 40L);            // newest id 40
            // watermark already 40 → newest <= lastNotified → skip.

            service.sendDailyUnreadDigests();

            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
            verify(userRepository, never()).save(any());
            verify(messageRepository, never()).countTotalUnreadForUser(anyLong());
        }

        @Test
        @DisplayName("total unread computes to zero → skipped")
        void zeroTotalUnread() {
            User u = wireEligibleUser(0L, 50L);
            when(messageRepository.countTotalUnreadForUser(USER_ID)).thenReturn(0L);

            service.sendDailyUnreadDigests();

            verify(emailService, never()).sendUnreadMessagesEmail(any(), any(), any(), anyInt(), any());
            verify(userRepository, never()).save(u);
        }

        private void wireEligibleUserSkipCount(User sender, long newestId) {
            User u = user(USER_ID, "jane@example.com", "Jane", newestId); // watermark == newestId
            Message msg = message(newestId, sender, "cipher", Instant.now());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.empty());
            when(messageRepository.findRecentUnreadForUser(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(List.of(msg));
        }
    }

    // ── happy-path send ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendForUser — digest sent")
    class DigestSent {

        @BeforeEach
        void oneCandidate() {
            when(messageRepository.findUserIdsWithNewUnread()).thenReturn(List.of(USER_ID));
        }

        @Test
        @DisplayName("new unread past the watermark → email sent, watermark advanced")
        void sendsAndAdvancesWatermark() {
            wireEligibleUser(0L, 50L);
            when(messageRepository.countTotalUnreadForUser(USER_ID)).thenReturn(3L);
            when(messageCryptoService.decrypt(CHAT_ID, "cipher")).thenReturn("hey there");

            service.sendDailyUnreadDigests();

            verify(emailService).sendUnreadMessagesEmail(
                    eq("jane@example.com"), eq("Jane"), previewsCaptor.capture(), eq(3), eq("http://localhost:3000/"));
            assertThat(previewsCaptor.getValue()).singleElement()
                    .satisfies(p -> {
                        assertThat(p.senderName()).isEqualTo("Bob");
                        assertThat(p.snippet()).isEqualTo("hey there");
                    });

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getLastUnreadDigestMessageId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("open link strips a trailing slash from the configured base url")
        void normalisesOpenLink() {
            ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:3000///");
            wireEligibleUser(0L, 50L);
            when(messageRepository.countTotalUnreadForUser(USER_ID)).thenReturn(1L);
            when(messageCryptoService.decrypt(anyLong(), any())).thenReturn("hi");

            service.sendDailyUnreadDigests();

            verify(emailService).sendUnreadMessagesEmail(
                    any(), any(), any(), anyInt(), eq("http://localhost:3000/"));
        }

        @Test
        @DisplayName("blank/undecryptable content falls back to a generic snippet")
        void blankSnippetFallback() {
            wireEligibleUser(0L, 50L);
            when(messageRepository.countTotalUnreadForUser(USER_ID)).thenReturn(1L);
            when(messageCryptoService.decrypt(CHAT_ID, "cipher")).thenReturn("   ");

            service.sendDailyUnreadDigests();

            verify(emailService).sendUnreadMessagesEmail(any(), any(), previewsCaptor.capture(), anyInt(), any());
            assertThat(previewsCaptor.getValue().getFirst().snippet()).isEqualTo("Sent you a message");
        }

        @Test
        @DisplayName("multiple messages from one sender collapse to a single preview row")
        void dedupesBySender() {
            User u = user(USER_ID, "jane@example.com", "Jane", 0L);
            User bob = User.builder().name("Bob").build();
            bob.setId(500L);
            Message newer = message(50L, bob, "cipher", Instant.now());
            Message older = message(49L, bob, "cipher", Instant.now().minusSeconds(60));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.empty());
            when(messageRepository.findRecentUnreadForUser(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(List.of(newer, older));
            when(messageRepository.countTotalUnreadForUser(USER_ID)).thenReturn(2L);
            when(messageCryptoService.decrypt(anyLong(), any())).thenReturn("hi");

            service.sendDailyUnreadDigests();

            verify(emailService).sendUnreadMessagesEmail(any(), any(), previewsCaptor.capture(), anyInt(), any());
            assertThat(previewsCaptor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("preview list is capped at maxPreviews distinct senders")
        void capsPreviews() {
            ReflectionTestUtils.setField(service, "maxPreviews", 2);
            User u = user(USER_ID, "jane@example.com", "Jane", 0L);
            Message m1 = message(50L, senderNamed(501L, "A"), "cipher", Instant.now());
            Message m2 = message(49L, senderNamed(502L, "B"), "cipher", Instant.now());
            Message m3 = message(48L, senderNamed(503L, "C"), "cipher", Instant.now());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
            when(userSettingRepository.findByUser(u)).thenReturn(Optional.empty());
            when(messageRepository.findRecentUnreadForUser(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(List.of(m1, m2, m3));
            when(messageRepository.countTotalUnreadForUser(USER_ID)).thenReturn(3L);
            when(messageCryptoService.decrypt(anyLong(), any())).thenReturn("hi");

            service.sendDailyUnreadDigests();

            verify(emailService).sendUnreadMessagesEmail(any(), any(), previewsCaptor.capture(), anyInt(), any());
            assertThat(previewsCaptor.getValue()).hasSize(2);
        }

        private User senderNamed(long id, String name) {
            User s = User.builder().name(name).build();
            s.setId(id);
            return s;
        }
    }

    // ── batch isolation ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendDailyUnreadDigests — partial-failure isolation")
    class Isolation {

        @Test
        @DisplayName("one user throwing does not abort the batch — the next still gets a digest")
        void oneFailureDoesNotAbortBatch() {
            long badId = 1L;
            long goodId = USER_ID;
            when(messageRepository.findUserIdsWithNewUnread()).thenReturn(List.of(badId, goodId));
            when(userRepository.findById(badId)).thenThrow(new RuntimeException("db blip"));

            // Good user wiring.
            User good = user(goodId, "jane@example.com", "Jane", 0L);
            User bob = User.builder().name("Bob").build();
            bob.setId(500L);
            Message msg = message(50L, bob, "cipher", Instant.now());
            when(userRepository.findById(goodId)).thenReturn(Optional.of(good));
            when(userSettingRepository.findByUser(good)).thenReturn(Optional.empty());
            when(messageRepository.findRecentUnreadForUser(eq(goodId), any(Pageable.class)))
                    .thenReturn(List.of(msg));
            when(messageRepository.countTotalUnreadForUser(goodId)).thenReturn(1L);
            when(messageCryptoService.decrypt(anyLong(), any())).thenReturn("hi");

            assertThatCode(() -> service.sendDailyUnreadDigests()).doesNotThrowAnyException();

            verify(emailService).sendUnreadMessagesEmail(eq("jane@example.com"), any(), any(), eq(1), any());
        }
    }
}
