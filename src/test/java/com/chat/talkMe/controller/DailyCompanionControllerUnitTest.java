package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.DailyCompanionResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.DailyCompanionService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * Pure controller unit test for {@link DailyCompanionController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link DailyCompanionService} and the real
 * {@link GlobalExceptionHandler}. Only {@link AuthenticationPrincipalArgumentResolver} is
 * registered — the controller has no {@code @RequestBody} (both endpoints take only path/query
 * params and {@code @AuthenticationPrincipal}), so no Jackson message-converter tweak and no
 * {@code PageableHandlerMethodArgumentResolver} are needed.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize("hasRole('USER')")} and the
 * {@code @PreAuthorize("@featureGuard.check('DAILY_COMPANION')")} feature gate are enforced by
 * Spring's method-security interceptor (AOP), which is NOT active in a standalone MockMvc setup.
 * Those gates are covered by the integration tests. Likewise the {@code act(...)} argument
 * validation (blank / unknown action) lives in the SERVICE ({@code BadRequestException},
 * {@code TM_400}); the controller forwards the raw {@code value} verbatim, so here we mock that
 * rejection to pin the wiring. This test verifies request/response wiring and delegation only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyCompanionController (unit)")
class DailyCompanionControllerUnitTest {

    private static final String BASE = "/daily-companion";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String BAD_REQUEST_CODE = "TM_400";
    private static final String PAIRING_UUID = "pairing-uuid-1";
    private static final String COMPANION_UUID = "companion-uuid-1";

    @Mock
    private DailyCompanionService dailyCompanionService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        DailyCompanionController controller = new DailyCompanionController(dailyCompanionService);

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

    /** A fully-populated companion card with a nested compatibility score. */
    private static DailyCompanionResponse fullCard(String status) {
        return DailyCompanionResponse.builder()
                .pairingUuid(PAIRING_UUID)
                .pairDate(LocalDate.of(2026, 7, 22))
                .status(status)
                .expiresAt(Instant.parse("2026-07-23T00:05:00Z"))
                .companionUuid(COMPANION_UUID)
                .name("Ada")
                .username("ada_l")
                .avatar("https://cdn/ada.png")
                .age(29)
                .country("US")
                .mood("CURIOUS")
                .compatibility(CompatibilityScore.builder()
                        .overall(87)
                        .breakdown(Map.of("interests", 90, "mood", 80))
                        .highlights(List.of("both love jazz"))
                        .explanation("You share a lot")
                        .bucket("HIGH")
                        .build())
                .build();
    }

    /** An "empty card" — assigner hasn't run / user ineligible, so all companion fields are null. */
    private static DailyCompanionResponse emptyCard() {
        return DailyCompanionResponse.builder().build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /daily-companion/today
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /today")
    class GetToday {

        @Test
        void shouldReturn200WithCardAndForwardAuthenticatedUser() throws Exception {
            authenticate();
            when(dailyCompanionService.getToday(any())).thenReturn(fullCard("ACTIVE"));

            mockMvc.perform(get(BASE + "/today"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // SUCCESS code read directly: getToday() uses SuccessResponseDto.success(response) → TM_000.
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.pairingUuid").value(PAIRING_UUID))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.companionUuid").value(COMPANION_UUID))
                    .andExpect(jsonPath("$.data.name").value("Ada"))
                    .andExpect(jsonPath("$.data.username").value("ada_l"))
                    .andExpect(jsonPath("$.data.age").value(29))
                    .andExpect(jsonPath("$.data.country").value("US"))
                    .andExpect(jsonPath("$.data.mood").value("CURIOUS"))
                    .andExpect(jsonPath("$.data.compatibility.overall").value(87))
                    .andExpect(jsonPath("$.data.compatibility.bucket").value("HIGH"))
                    .andExpect(jsonPath("$.data.compatibility.highlights[0]").value("both love jazz"));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(dailyCompanionService).getToday(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            // getToday must never trigger a mutation.
            verify(dailyCompanionService, never()).act(any(), any());
        }

        @Test
        void shouldReturn200WithEmptyCardWhenNoCompanionAssigned() throws Exception {
            authenticate();
            when(dailyCompanionService.getToday(any())).thenReturn(emptyCard());

            mockMvc.perform(get(BASE + "/today"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    // Null companion fields are omitted from the JSON body.
                    .andExpect(jsonPath("$.data.pairingUuid").doesNotExist())
                    .andExpect(jsonPath("$.data.companionUuid").doesNotExist())
                    .andExpect(jsonPath("$.data.compatibility").doesNotExist());

            verify(dailyCompanionService).getToday(testUser);
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(dailyCompanionService.getToday(any()))
                    .thenThrow(new NotFoundException("User not found", "TM_101"));

            mockMvc.perform(get(BASE + "/today"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(dailyCompanionService.getToday(any()))
                    .thenThrow(new ForbiddenException("Feature not enabled for this account", "TM_103"));

            mockMvc.perform(get(BASE + "/today"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            authenticate();
            when(dailyCompanionService.getToday(any()))
                    .thenThrow(new ServiceException(409, "Assignment in progress", "TM_409"));

            mockMvc.perform(get(BASE + "/today"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(dailyCompanionService.getToday(any()))
                    .thenThrow(new BadRequestException("Ineligible", BAD_REQUEST_CODE));

            mockMvc.perform(get(BASE + "/today"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(BAD_REQUEST_CODE));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(dailyCompanionService.getToday(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/today"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /daily-companion/action
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /action")
    class Act {

        @Test
        void shouldReturn200AndForwardValueAndUserForContinue() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any())).thenReturn(fullCard("ACTIVE"));

            mockMvc.perform(post(BASE + "/action").param("value", "CONTINUE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // SUCCESS code + message read directly from the controller.
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Companion updated"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.companionUuid").value(COMPANION_UUID));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(dailyCompanionService).act(user.capture(), value.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(value.getValue()).isEqualTo("CONTINUE");
            // act must never fall through to a read.
            verify(dailyCompanionService, never()).getToday(any());
        }

        @Test
        void shouldForwardStayFriendsValueVerbatim() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any())).thenReturn(fullCard("CONVERTED_FRIENDS"));

            mockMvc.perform(post(BASE + "/action").param("value", "STAY_FRIENDS"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CONVERTED_FRIENDS"));

            verify(dailyCompanionService).act(eq(testUser), eq("STAY_FRIENDS"));
        }

        @Test
        void shouldForwardEndValueVerbatim() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any())).thenReturn(fullCard("ENDED"));

            mockMvc.perform(post(BASE + "/action").param("value", "END"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ENDED"));

            verify(dailyCompanionService).act(eq(testUser), eq("END"));
        }

        @Test
        void shouldForwardBlankValueToServiceWhichRejectsIt() throws Exception {
            authenticate();
            // The param is present-but-empty (not missing), so it binds and reaches the service,
            // which rejects blank input with BadRequestException(400, TM_400).
            when(dailyCompanionService.act(any(), eq("")))
                    .thenThrow(new BadRequestException("action is required", BAD_REQUEST_CODE));

            mockMvc.perform(post(BASE + "/action").param("value", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(BAD_REQUEST_CODE));

            verify(dailyCompanionService).act(testUser, "");
        }

        @Test
        void shouldReturn400WhenServiceRejectsUnknownAction() throws Exception {
            authenticate();
            // 'value' is a plain String @RequestParam (no enum binding at the controller), so an
            // unknown action is NOT a bind failure — it reaches the service, which throws
            // BadRequestException(400, TM_400). (Verified against DailyCompanionServiceImpl.act.)
            when(dailyCompanionService.act(any(), eq("MAYBE")))
                    .thenThrow(new BadRequestException(
                            "action must be STAY_FRIENDS, CONTINUE or END", BAD_REQUEST_CODE));

            mockMvc.perform(post(BASE + "/action").param("value", "MAYBE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(BAD_REQUEST_CODE));

            verify(dailyCompanionService).act(testUser, "MAYBE");
        }

        @Test
        void shouldReturn400WhenDecisionAlreadyFinal() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any()))
                    .thenThrow(new BadRequestException("This companion decision is already final", BAD_REQUEST_CODE));

            mockMvc.perform(post(BASE + "/action").param("value", "END"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(BAD_REQUEST_CODE));
        }

        @Test
        void shouldReturn500WhenValueParamMissing() throws Exception {
            authenticate();
            // Required @RequestParam("value") absent → MissingServletRequestParameterException,
            // which has no dedicated handler → catch-all 500 (pins current behaviour).
            mockMvc.perform(post(BASE + "/action"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(dailyCompanionService);
        }

        @Test
        void shouldForwardUnicodeAndEmojiValueVerbatim() throws Exception {
            authenticate();
            String weird = "CONTINUE 名前 😀";
            when(dailyCompanionService.act(any(), any())).thenReturn(fullCard("ACTIVE"));

            mockMvc.perform(post(BASE + "/action").param("value", weird))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(dailyCompanionService).act(eq(testUser), value.capture());
            assertThat(value.getValue()).isEqualTo(weird);
        }

        @Test
        void shouldPassThroughXssPayloadUnsanitizedToService() throws Exception {
            authenticate();
            String xss = "<script>alert('x')</script>";
            when(dailyCompanionService.act(any(), any())).thenReturn(fullCard("ACTIVE"));

            mockMvc.perform(post(BASE + "/action").param("value", xss))
                    .andExpect(status().isOk());

            // The controller does not sanitize — the raw payload must reach the service unchanged.
            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(dailyCompanionService).act(eq(testUser), value.capture());
            assertThat(value.getValue()).isEqualTo(xss);
        }

        @Test
        void shouldPassThroughSqlInjectionPayloadUnsanitizedToService() throws Exception {
            authenticate();
            String sqli = "END'; DROP TABLE daily_companion;--";
            when(dailyCompanionService.act(any(), any())).thenReturn(fullCard("ENDED"));

            mockMvc.perform(post(BASE + "/action").param("value", sqli))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(dailyCompanionService).act(eq(testUser), value.capture());
            assertThat(value.getValue()).isEqualTo(sqli);
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any()))
                    .thenThrow(new NotFoundException("No companion assigned today", "TM_101"));

            mockMvc.perform(post(BASE + "/action").param("value", "CONTINUE"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any()))
                    .thenThrow(new ForbiddenException("Not your companion", "TM_103"));

            mockMvc.perform(post(BASE + "/action").param("value", "END"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any()))
                    .thenThrow(new ServiceException(409, "Concurrent update", "TM_409"));

            mockMvc.perform(post(BASE + "/action").param("value", "CONTINUE"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(dailyCompanionService.act(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/action").param("value", "CONTINUE"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
