package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.EndorseBadgeRequest;
import com.chat.talkMe.dto.response.BadgeResponse;
import com.chat.talkMe.enums.BadgeType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.BadgeService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link BadgeController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link BadgeService} and the real
 * {@link GlobalExceptionHandler}. Registers only {@link AuthenticationPrincipalArgumentResolver}
 * for {@code @AuthenticationPrincipal} — the controller has no {@code Pageable} param, so no
 * {@code PageableHandlerMethodArgumentResolver} is needed, and {@link EndorseBadgeRequest} has no
 * unboxed primitive field, so the tolerant Jackson mapper (see {@code MessageControllerUnitTest})
 * is not needed either; the default standalone converters are used.
 *
 * <p><b>Scope boundary:</b> class-level {@code @PreAuthorize("hasRole('USER')")} and the
 * per-method {@code @PreAuthorize("@featureGuard.check('BADGES')")} feature-gate are enforced by
 * Spring Security's method-security interceptor + the filter chain, which are inactive in a
 * standalone {@code MockMvc} setup. Those (JWT auth, role check, BADGES entitlement) are covered
 * by integration tests; here we exercise controller wiring, request/response mapping, validation,
 * and exception translation only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeController (unit)")
class BadgeControllerUnitTest {

    private static final String BASE = "/reputation/badges";
    private static final String USER_UUID = "user-uuid-1";
    private static final String RECIPIENT_UUID = "recipient-uuid-2";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String SUCCESS_CODE = "TM_000";

    @Mock
    private BadgeService badgeService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        BadgeController controller = new BadgeController(badgeService);

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

    private static BadgeResponse badge(BadgeType type, int count, boolean earned) {
        return BadgeResponse.builder()
                .type(type.name())
                .label(type.getLabel())
                .awardedAt(earned ? "2026-07-22T00:00:00Z" : null)
                .endorsementCount(count)
                .earned(earned)
                .build();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /reputation/badges/{userUuid}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /{userUuid} (list badges)")
    class ListBadges {

        @Test
        void shouldReturn200WithBadgeListAndForwardUserUuid() throws Exception {
            authenticate();
            when(badgeService.listBadges(any()))
                    .thenReturn(List.of(
                            badge(BadgeType.FRIENDLY, 5, true),
                            badge(BadgeType.FUNNY, 1, false)));

            mockMvc.perform(get(BASE + "/{userUuid}", USER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // SuccessResponseDto.success(data) → message "Success", code "TM_000".
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data[0].type").value("FRIENDLY"))
                    .andExpect(jsonPath("$.data[0].label").value("Friendly"))
                    .andExpect(jsonPath("$.data[0].endorsementCount").value(5))
                    // Lombok boolean isEarned → JSON "earned".
                    .andExpect(jsonPath("$.data[0].earned").value(true))
                    .andExpect(jsonPath("$.data[0].awardedAt").value("2026-07-22T00:00:00Z"))
                    .andExpect(jsonPath("$.data[1].type").value("FUNNY"))
                    .andExpect(jsonPath("$.data[1].earned").value(false))
                    // null awardedAt for the in-progress badge.
                    .andExpect(jsonPath("$.data[1].awardedAt").doesNotExist());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(badgeService).listBadges(uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(USER_UUID);
        }

        @Test
        void shouldReturn200WithEmptyListWhenUserHasNoBadges() throws Exception {
            authenticate();
            when(badgeService.listBadges(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/{userUuid}", USER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(badgeService).listBadges(USER_UUID);
        }

        @Test
        void shouldWorkWithoutAuthenticationSinceEndpointReadsNoPrincipal() throws Exception {
            // listBadges takes no @AuthenticationPrincipal — no SecurityContext required.
            when(badgeService.listBadges(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/{userUuid}", USER_UUID))
                    .andExpect(status().isOk());

            verify(badgeService).listBadges(USER_UUID);
        }

        @Test
        void shouldForwardUnicodeUserUuidUnchanged() throws Exception {
            authenticate();
            when(badgeService.listBadges(any())).thenReturn(List.of());

            String unicode = "用户-😀-id";
            mockMvc.perform(get(BASE + "/{userUuid}", unicode))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(badgeService).listBadges(uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(unicode);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            authenticate();
            when(badgeService.listBadges(any()))
                    .thenThrow(new NotFoundException("User not found", "TM_064"));

            mockMvc.perform(get(BASE + "/{userUuid}", USER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsUuidFormat() throws Exception {
            authenticate();
            // IllegalArgumentException from the service → TM_071 / 400 (GlobalExceptionHandler).
            when(badgeService.listBadges(any()))
                    .thenThrow(new IllegalArgumentException("bad uuid"));

            mockMvc.perform(get(BASE + "/{userUuid}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn400WithInvalidUuidCodeWhenMessageMentionsInvalidUuidString() throws Exception {
            authenticate();
            when(badgeService.listBadges(any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: xyz"));

            mockMvc.perform(get(BASE + "/{userUuid}", "xyz"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(badgeService.listBadges(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/{userUuid}", USER_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /reputation/badges/endorse
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /endorse")
    class Endorse {

        @Test
        void shouldReturn200AndForwardAllArgumentsWhenValid() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenReturn(badge(BadgeType.GREAT_LISTENER, 3, false));

            String body = """
                    {"recipientUuid":"%s","badgeType":"GREAT_LISTENER"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // SuccessResponseDto.success(data, "Endorsement recorded", "TM_000").
                    .andExpect(jsonPath("$.message").value("Endorsement recorded"))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.type").value("GREAT_LISTENER"))
                    .andExpect(jsonPath("$.data.label").value("Great Listener"))
                    .andExpect(jsonPath("$.data.endorsementCount").value(3))
                    .andExpect(jsonPath("$.data.earned").value(false));

            ArgumentCaptor<User> endorser = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<BadgeType> type = ArgumentCaptor.forClass(BadgeType.class);
            verify(badgeService).endorse(endorser.capture(), recipient.capture(), type.capture());
            assertThat(endorser.getValue()).isSameAs(testUser);
            assertThat(recipient.getValue()).isEqualTo(RECIPIENT_UUID);
            assertThat(type.getValue()).isEqualTo(BadgeType.GREAT_LISTENER);
        }

        @Test
        void shouldForwardEachBadgeTypeVerbatim() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenReturn(badge(BadgeType.COMMUNITY_FAVOURITE, 9, true));

            String body = """
                    {"recipientUuid":"%s","badgeType":"COMMUNITY_FAVOURITE"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.earned").value(true));

            ArgumentCaptor<BadgeType> type = ArgumentCaptor.forClass(BadgeType.class);
            verify(badgeService).endorse(eq(testUser), eq(RECIPIENT_UUID), type.capture());
            assertThat(type.getValue()).isEqualTo(BadgeType.COMMUNITY_FAVOURITE);
        }

        @Test
        void shouldForwardUnicodeRecipientUuidUnchanged() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenReturn(badge(BadgeType.FRIENDLY, 1, false));

            String unicode = "用户-😀-recipient";
            String body = """
                    {"recipientUuid":"%s","badgeType":"FRIENDLY"}""".formatted(unicode);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
            verify(badgeService).endorse(any(), recipient.capture(), any());
            assertThat(recipient.getValue()).isEqualTo(unicode);
        }

        @Test
        void shouldPassThroughXssAndSqliPayloadInRecipientUuid() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenReturn(badge(BadgeType.HELPFUL, 1, false));

            // Free-form String body field: controller must not sanitize/reject — forwarded verbatim.
            String payload = "<script>alert('x')</script>'; DROP TABLE users;--";
            String body = """
                    {"recipientUuid":%s,"badgeType":"HELPFUL"}"""
                    .formatted(quote(payload));
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
            verify(badgeService).endorse(any(), recipient.capture(), any());
            assertThat(recipient.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenRecipientUuidBlank() throws Exception {
            authenticate();
            String body = """
                    {"recipientUuid":"","badgeType":"FRIENDLY"}""";
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenRecipientUuidWhitespaceOnly() throws Exception {
            authenticate();
            // @NotBlank rejects a whitespace-only value.
            String body = """
                    {"recipientUuid":"   ","badgeType":"FRIENDLY"}""";
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenRecipientUuidMissing() throws Exception {
            authenticate();
            // recipientUuid absent → @NotBlank violated.
            String body = """
                    {"badgeType":"FRIENDLY"}""";
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenRecipientUuidNull() throws Exception {
            authenticate();
            String body = """
                    {"recipientUuid":null,"badgeType":"FRIENDLY"}""";
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenBadgeTypeMissing() throws Exception {
            authenticate();
            // badgeType absent → @NotNull violated.
            String body = """
                    {"recipientUuid":"%s"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenBadgeTypeExplicitlyNull() throws Exception {
            authenticate();
            String body = """
                    {"recipientUuid":"%s","badgeType":null}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenBadgeTypeInvalidEnum() throws Exception {
            authenticate();
            // badgeType is a typed BadgeType field — an unknown enum literal fails Jackson
            // deserialization → HttpMessageNotReadableException. There is no dedicated handler,
            // so it falls through to the catch-all → 500 / TM_002 (pins current behaviour).
            String body = """
                    {"recipientUuid":"%s","badgeType":"NOT_A_REAL_BADGE"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenBodyMalformed() throws Exception {
            authenticate();
            // Malformed JSON → HttpMessageNotReadableException → no handler → catch-all 500.
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenBodyMissing() throws Exception {
            authenticate();
            // No request body at all → HttpMessageNotReadableException → catch-all 500.
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(badgeService);
        }

        @Test
        void shouldReturn404WhenRecipientNotFound() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenThrow(new NotFoundException("Recipient not found", "TM_064"));

            String body = """
                    {"recipientUuid":"%s","badgeType":"FRIENDLY"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn403WhenSelfEndorsement() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Cannot endorse yourself", "TM_005"));

            String body = """
                    {"recipientUuid":"%s","badgeType":"FRIENDLY"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn409WhenAlreadyEndorsed() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenThrow(new ConflictException("Already endorsed for this trait", "TM_009"));

            String body = """
                    {"recipientUuid":"%s","badgeType":"FRIENDLY"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_009"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsRequest() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any()))
                    .thenThrow(new BadRequestException("Invalid endorsement", "TM_071"));

            String body = """
                    {"recipientUuid":"%s","badgeType":"FRIENDLY"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(badgeService.endorse(any(), any(), any())).thenThrow(new RuntimeException("boom"));

            String body = """
                    {"recipientUuid":"%s","badgeType":"FRIENDLY"}""".formatted(RECIPIENT_UUID);
            mockMvc.perform(post(BASE + "/endorse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    /** Minimal JSON string literal encoder for embedding an arbitrary payload in a body. */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
