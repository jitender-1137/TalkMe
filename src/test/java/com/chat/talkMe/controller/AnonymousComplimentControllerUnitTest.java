package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SendComplimentRequest;
import com.chat.talkMe.dto.response.ComplimentResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ContentModerationException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AnonymousComplimentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link AnonymousComplimentController} (feature ANON_COMPLIMENTS).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link AnonymousComplimentService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>Every route is annotated {@code @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")},
 *       enforced by Spring's method-security interceptor (AOP) which is NOT active in a standalone
 *       MockMvc setup — the entitlement gate is covered by the integration test. Here we verify
 *       request/response wiring and delegation to the service (which owns the self-send / block /
 *       IDOR / secrecy logic).</li>
 *   <li>{@code POST /compliments} takes a {@code @Valid @RequestBody SendComplimentRequest}, whose
 *       {@code recipientUuid}/{@code message} carry {@code @NotBlank} (+ {@code @Size(500)}); a
 *       {@link LocalValidatorFactoryBean} is wired so those violations surface as
 *       {@code MethodArgumentNotValidException} → {@code VE_101} (400).</li>
 *   <li>A missing/unparseable body has no dedicated handler and falls through to the catch-all
 *       {@code Exception} handler → 500 {@code TM_002} (pinned, mirrors the repo template).</li>
 *   <li>The reveal endpoints take only a {@code String} path variable (and, for reveal-response, a
 *       required {@code boolean} {@code @RequestParam accept}); a missing {@code accept} yields
 *       {@code MissingServletRequestParameterException} → catch-all 500.</li>
 * </ul>
 *
 * <p><b>Secrecy note:</b> the controller merely serialises whatever the service maps. These tests
 * additionally pin that a SENT inbox item serialises with NO sender fields, while a REVEALED item
 * exposes them — the wire-level face of the secrecy invariant the service enforces.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnonymousComplimentController (unit)")
class AnonymousComplimentControllerUnitTest {

    private static final String BASE = "/compliments";
    private static final String COMPLIMENT_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String RECIPIENT_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String VALIDATION_CODE = "VE_101";

    @Mock
    private AnonymousComplimentService complimentService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        AnonymousComplimentController controller = new AnonymousComplimentController(complimentService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder()
                .username("testuser").email("t@e.com").name("Test User")
                .isGuest(false).roles(Set.of(role))
                .build();
        testUser.setId(7L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static String sendBody(String recipientUuid, String message) {
        return "{\"recipientUuid\":\"" + recipientUuid + "\",\"message\":\"" + message + "\"}";
    }

    /** The sender's own "sent" view: recipient card shown, sender identity omitted (fromMe=true). */
    private static ComplimentResponse sentView() {
        return ComplimentResponse.builder()
                .uuid(COMPLIMENT_UUID)
                .message("You have the kindest smile")
                .status("SENT")
                .createdAt("2026-07-26T10:00:00Z")
                .fromMe(true)
                .recipientName("Recipient Name")
                .recipientUsername("recipient")
                .recipientAvatar("https://cdn/av-recipient.png")
                .build();
    }

    /** A recipient's inbox view of a still-anonymous (SENT) compliment — sender fields NULL. */
    private static ComplimentResponse inboxAnonymous() {
        return ComplimentResponse.builder()
                .uuid(COMPLIMENT_UUID)
                .message("You have the kindest smile")
                .status("SENT")
                .createdAt("2026-07-26T10:00:00Z")
                .fromMe(false)
                .build();
    }

    /** A recipient's inbox view of a REVEALED compliment — the one state that exposes the sender. */
    private static ComplimentResponse inboxRevealed() {
        return ComplimentResponse.builder()
                .uuid(COMPLIMENT_UUID)
                .message("It was me all along")
                .status("REVEALED")
                .createdAt("2026-07-26T09:00:00Z")
                .fromMe(false)
                .senderName("Admirer Name")
                .senderUsername("admirer")
                .senderAvatar("https://cdn/av-admirer.png")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /compliments  (send)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /compliments")
    class Send {

        @Test
        void shouldReturn200AndForwardSenderAndRequest() throws Exception {
            authenticate();
            when(complimentService.send(any(), any())).thenReturn(sentView());

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "You have the kindest smile")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Compliment sent"))
                    .andExpect(jsonPath("$.data.uuid").value(COMPLIMENT_UUID))
                    .andExpect(jsonPath("$.data.fromMe").value(true))
                    .andExpect(jsonPath("$.data.recipientUsername").value("recipient"))
                    // The sender's own view must never carry sender identity fields.
                    .andExpect(jsonPath("$.data.senderUsername").doesNotExist())
                    .andExpect(jsonPath("$.data.senderName").doesNotExist());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<SendComplimentRequest> req =
                    ArgumentCaptor.forClass(SendComplimentRequest.class);
            verify(complimentService).send(user.capture(), req.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(req.getValue().getRecipientUuid()).isEqualTo(RECIPIENT_UUID);
            assertThat(req.getValue().getMessage()).isEqualTo("You have the kindest smile");
            verify(complimentService, never()).inbox(any());
        }

        @Test
        void shouldForwardUnicodeEmojiAndInjectionTextVerbatim() throws Exception {
            authenticate();
            // Free-form compliment body: unicode/emoji + XSS + SQLi must pass through untouched
            // (controller does not sanitise; moderation/escaping is the service/UI's concern).
            String hostile = "Ålëx 🌟 <script>alert('x')</script> OR 1=1--";
            when(complimentService.send(any(), any())).thenReturn(sentView());

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, hostile)))
                    .andExpect(status().isOk());

            ArgumentCaptor<SendComplimentRequest> req =
                    ArgumentCaptor.forClass(SendComplimentRequest.class);
            verify(complimentService).send(eq(testUser), req.capture());
            assertThat(req.getValue().getMessage()).isEqualTo(hostile);
        }

        @Test
        void shouldReturn400ValidationWhenMessageBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(complimentService);
        }

        @Test
        void shouldReturn400ValidationWhenRecipientUuidBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody("", "hi there")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(complimentService);
        }

        @Test
        void shouldReturn400ValidationWhenMessageTooLong() throws Exception {
            authenticate();
            String tooLong = "a".repeat(501); // @Size(max = 500)
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, tooLong)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(complimentService);
        }

        @Test
        void shouldReturn400WhenSendingToSelf() throws Exception {
            authenticate();
            when(complimentService.send(any(), any()))
                    .thenThrow(new BadRequestException("You cannot send a compliment to yourself", "TM_962"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "hi")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_962"));
        }

        @Test
        void shouldReturn400WhenRecipientNotEligibleOrBlocked() throws Exception {
            authenticate();
            when(complimentService.send(any(), any()))
                    .thenThrow(new BadRequestException("You cannot send a compliment to this user", "TM_963"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "hi")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_963"));
        }

        @Test
        void shouldReturn422WhenContentModerated() throws Exception {
            authenticate();
            when(complimentService.send(any(), any()))
                    .thenThrow(new ContentModerationException(
                            "Your compliment contains content that violates our community guidelines."));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "not-nice")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.messageCode").value("TM_490"));
        }

        @Test
        void shouldReturn429WhenDailyCapReached() throws Exception {
            authenticate();
            when(complimentService.send(any(), any()))
                    .thenThrow(new TooManyRequestsException(
                            "You have reached today's compliment limit. Try again later.", "TM_965"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "hi")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.messageCode").value("TM_965"));
        }

        @Test
        void shouldReturn404WhenRecipientNotFound() throws Exception {
            authenticate();
            when(complimentService.send(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_404"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "hi")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_404"));
        }

        @Test
        void shouldReturn400WhenServiceReportsInvalidId() throws Exception {
            authenticate();
            when(complimentService.send(any(), any()))
                    .thenThrow(new BadRequestException("Invalid id", "TM_961"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody("not-a-uuid", "hi")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_961"));
        }

        @Test
        void shouldReturn403OnAccessDenied() throws Exception {
            authenticate();
            when(complimentService.send(any(), any())).thenThrow(new AccessDeniedException("nope"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "hi")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(complimentService.send(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "hi")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(complimentService);
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(complimentService);
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            // Valid body → passes @Valid → method body runs → userDetails is null → NPE → catch-all 500.
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody(RECIPIENT_UUID, "hi")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(complimentService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /compliments/inbox
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /compliments/inbox")
    class Inbox {

        @Test
        void shouldReturn200AndKeepAnonymousItemsSenderless() throws Exception {
            authenticate();
            when(complimentService.inbox(any()))
                    .thenReturn(List.of(inboxAnonymous(), inboxRevealed()));

            mockMvc.perform(get(BASE + "/inbox"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    // [0] is still SENT → the recipient must NOT learn who sent it.
                    .andExpect(jsonPath("$.data[0].status").value("SENT"))
                    .andExpect(jsonPath("$.data[0].message").value("You have the kindest smile"))
                    .andExpect(jsonPath("$.data[0].senderUsername").doesNotExist())
                    .andExpect(jsonPath("$.data[0].senderName").doesNotExist())
                    .andExpect(jsonPath("$.data[0].senderAvatar").doesNotExist())
                    // [1] is REVEALED → the one state that exposes the sender.
                    .andExpect(jsonPath("$.data[1].status").value("REVEALED"))
                    .andExpect(jsonPath("$.data[1].senderUsername").value("admirer"))
                    .andExpect(jsonPath("$.data[1].senderName").value("Admirer Name"));

            verify(complimentService).inbox(testUser);
            verify(complimentService, never()).sent(any());
        }

        @Test
        void shouldReturn200WithEmptyInbox() throws Exception {
            authenticate();
            when(complimentService.inbox(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/inbox"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(complimentService).inbox(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(complimentService.inbox(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/inbox"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(get(BASE + "/inbox"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(complimentService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /compliments/sent
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /compliments/sent")
    class Sent {

        @Test
        void shouldReturn200WithSenderOwnViewShowingRecipientNotSender() throws Exception {
            authenticate();
            when(complimentService.sent(any())).thenReturn(List.of(sentView()));

            mockMvc.perform(get(BASE + "/sent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].fromMe").value(true))
                    .andExpect(jsonPath("$.data[0].recipientUsername").value("recipient"))
                    // Even in one's own outbox the sender card stays absent (never leaked back).
                    .andExpect(jsonPath("$.data[0].senderUsername").doesNotExist());

            verify(complimentService).sent(testUser);
            verify(complimentService, never()).inbox(any());
        }

        @Test
        void shouldReturn200WithEmptySent() throws Exception {
            authenticate();
            when(complimentService.sent(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/sent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(complimentService).sent(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(complimentService.sent(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/sent"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /compliments/{uuid}/reveal-request
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /compliments/{uuid}/reveal-request")
    class RequestReveal {

        @Test
        void shouldReturn200AndForwardArgs() throws Exception {
            authenticate();
            when(complimentService.requestReveal(any(), any())).thenReturn(inboxAnonymous());

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-request"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Reveal requested"))
                    // Requesting a reveal must NOT itself expose the sender.
                    .andExpect(jsonPath("$.data.senderUsername").doesNotExist());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(complimentService).requestReveal(user.capture(), uuid.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(uuid.getValue()).isEqualTo(COMPLIMENT_UUID);
            verify(complimentService, never()).respondReveal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        void shouldReturn404WhenCallerIsNotTheRecipient() throws Exception {
            authenticate();
            // IDOR guard: a non-recipient is answered "not found" so the endpoint never confirms
            // a compliment the caller is not party to.
            when(complimentService.requestReveal(any(), any()))
                    .thenThrow(new NotFoundException("Compliment not found", "TM_966"));

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-request"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_966"));
        }

        @Test
        void shouldReturn400WhenAlreadyResolved() throws Exception {
            authenticate();
            when(complimentService.requestReveal(any(), any()))
                    .thenThrow(new BadRequestException("This compliment has already been revealed", "TM_967"));

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-request"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_967"));
        }

        @Test
        void shouldReturn400WhenServiceReportsInvalidId() throws Exception {
            authenticate();
            when(complimentService.requestReveal(any(), any()))
                    .thenThrow(new BadRequestException("Invalid id", "TM_961"));

            mockMvc.perform(post(BASE + "/not-a-uuid/reveal-request"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_961"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(complimentService.requestReveal(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-request"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-request"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(complimentService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /compliments/{uuid}/reveal-response?accept=…
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /compliments/{uuid}/reveal-response")
    class RespondReveal {

        @Test
        void shouldReturn200AndRevealWhenAcceptTrue() throws Exception {
            authenticate();
            when(complimentService.respondReveal(any(), any(), eq(true))).thenReturn(sentView());

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-response").param("accept", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Compliment revealed"));

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<Boolean> accept = ArgumentCaptor.forClass(Boolean.class);
            verify(complimentService).respondReveal(user.capture(), uuid.capture(), accept.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(uuid.getValue()).isEqualTo(COMPLIMENT_UUID);
            assertThat(accept.getValue()).isTrue();
        }

        @Test
        void shouldReturn200AndDeclineWhenAcceptFalse() throws Exception {
            authenticate();
            when(complimentService.respondReveal(any(), any(), eq(false))).thenReturn(sentView());

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-response").param("accept", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Reveal declined"));

            ArgumentCaptor<Boolean> accept = ArgumentCaptor.forClass(Boolean.class);
            verify(complimentService).respondReveal(eq(testUser), eq(COMPLIMENT_UUID), accept.capture());
            assertThat(accept.getValue()).isFalse();
        }

        @Test
        void shouldReturn404WhenCallerIsNotTheSender() throws Exception {
            authenticate();
            when(complimentService.respondReveal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenThrow(new NotFoundException("Compliment not found", "TM_966"));

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-response").param("accept", "true"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_966"));
        }

        @Test
        void shouldReturn400WhenNoPendingRequest() throws Exception {
            authenticate();
            when(complimentService.respondReveal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenThrow(new BadRequestException(
                            "There is no pending reveal request for this compliment", "TM_968"));

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-response").param("accept", "false"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_968"));
        }

        @Test
        void shouldReturn500WhenAcceptParamMissing() throws Exception {
            authenticate();
            // Required primitive @RequestParam missing → MissingServletRequestParameterException →
            // no dedicated handler → catch-all 500 (pinned).
            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-response"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(complimentService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(complimentService.respondReveal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-response").param("accept", "true"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/" + COMPLIMENT_UUID + "/reveal-response").param("accept", "true"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(complimentService);
        }
    }
}
