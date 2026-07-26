package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.StreakResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.StreakService;
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

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link StreakController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link StreakService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize("hasRole('USER')")} and
 * {@code @PreAuthorize("@featureGuard.check('STREAKS')")} are enforced by Spring's
 * method-security interceptor (AOP), which is NOT active in a standalone MockMvc setup —
 * those gates are covered by the integration test. Here we verify request/response wiring
 * and delegation to the service.
 *
 * <p><b>Surface note:</b> both endpoints take only {@code @AuthenticationPrincipal} — there is
 * no {@code @RequestBody}, no {@code @PathVariable}, no {@code @RequestParam} and no repository
 * lookup. Consequently the usual malformed-JSON, missing-request-param, path-UUID/not-found,
 * bean-validation and free-form-body (unicode/XSS/SQLi) edge cases do not apply and are omitted
 * deliberately. No tolerant Jackson converter and no Pageable resolver are needed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StreakController (unit)")
class StreakControllerUnitTest {

    private static final String BASE = "/reputation/streak";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private StreakService streakService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        StreakController controller = new StreakController(streakService);

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

    private static StreakResponse streak(int current, int longest, LocalDate lastDay, int freeze) {
        return StreakResponse.builder()
                .currentStreak(current)
                .longestStreak(longest)
                .lastCheckInDay(lastDay)
                .freezeTokens(freeze)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /reputation/streak  (getStreak)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /reputation/streak")
    class GetStreak {

        @Test
        void shouldReturn200WithZeroedStreakOnFirstRead() throws Exception {
            authenticate();
            when(streakService.getStreak(any())).thenReturn(streak(0, 0, null, 0));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.currentStreak").value(0))
                    .andExpect(jsonPath("$.data.longestStreak").value(0))
                    .andExpect(jsonPath("$.data.freezeTokens").value(0));
        }

        @Test
        void shouldReturn200WithNonZeroStreakAndAllFields() throws Exception {
            authenticate();
            when(streakService.getStreak(any()))
                    .thenReturn(streak(12, 45, LocalDate.of(2026, 7, 22), 2));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.currentStreak").value(12))
                    .andExpect(jsonPath("$.data.longestStreak").value(45))
                    .andExpect(jsonPath("$.data.lastCheckInDay").value("2026-07-22"))
                    .andExpect(jsonPath("$.data.freezeTokens").value(2));
        }

        @Test
        void shouldForwardAuthenticatedUserToService() throws Exception {
            authenticate();
            when(streakService.getStreak(any())).thenReturn(streak(1, 1, LocalDate.of(2026, 7, 22), 0));

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(streakService).getStreak(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            verify(streakService, never()).checkIn(any());
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(streakService.getStreak(any()))
                    .thenThrow(new NotFoundException("Streak not found", "TM_101"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(streakService.getStreak(any()))
                    .thenThrow(new ForbiddenException("Streaks not available", "TM_103"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(streakService.getStreak(any()))
                    .thenThrow(new BadRequestException("Bad request", "TM_071"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsIllegalArgument() throws Exception {
            authenticate();
            // GlobalExceptionHandler maps a generic IllegalArgumentException → 400/TM_071.
            when(streakService.getStreak(any()))
                    .thenThrow(new IllegalArgumentException("bad arg"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsAccessDenied() throws Exception {
            authenticate();
            // GlobalExceptionHandler maps Spring's AccessDeniedException → 403/TM_005.
            when(streakService.getStreak(any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(streakService.getStreak(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /reputation/streak/checkin  (checkIn)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /reputation/streak/checkin")
    class CheckIn {

        @Test
        void shouldReturn200AndTM950WhenStreakIncremented() throws Exception {
            authenticate();
            when(streakService.checkIn(any()))
                    .thenReturn(streak(5, 5, LocalDate.of(2026, 7, 22), 1));

            mockMvc.perform(post(BASE + "/checkin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // Message code comes DIRECTLY from the controller's success(..., "TM_950") call.
                    .andExpect(jsonPath("$.messageCode").value("TM_950"))
                    .andExpect(jsonPath("$.message").value("Streak updated"))
                    .andExpect(jsonPath("$.data.currentStreak").value(5))
                    .andExpect(jsonPath("$.data.longestStreak").value(5))
                    .andExpect(jsonPath("$.data.lastCheckInDay").value("2026-07-22"))
                    .andExpect(jsonPath("$.data.freezeTokens").value(1));
        }

        @Test
        void shouldReturn200WhenIdempotentSameDayCheckIn() throws Exception {
            authenticate();
            // A second same-day check-in is a no-op — service returns the unchanged snapshot.
            when(streakService.checkIn(any()))
                    .thenReturn(streak(5, 10, LocalDate.of(2026, 7, 22), 3));

            mockMvc.perform(post(BASE + "/checkin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_950"))
                    .andExpect(jsonPath("$.data.currentStreak").value(5))
                    .andExpect(jsonPath("$.data.longestStreak").value(10));
        }

        @Test
        void shouldReturn200WithResetStreakWhenGapNotAbsorbed() throws Exception {
            authenticate();
            // Missed day with no freeze token → streak resets to 1, freezeTokens 0.
            when(streakService.checkIn(any()))
                    .thenReturn(streak(1, 30, LocalDate.of(2026, 7, 22), 0));

            mockMvc.perform(post(BASE + "/checkin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_950"))
                    .andExpect(jsonPath("$.data.currentStreak").value(1))
                    .andExpect(jsonPath("$.data.longestStreak").value(30))
                    .andExpect(jsonPath("$.data.freezeTokens").value(0));
        }

        @Test
        void shouldForwardAuthenticatedUserToService() throws Exception {
            authenticate();
            when(streakService.checkIn(any())).thenReturn(streak(2, 2, LocalDate.of(2026, 7, 22), 0));

            mockMvc.perform(post(BASE + "/checkin")).andExpect(status().isOk());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(streakService).checkIn(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            verify(streakService, never()).getStreak(any());
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(streakService.checkIn(any()))
                    .thenThrow(new NotFoundException("Streak not found", "TM_101"));

            mockMvc.perform(post(BASE + "/checkin"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(streakService.checkIn(any()))
                    .thenThrow(new ForbiddenException("Streaks not available", "TM_103"));

            mockMvc.perform(post(BASE + "/checkin"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(streakService.checkIn(any()))
                    .thenThrow(new BadRequestException("Bad request", "TM_071"));

            mockMvc.perform(post(BASE + "/checkin"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(streakService.checkIn(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/checkin"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
