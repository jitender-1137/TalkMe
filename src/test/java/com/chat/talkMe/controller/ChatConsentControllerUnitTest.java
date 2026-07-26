package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStateResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ChatConsentService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link ChatConsentController} (per-chat mutual-consent handshake).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link ChatConsentService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link AuthenticationPrincipalArgumentResolver} for
 * {@code @AuthenticationPrincipal}. No tolerant Jackson mapper and no {@code Pageable} resolver are
 * needed: every endpoint takes only the {@code chatId} path variable plus the authenticated
 * principal — there is no {@code @RequestBody}, no {@code @RequestParam}, and no bean validation.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize} role gates and the security filter chain (JWT,
 * CSRF, chat-membership enforcement) are applied by Spring's method-security interceptor / filter
 * chain, which are NOT active in a standalone MockMvc setup. Those are covered by the integration
 * tests. Membership/authorization checks live in {@link ChatConsentService}, which is mocked here;
 * this test verifies request/response wiring, success codes read directly from the controller, and
 * delegation to the service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatConsentController (unit)")
class ChatConsentControllerUnitTest {

    private static final String CHAT = "chat-uuid-1";
    private static final String BASE = "/chats/" + CHAT + "/consent";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String ACCESS_DENIED_CODE = "TM_005";
    private static final String ILLEGAL_ARG_CODE = "TM_071";
    private static final String INVALID_UUID_CODE = "TM_INVALID_UUID";

    @Mock
    private ChatConsentService chatConsentService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ChatConsentController controller = new ChatConsentController(chatConsentService);

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

    /** A plausible consent snapshot for the given status, with the derived flags wired sensibly. */
    private static ConsentStateResponse state(String status) {
        return ConsentStateResponse.builder()
                .chatId(CHAT)
                .status(status)
                .canRequest("NONE".equals(status) || "DECLINED".equals(status))
                .canRevoke("GRANTED".equals(status))
                .isRequester(false)
                .awaitingMyAccept("PENDING".equals(status))
                .heldMessageCount(0L)
                .declineCount(0)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatId}/consent   (status query)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /chats/{chatId}/consent (getState)")
    class GetState {

        @Test
        void shouldReturn200WithStateAndForwardChatIdAndUser() throws Exception {
            authenticate();
            ConsentStateResponse resp = ConsentStateResponse.builder()
                    .chatId(CHAT).status("GRANTED")
                    .canRequest(false).canRevoke(true)
                    .isRequester(true).awaitingMyAccept(false)
                    .heldMessageCount(3L).declineCount(1)
                    .build();
            when(chatConsentService.getState(eq(CHAT), any())).thenReturn(resp);

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // getState uses SuccessResponseDto.success(data) -> default TM_000 / "Success".
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.chatId").value(CHAT))
                    .andExpect(jsonPath("$.data.status").value("GRANTED"))
                    .andExpect(jsonPath("$.data.canRevoke").value(true))
                    .andExpect(jsonPath("$.data.heldMessageCount").value(3))
                    .andExpect(jsonPath("$.data.declineCount").value(1));

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            verify(chatConsentService).getState(chatId.capture(), eq(testUser));
            assertThat(chatId.getValue()).isEqualTo(CHAT);
        }

        @Test
        void shouldExposeBooleanFieldsWithJacksonIsPrefixNaming() throws Exception {
            authenticate();
            // Lombok boolean field `isRequester` -> getter isRequester() -> Jackson property "requester"
            // (the leading "is" is stripped). The other booleans (canRequest/canRevoke/awaitingMyAccept)
            // keep their names because their getters are isCanRequest()/isCanRevoke()/isAwaitingMyAccept().
            ConsentStateResponse resp = ConsentStateResponse.builder()
                    .chatId(CHAT).status("PENDING")
                    .canRequest(false).canRevoke(false)
                    .isRequester(true).awaitingMyAccept(true)
                    .heldMessageCount(0L).declineCount(0)
                    .build();
            when(chatConsentService.getState(any(), any())).thenReturn(resp);

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.requester").value(true))
                    .andExpect(jsonPath("$.data.isRequester").doesNotExist())
                    .andExpect(jsonPath("$.data.awaitingMyAccept").value(true))
                    .andExpect(jsonPath("$.data.canRequest").value(false))
                    .andExpect(jsonPath("$.data.canRevoke").value(false));
        }

        @Test
        void shouldReturnPendingStateAwaitingCurrentUser() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any())).thenReturn(state("PENDING"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.awaitingMyAccept").value(true))
                    .andExpect(jsonPath("$.data.canRequest").value(false));
        }

        @Test
        void shouldReturnNoneStateAllowingRequest() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any())).thenReturn(state("NONE"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("NONE"))
                    .andExpect(jsonPath("$.data.canRequest").value(true))
                    .andExpect(jsonPath("$.data.canRevoke").value(false));
        }

        @Test
        void shouldForwardUnusualChatIdContainingXssAndSqliPayload() throws Exception {
            authenticate();
            // chatId is a free-form path variable; the controller passes it straight through to the
            // service untouched (no parsing/sanitising in the controller layer).
            // Avoid ';' and '/' in a path segment: ';' starts a servlet matrix parameter and '/'
            // adds a segment — both corrupt the captured value (URI-parsing artifacts, not controller
            // behavior). This XSS+SQLi payload uses neither.
            String weirdId = "<img src=x onerror=alert(1)>' OR '1'='1 名前😀";
            when(chatConsentService.getState(any(), any())).thenReturn(state("NONE"));

            mockMvc.perform(get("/chats/{chatId}/consent", weirdId))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            verify(chatConsentService).getState(chatId.capture(), eq(testUser));
            assertThat(chatId.getValue()).isEqualTo(weirdId);
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsIllegalArgument() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any()))
                    .thenThrow(new IllegalArgumentException("bad argument"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(ILLEGAL_ARG_CODE));
        }

        @Test
        void shouldReturn400WithInvalidUuidCodeWhenChatIdNotAUuid() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: xyz"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_UUID_CODE));
        }

        @Test
        void shouldReturn403WhenAccessDenied() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value(ACCESS_DENIED_CODE));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(chatConsentService.getState(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/consent/request
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /request (requestConsent)")
    class RequestConsent {

        @Test
        void shouldReturn200AndForwardChatIdAndUser() throws Exception {
            authenticate();
            when(chatConsentService.requestConsent(eq(CHAT), any())).thenReturn(state("PENDING"));

            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Consent requested"))
                    .andExpect(jsonPath("$.messageCode").value("TM_495"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.canRequest").value(false));

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            verify(chatConsentService).requestConsent(chatId.capture(), eq(testUser));
            assertThat(chatId.getValue()).isEqualTo(CHAT);
            // Requesting must not touch the accept branch.
            verify(chatConsentService, never()).acceptConsent(any(), any());
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(chatConsentService.requestConsent(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(chatConsentService.requestConsent(any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));

            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn409WhenRequestNotAllowed() throws Exception {
            authenticate();
            // e.g. already PENDING/GRANTED or the decline cap has been reached.
            when(chatConsentService.requestConsent(any(), any()))
                    .thenThrow(new ServiceException(409, "Consent request not allowed", "TM_498"));

            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_498"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(chatConsentService.requestConsent(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/request"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/consent/accept   (grant direction)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /accept (acceptConsent)")
    class AcceptConsent {

        @Test
        void shouldReturn200AndGrantConsent() throws Exception {
            authenticate();
            when(chatConsentService.acceptConsent(eq(CHAT), any())).thenReturn(state("GRANTED"));

            mockMvc.perform(post(BASE + "/accept"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Consent granted"))
                    .andExpect(jsonPath("$.messageCode").value("TM_496"))
                    .andExpect(jsonPath("$.data.status").value("GRANTED"))
                    .andExpect(jsonPath("$.data.canRevoke").value(true));

            verify(chatConsentService).acceptConsent(CHAT, testUser);
            // Accept (grant) must not trigger the decline branch.
            verify(chatConsentService, never()).declineConsent(any(), any());
        }

        @Test
        void shouldReturn403WhenCurrentUserIsNotAwaitingParty() throws Exception {
            authenticate();
            when(chatConsentService.acceptConsent(any(), any()))
                    .thenThrow(new ForbiddenException("Cannot accept your own request", "TM_141"));

            mockMvc.perform(post(BASE + "/accept"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn400WhenNoPendingRequest() throws Exception {
            authenticate();
            when(chatConsentService.acceptConsent(any(), any()))
                    .thenThrow(new ServiceException(400, "No pending consent request", "TM_498"));

            mockMvc.perform(post(BASE + "/accept"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_498"));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(chatConsentService.acceptConsent(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(post(BASE + "/accept"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(chatConsentService.acceptConsent(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/accept"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/consent/decline
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /decline (declineConsent)")
    class DeclineConsent {

        @Test
        void shouldReturn200AndDeclineConsent() throws Exception {
            authenticate();
            ConsentStateResponse resp = ConsentStateResponse.builder()
                    .chatId(CHAT).status("DECLINED")
                    .canRequest(true).canRevoke(false)
                    .isRequester(false).awaitingMyAccept(false)
                    .heldMessageCount(0L).declineCount(1)
                    .build();
            when(chatConsentService.declineConsent(eq(CHAT), any())).thenReturn(resp);

            mockMvc.perform(post(BASE + "/decline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Consent declined"))
                    .andExpect(jsonPath("$.messageCode").value("TM_497"))
                    .andExpect(jsonPath("$.data.status").value("DECLINED"))
                    .andExpect(jsonPath("$.data.declineCount").value(1));

            verify(chatConsentService).declineConsent(CHAT, testUser);
            verify(chatConsentService, never()).acceptConsent(any(), any());
        }

        @Test
        void shouldReturn403WhenCurrentUserIsNotAwaitingParty() throws Exception {
            authenticate();
            when(chatConsentService.declineConsent(any(), any()))
                    .thenThrow(new ForbiddenException("Cannot decline your own request", "TM_141"));

            mockMvc.perform(post(BASE + "/decline"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn409WhenDeclineCapReached() throws Exception {
            authenticate();
            when(chatConsentService.declineConsent(any(), any()))
                    .thenThrow(new ServiceException(409, "No pending request to decline", "TM_498"));

            mockMvc.perform(post(BASE + "/decline"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_498"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(chatConsentService.declineConsent(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/decline"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/consent/revoke   (turn-off direction)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /revoke (revokeConsent)")
    class RevokeConsent {

        @Test
        void shouldReturn200AndTurnConsentOff() throws Exception {
            authenticate();
            when(chatConsentService.revokeConsent(eq(CHAT), any())).thenReturn(state("NONE"));

            mockMvc.perform(post(BASE + "/revoke"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Consent turned off"))
                    .andExpect(jsonPath("$.messageCode").value("TM_499"))
                    .andExpect(jsonPath("$.data.status").value("NONE"))
                    .andExpect(jsonPath("$.data.canRequest").value(true))
                    .andExpect(jsonPath("$.data.canRevoke").value(false));

            verify(chatConsentService).revokeConsent(CHAT, testUser);
            // Revoke (turn off) is the opposite of accept (grant) — the grant branch must not run.
            verify(chatConsentService, never()).acceptConsent(any(), any());
        }

        @Test
        void shouldReturn400WhenConsentNotGranted() throws Exception {
            authenticate();
            when(chatConsentService.revokeConsent(any(), any()))
                    .thenThrow(new ServiceException(400, "Consent is not currently granted", "TM_498"));

            mockMvc.perform(post(BASE + "/revoke"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_498"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(chatConsentService.revokeConsent(any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));

            mockMvc.perform(post(BASE + "/revoke"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(chatConsentService.revokeConsent(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(post(BASE + "/revoke"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(chatConsentService.revokeConsent(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/revoke"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
