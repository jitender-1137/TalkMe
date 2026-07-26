package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.WeeklyMatchPickResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.WeeklyMatchPickService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link WeeklyMatchPickController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link WeeklyMatchPickService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> the controller exposes a single read-only endpoint —
 * {@code GET /match/weekly-picks}. It takes no request body, no request params, no path
 * variables and no {@code Pageable}, so there is no bean-validation surface, no missing-param
 * branch, no malformed-JSON branch and no enum-parsing branch to exercise here; likewise there
 * is no free-form String body field on which to run a unicode/XSS/SQLi pass-through, so those
 * payloads are exercised on the returned (service-supplied) String fields instead. The class
 * {@code @PreAuthorize("hasRole('USER')")} and the method
 * {@code @PreAuthorize("@featureGuard.check('WEEKLY_PICKS')")} entitlement gate are enforced by
 * Spring method-security, which is inactive in standalone MockMvc — those are covered by the
 * integration test. Service-layer authorization/limits are driven here via stubbed exceptions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WeeklyMatchPickController (unit)")
class WeeklyMatchPickControllerUnitTest {

    private static final String BASE = "/match/weekly-picks";
    // Success code comes DIRECTLY from the controller: SuccessResponseDto.success(picks)
    // resolves to ResponseDto.success(data) → messageCode "TM_000", message "Success".
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String INVALID_ARG_CODE = "TM_071";
    private static final String INVALID_UUID_CODE = "TM_INVALID_UUID";
    private static final String ACCESS_DENIED_CODE = "TM_005";

    @Mock
    private WeeklyMatchPickService weeklyMatchPickService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        WeeklyMatchPickController controller =
                new WeeklyMatchPickController(weeklyMatchPickService);

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

    private static CompatibilityScore score(int overall, String bucket) {
        return CompatibilityScore.builder()
                .overall(overall)
                .bucket(bucket)
                .breakdown(Map.of("interests", 90, "location", 75))
                .highlights(List.of("both love hiking", "same night-owl energy"))
                .explanation("You share several interests.")
                .build();
    }

    private static WeeklyMatchPickResponse pick(String id, String name, int rank, int score) {
        return WeeklyMatchPickResponse.builder()
                .id(id)
                .name(name)
                .username("user-" + rank)
                .avatar("https://cdn/" + id + ".png")
                .mood("chatty")
                .country("US")
                .age(27)
                .rank(rank)
                .score(score)
                .compatibility(score(score, score >= 80 ? "HIGH" : "MEDIUM"))
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /match/weekly-picks  (current week)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /match/weekly-picks")
    class Current {

        @Test
        void shouldReturn200AndEmptyDataWhenNoPicksGeneratedYet() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn200AndSerializeAllPickFieldsIncludingNestedCompatibility() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenReturn(List.of(pick("u-1", "Alice", 1, 92)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id").value("u-1"))
                    .andExpect(jsonPath("$.data[0].name").value("Alice"))
                    .andExpect(jsonPath("$.data[0].username").value("user-1"))
                    .andExpect(jsonPath("$.data[0].avatar").value("https://cdn/u-1.png"))
                    .andExpect(jsonPath("$.data[0].mood").value("chatty"))
                    .andExpect(jsonPath("$.data[0].country").value("US"))
                    .andExpect(jsonPath("$.data[0].age").value(27))
                    .andExpect(jsonPath("$.data[0].rank").value(1))
                    .andExpect(jsonPath("$.data[0].score").value(92))
                    .andExpect(jsonPath("$.data[0].compatibility.overall").value(92))
                    .andExpect(jsonPath("$.data[0].compatibility.bucket").value("HIGH"))
                    .andExpect(jsonPath("$.data[0].compatibility.breakdown.interests").value(90))
                    .andExpect(jsonPath("$.data[0].compatibility.highlights[0]").value("both love hiking"))
                    .andExpect(jsonPath("$.data[0].compatibility.explanation")
                            .value("You share several interests."));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldForwardTheAuthenticatedUserToService() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(weeklyMatchPickService).getCurrent(userCaptor.capture());
            assertThat(userCaptor.getValue()).isSameAs(testUser);
            assertThat(userCaptor.getValue().getUsername()).isEqualTo("testuser");
        }

        @Test
        void shouldPreserveServiceRankingOrder() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any())).thenReturn(List.of(
                    pick("u-1", "Alice", 1, 92),
                    pick("u-2", "Bob", 2, 81),
                    pick("u-3", "Carol", 3, 64)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(3)))
                    .andExpect(jsonPath("$.data[0].id").value("u-1"))
                    .andExpect(jsonPath("$.data[0].rank").value(1))
                    .andExpect(jsonPath("$.data[1].id").value("u-2"))
                    .andExpect(jsonPath("$.data[1].rank").value(2))
                    .andExpect(jsonPath("$.data[2].id").value("u-3"))
                    .andExpect(jsonPath("$.data[2].rank").value(3))
                    .andExpect(jsonPath("$.data[2].compatibility.bucket").value("MEDIUM"));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldPassThroughUnicodeAndEmojiInStringFields() throws Exception {
            authenticate();
            // No request body exists on this endpoint, so the unicode/emoji pass-through is
            // exercised on the service-supplied (free-form) String fields returned to the client.
            WeeklyMatchPickResponse p = WeeklyMatchPickResponse.builder()
                    .id("u-uni").name("测试用户 🌙✨").username("user-😀")
                    .avatar("a.png").mood("dreamy 😴").country("日本").age(30)
                    .rank(1).score(70)
                    .compatibility(score(70, "MEDIUM"))
                    .build();
            when(weeklyMatchPickService.getCurrent(any())).thenReturn(List.of(p));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].name").value("测试用户 🌙✨"))
                    .andExpect(jsonPath("$.data[0].mood").value("dreamy 😴"))
                    .andExpect(jsonPath("$.data[0].country").value("日本"));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldPassThroughXssAndSqliPayloadInStringFieldsVerbatim() throws Exception {
            authenticate();
            // The controller does not sanitise; payloads must round-trip verbatim (JSON-escaped)
            // exactly as the service returned them — no HTML/entity mangling, no truncation.
            String xss = "<script>alert('x')</script>";
            String sqli = "Robert'); DROP TABLE picks;--";
            WeeklyMatchPickResponse p = WeeklyMatchPickResponse.builder()
                    .id("u-evil").name(xss).username("user-x")
                    .avatar("a.png").mood(sqli).country("US").age(21)
                    .rank(1).score(50)
                    .compatibility(score(50, "MEDIUM"))
                    .build();
            when(weeklyMatchPickService.getCurrent(any())).thenReturn(List.of(p));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].name").value(xss))
                    .andExpect(jsonPath("$.data[0].mood").value(sqli));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Error mapping via GlobalExceptionHandler
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Error mapping (GlobalExceptionHandler)")
    class ErrorMapping {

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new NotFoundException("No picks generated for this week", "TM_101"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new ForbiddenException("Weekly picks entitlement required", "TM_103"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new ServiceException(HttpStatus.CONFLICT.value(),
                            "Picks are being regenerated", "TM_409"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new ServiceException(HttpStatus.BAD_REQUEST.value(),
                            "Bad request", "TM_400"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn403AndTm005WhenAccessDenied() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value(ACCESS_DENIED_CODE));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn400AndTm071WhenIllegalArgument() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new IllegalArgumentException("bad value"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_ARG_CODE));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn400AndInvalidUuidWhenIllegalArgumentIsUuidMessage() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: abc"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_UUID_CODE));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldReturn500AndTm002WhenUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verify(weeklyMatchPickService).getCurrent(testUser);
        }

        @Test
        void shouldNotHitAnyOtherServiceMethodOnRead() throws Exception {
            authenticate();
            when(weeklyMatchPickService.getCurrent(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            // getCurrent is the ONLY method the read path may touch; generateFor / pruneOlderThan
            // must never be reached from the controller.
            verify(weeklyMatchPickService).getCurrent(testUser);
            org.mockito.Mockito.verifyNoMoreInteractions(weeklyMatchPickService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Unauthenticated principal resolution
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("No authenticated principal")
    class Unauthenticated {

        @Test
        void shouldReturn500WhenNoPrincipalPresent() throws Exception {
            // No authenticate() call: @AuthenticationPrincipal resolves to null, so
            // userDetails.getUser() NPEs inside the controller before the service is called.
            // The catch-all maps it to 500 / TM_002. The service is never invoked.
            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verifyNoInteractions(weeklyMatchPickService);
        }
    }
}
