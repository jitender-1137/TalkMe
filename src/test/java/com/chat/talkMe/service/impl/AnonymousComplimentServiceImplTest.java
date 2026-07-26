package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.AnonymousCompliment;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SendComplimentRequest;
import com.chat.talkMe.dto.response.ComplimentResponse;
import com.chat.talkMe.enums.ComplimentStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.moderation.ContentModerationService;
import com.chat.talkMe.moderation.ModerationResult;
import com.chat.talkMe.repository.AnonymousComplimentRepository;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link AnonymousComplimentServiceImpl} (feature ANON_COMPLIMENTS).
 *
 * <p>No Spring context, no DB, no Redis — every collaborator is mocked. The focus is the
 * <b>secrecy invariant</b>: a compliment's sender identity is exposed to the recipient at exactly
 * one transition (the sender accepting a reveal), and NOWHERE else. These tests pin that:
 * <ul>
 *   <li>{@code inbox()} strips the sender for every status except {@code REVEALED};</li>
 *   <li>the sender's own {@code sent()} view never carries sender identity (it shows the recipient);</li>
 *   <li>reveal-request is IDOR-locked to the recipient and reveal-response to the sender;</li>
 *   <li>accept → {@code REVEALED} + sender exposed; decline → {@code DECLINED} + sender still hidden;</li>
 *   <li>the WS push payload obeys the same rule at every transition.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnonymousComplimentServiceImpl (unit)")
class AnonymousComplimentServiceImplTest {

    private static final String RECIPIENT_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String COMPLIMENT_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String QUEUE = "/queue/compliments";

    @Mock
    private AnonymousComplimentRepository complimentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private AnonymousComplimentServiceImpl service;

    private User sender;
    private User recipient;

    @BeforeEach
    void setUp() {
        sender = buildUser(1L, "sender", "Sender Name", "https://cdn/av-sender.png");
        recipient = buildUser(2L, "recipient", "Recipient Name", "https://cdn/av-recipient.png");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static User buildUser(long id, String username, String name, String avatar) {
        User u = User.builder()
                .username(username).name(name).email(username + "@e.com")
                .profileImage(avatar).isGuest(false).banned(false)
                .build();
        u.setId(id);
        return u;
    }

    private static SendComplimentRequest sendRequest(String recipientUuid, String message) {
        return SendComplimentRequest.builder()
                .recipientUuid(recipientUuid).message(message).build();
    }

    /** A persisted-looking compliment (from `s` to `r`) in the given status. */
    private static AnonymousCompliment compliment(User s, User r, ComplimentStatus status) {
        AnonymousCompliment c = AnonymousCompliment.builder()
                .sender(s).recipient(r).message("You have the kindest smile").status(status)
                .build();
        c.setUuid(UUID.fromString(COMPLIMENT_UUID));
        c.setCreatedAt(Instant.parse("2026-07-26T10:00:00Z"));
        return c;
    }

    /** Extract the {@link ComplimentResponse} carried in the single WS push to {@code username}. */
    private ComplimentResponse capturePushPayload(String username, String expectedEvent) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq(username), eq(QUEUE), payload.capture());
        Map<?, ?> envelope = (Map<?, ?>) payload.getValue();
        assertThat(envelope.get("event")).isEqualTo(expectedEvent);
        return (ComplimentResponse) envelope.get("payload");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  send()
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("send()")
    class Send {

        @Test
        void shouldPersistAndPushAnonymousInboxViewAndReturnSenderView() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());

            ComplimentResponse mine = service.send(sender, sendRequest(RECIPIENT_UUID, "You light up the room"));

            // Persisted a fresh SENT compliment addressed to the recipient.
            ArgumentCaptor<AnonymousCompliment> saved = ArgumentCaptor.forClass(AnonymousCompliment.class);
            verify(complimentRepository).save(saved.capture());
            assertThat(saved.getValue().getSender()).isSameAs(sender);
            assertThat(saved.getValue().getRecipient()).isSameAs(recipient);
            assertThat(saved.getValue().getStatus()).isEqualTo(ComplimentStatus.SENT);
            assertThat(saved.getValue().getMessage()).isEqualTo("You light up the room");

            // The recipient's pushed inbox view must NOT reveal the sender (status == SENT).
            ComplimentResponse pushed = capturePushPayload("recipient", "compliment_received");
            assertThat(pushed.isFromMe()).isFalse();
            assertThat(pushed.getSenderName()).isNull();
            assertThat(pushed.getSenderUsername()).isNull();
            assertThat(pushed.getSenderAvatar()).isNull();

            // The sender's own returned view shows the recipient, never a sender card.
            assertThat(mine.isFromMe()).isTrue();
            assertThat(mine.getRecipientUsername()).isEqualTo("recipient");
            assertThat(mine.getSenderUsername()).isNull();
            assertThat(mine.getStatus()).isEqualTo("SENT");
        }

        @Test
        void shouldTrimMessageBeforePersisting() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());

            service.send(sender, sendRequest(RECIPIENT_UUID, "   spaced out   "));

            ArgumentCaptor<AnonymousCompliment> saved = ArgumentCaptor.forClass(AnonymousCompliment.class);
            verify(complimentRepository).save(saved.capture());
            assertThat(saved.getValue().getMessage()).isEqualTo("spaced out");
        }

        @Test
        void shouldSucceedEvenWhenWsPushFailsOpen() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            ComplimentResponse mine = service.send(sender, sendRequest(RECIPIENT_UUID, "still delivered"));

            assertThat(mine).isNotNull();
            verify(complimentRepository).save(any());
        }

        @Test
        void shouldRejectSendingToSelf() {
            User self = buildUser(1L, "sender", "Sender Name", "https://cdn/av-sender.png");
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(self));

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "hi me")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_962"));

            verify(complimentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectSendingToGuest() {
            User guest = User.builder()
                    .username("guest").name("Guest").email("guest@e.com").isGuest(true).build();
            guest.setId(2L);
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "hi")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_963"));

            verify(complimentRepository, never()).save(any());
        }

        @Test
        void shouldRejectSendingToBannedUser() {
            User banned = User.builder()
                    .username("banned").name("Banned").email("banned@e.com").banned(true).build();
            banned.setId(2L);
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(banned));

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "hi")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_963"));

            verify(complimentRepository, never()).save(any());
        }

        @Test
        void shouldRejectWhenSenderHasBlockedRecipient() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));
            when(blockUserRepository.existsByUserAndBlocked(sender, recipient)).thenReturn(true);

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "hi")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_963"));

            verify(complimentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectWhenRecipientHasBlockedSender() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));
            // Forward direction is clear; the reverse (recipient blocked sender) must still gate.
            when(blockUserRepository.existsByUserAndBlocked(sender, recipient)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(recipient, sender)).thenReturn(true);

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "hi")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_963"));

            verify(complimentRepository, never()).save(any());
        }

        @Test
        void shouldRejectBlankMessageAfterTrim() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "     ")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_964"));

            verify(complimentRepository, never()).save(any());
        }

        @Test
        void shouldRejectExplicitContent() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));
            when(moderationService.moderateText(anyString()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.SEXUAL, 0.99, List.of("x")));

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "explicit text")))
                    .isInstanceOf(ContentModerationException.class);

            verify(complimentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectWhenDailyCapReached() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(recipient));
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(complimentRepository.countBySenderAndCreatedAtAfter(eq(sender), any())).thenReturn(10L);

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "one too many")))
                    .isInstanceOfSatisfying(TooManyRequestsException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_965"));

            verify(complimentRepository, never()).save(any());
        }

        @Test
        void shouldThrowNotFoundWhenRecipientMissing() {
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.send(sender, sendRequest(RECIPIENT_UUID, "hi")))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_404"));
        }

        @Test
        void shouldThrowInvalidIdForMalformedRecipientUuid() {
            assertThatThrownBy(() -> service.send(sender, sendRequest("not-a-uuid", "hi")))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_961"));

            verify(userRepository, never()).findByUuid(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  inbox()  — the core secrecy surface
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("inbox()")
    class Inbox {

        @Test
        void shouldStripSenderIdentityForSentCompliment() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.SENT);
            when(complimentRepository.findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc(recipient))
                    .thenReturn(List.of(c));

            List<ComplimentResponse> inbox = service.inbox(recipient);

            assertThat(inbox).hasSize(1);
            ComplimentResponse r = inbox.get(0);
            // SECRECY: recipient sees the message + status but NOT who sent it.
            assertThat(r.getMessage()).isEqualTo("You have the kindest smile");
            assertThat(r.getStatus()).isEqualTo("SENT");
            assertThat(r.isFromMe()).isFalse();
            assertThat(r.getSenderName()).isNull();
            assertThat(r.getSenderUsername()).isNull();
            assertThat(r.getSenderAvatar()).isNull();
            // Inbox view is not the sender's, so recipient card is absent too.
            assertThat(r.getRecipientUsername()).isNull();
        }

        @Test
        void shouldStripSenderIdentityWhileRevealPending() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEAL_REQUESTED);
            when(complimentRepository.findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc(recipient))
                    .thenReturn(List.of(c));

            ComplimentResponse r = service.inbox(recipient).get(0);

            // A pending reveal has NOT yet exposed the sender.
            assertThat(r.getStatus()).isEqualTo("REVEAL_REQUESTED");
            assertThat(r.getSenderUsername()).isNull();
            assertThat(r.getSenderName()).isNull();
            assertThat(r.getSenderAvatar()).isNull();
        }

        @Test
        void shouldStripSenderIdentityWhenDeclined() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.DECLINED);
            when(complimentRepository.findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc(recipient))
                    .thenReturn(List.of(c));

            ComplimentResponse r = service.inbox(recipient).get(0);

            // Declined stays anonymous forever.
            assertThat(r.getStatus()).isEqualTo("DECLINED");
            assertThat(r.getSenderUsername()).isNull();
            assertThat(r.getSenderName()).isNull();
            assertThat(r.getSenderAvatar()).isNull();
        }

        @Test
        void shouldExposeSenderOnlyWhenRevealed() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEALED);
            when(complimentRepository.findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc(recipient))
                    .thenReturn(List.of(c));

            ComplimentResponse r = service.inbox(recipient).get(0);

            // The single state that discloses the sender.
            assertThat(r.getStatus()).isEqualTo("REVEALED");
            assertThat(r.getSenderName()).isEqualTo("Sender Name");
            assertThat(r.getSenderUsername()).isEqualTo("sender");
            assertThat(r.getSenderAvatar()).isEqualTo("https://cdn/av-sender.png");
        }

        @Test
        void shouldReturnEmptyListWhenNoCompliments() {
            when(complimentRepository.findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc(recipient))
                    .thenReturn(List.of());

            assertThat(service.inbox(recipient)).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  sent()  — sender's own outbox (recipient shown, sender never)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sent()")
    class SentView {

        @Test
        void shouldShowRecipientAndNeverSenderEvenWhenRevealed() {
            // Even a REVEALED row must not echo the sender back into the sender's own outbox.
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEALED);
            when(complimentRepository.findBySenderAndIsDeletedFalseOrderByCreatedAtDesc(sender))
                    .thenReturn(List.of(c));

            List<ComplimentResponse> sent = service.sent(sender);

            assertThat(sent).hasSize(1);
            ComplimentResponse r = sent.get(0);
            assertThat(r.isFromMe()).isTrue();
            assertThat(r.getRecipientName()).isEqualTo("Recipient Name");
            assertThat(r.getRecipientUsername()).isEqualTo("recipient");
            assertThat(r.getRecipientAvatar()).isEqualTo("https://cdn/av-recipient.png");
            assertThat(r.getSenderUsername()).isNull();
            assertThat(r.getSenderName()).isNull();
        }

        @Test
        void shouldReturnEmptyListWhenNothingSent() {
            when(complimentRepository.findBySenderAndIsDeletedFalseOrderByCreatedAtDesc(sender))
                    .thenReturn(List.of());

            assertThat(service.sent(sender)).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  requestReveal()  — recipient-only (IDOR-guarded)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("requestReveal()")
    class RequestReveal {

        @Test
        void shouldMoveToRevealRequestedAndNotifySenderWithoutExposingSender() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.SENT);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            ComplimentResponse mine = service.requestReveal(recipient, COMPLIMENT_UUID);

            assertThat(c.getStatus()).isEqualTo(ComplimentStatus.REVEAL_REQUESTED);
            verify(complimentRepository).save(c);

            // The SENDER is notified; the payload is the sender's own view (recipient shown, sender
            // absent) — a reveal request must not leak the sender to anyone.
            ComplimentResponse pushed = capturePushPayload("sender", "compliment_reveal_requested");
            assertThat(pushed.isFromMe()).isTrue();
            assertThat(pushed.getRecipientUsername()).isEqualTo("recipient");
            assertThat(pushed.getSenderUsername()).isNull();

            // The recipient's own returned view still hides the sender.
            assertThat(mine.isFromMe()).isFalse();
            assertThat(mine.getSenderUsername()).isNull();
        }

        @Test
        void shouldBeIdempotentWhenAlreadyRequested() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEAL_REQUESTED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            ComplimentResponse mine = service.requestReveal(recipient, COMPLIMENT_UUID);

            assertThat(c.getStatus()).isEqualTo(ComplimentStatus.REVEAL_REQUESTED);
            assertThat(mine.getSenderUsername()).isNull();
            // No second notification, no redundant write.
            verify(complimentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectNonRecipientAsNotFound() {
            User stranger = buildUser(99L, "stranger", "Stranger", null);
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.SENT);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            // IDOR: a caller who is neither party is told "not found" — the endpoint must not even
            // confirm the compliment exists.
            assertThatThrownBy(() -> service.requestReveal(stranger, COMPLIMENT_UUID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_966"));

            assertThat(c.getStatus()).isEqualTo(ComplimentStatus.SENT);
            verify(complimentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectTheSenderRequestingReveal() {
            // The sender is not the recipient, so even they are treated as "not found" here.
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.SENT);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.requestReveal(sender, COMPLIMENT_UUID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_966"));

            verify(complimentRepository, never()).save(any());
        }

        @Test
        void shouldRejectWhenAlreadyRevealed() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEALED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.requestReveal(recipient, COMPLIMENT_UUID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_967"));

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectWhenAlreadyDeclined() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.DECLINED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.requestReveal(recipient, COMPLIMENT_UUID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_967"));
        }

        @Test
        void shouldThrowNotFoundWhenComplimentMissing() {
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestReveal(recipient, COMPLIMENT_UUID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_966"));
        }

        @Test
        void shouldThrowInvalidIdForMalformedUuid() {
            assertThatThrownBy(() -> service.requestReveal(recipient, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_961"));

            verify(complimentRepository, never()).findByUuid(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  respondReveal()  — sender-only (IDOR-guarded); the one exposure point
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("respondReveal()")
    class RespondReveal {

        @Test
        void shouldRevealSenderToRecipientWhenAccepted() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEAL_REQUESTED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            ComplimentResponse mine = service.respondReveal(sender, COMPLIMENT_UUID, true);

            assertThat(c.getStatus()).isEqualTo(ComplimentStatus.REVEALED);
            assertThat(c.getRevealedAt()).isNotNull();
            verify(complimentRepository).save(c);

            // The RECIPIENT is pushed a view that now DOES expose the sender — the single point of
            // disclosure in the whole feature.
            ComplimentResponse pushed = capturePushPayload("recipient", "compliment_revealed");
            assertThat(pushed.isFromMe()).isFalse();
            assertThat(pushed.getStatus()).isEqualTo("REVEALED");
            assertThat(pushed.getSenderUsername()).isEqualTo("sender");
            assertThat(pushed.getSenderName()).isEqualTo("Sender Name");

            // The sender's own returned view stays a "sent" view (recipient shown, no sender card).
            assertThat(mine.isFromMe()).isTrue();
            assertThat(mine.getRecipientUsername()).isEqualTo("recipient");
            assertThat(mine.getSenderUsername()).isNull();
        }

        @Test
        void shouldStayAnonymousWhenDeclined() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEAL_REQUESTED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            ComplimentResponse mine = service.respondReveal(sender, COMPLIMENT_UUID, false);

            assertThat(c.getStatus()).isEqualTo(ComplimentStatus.DECLINED);
            assertThat(c.getRevealedAt()).isNull();
            verify(complimentRepository).save(c);

            // The recipient is told it stays anonymous — the pushed payload must NOT carry the sender.
            ComplimentResponse pushed = capturePushPayload("recipient", "compliment_declined");
            assertThat(pushed.getStatus()).isEqualTo("DECLINED");
            assertThat(pushed.getSenderUsername()).isNull();
            assertThat(pushed.getSenderName()).isNull();
            assertThat(pushed.getSenderAvatar()).isNull();

            assertThat(mine.isFromMe()).isTrue();
        }

        @Test
        void shouldRejectNonSenderAsNotFound() {
            User stranger = buildUser(99L, "stranger", "Stranger", null);
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEAL_REQUESTED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.respondReveal(stranger, COMPLIMENT_UUID, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_966"));

            assertThat(c.getStatus()).isEqualTo(ComplimentStatus.REVEAL_REQUESTED);
            verify(complimentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectTheRecipientAnsweringOwnReveal() {
            // Only the sender may answer; the recipient (who asked) is treated as not-found.
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEAL_REQUESTED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.respondReveal(recipient, COMPLIMENT_UUID, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_966"));

            verify(complimentRepository, never()).save(any());
        }

        @Test
        void shouldRejectWhenNoPendingRequestBecauseStillSent() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.SENT);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.respondReveal(sender, COMPLIMENT_UUID, true))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_968"));

            verify(complimentRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        void shouldRejectWhenAlreadyRevealed() {
            AnonymousCompliment c = compliment(sender, recipient, ComplimentStatus.REVEALED);
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.respondReveal(sender, COMPLIMENT_UUID, false))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_968"));
        }

        @Test
        void shouldThrowNotFoundWhenComplimentMissing() {
            when(complimentRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.respondReveal(sender, COMPLIMENT_UUID, true))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_966"));
        }

        @Test
        void shouldThrowInvalidIdForMalformedUuid() {
            assertThatThrownBy(() -> service.respondReveal(sender, "not-a-uuid", true))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getMessageCode()).isEqualTo("TM_961"));

            verify(complimentRepository, never()).findByUuid(any());
        }
    }
}
