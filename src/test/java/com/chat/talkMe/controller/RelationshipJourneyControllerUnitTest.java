package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MilestoneResponse;
import com.chat.talkMe.dto.response.RelationshipJourneyResponse;
import com.chat.talkMe.enums.MilestoneType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.RelationshipJourneyService;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link RelationshipJourneyController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link RelationshipJourneyService} and the real
 * {@link GlobalExceptionHandler}. The controller is deliberately thin: a single
 * {@code GET /relationship-journey/{userUuid}} that forwards the authenticated
 * {@code userDetails.getUser()} and the RAW {@code String} path segment straight to the service
 * and wraps the result in {@link com.chat.talkMe.dto.response.SuccessResponseDto} (TM_000). There
 * is no request body, no {@code @RequestParam}, no {@code Pageable}, and — unlike
 * {@code CompatibilityController} — NO in-controller {@code UUID.fromString} guard and NO
 * {@code UserRepository} lookup. All UUID parsing / authorization / not-found logic lives in the
 * service, which is mocked here; those outcomes are therefore exercised by driving the mock to
 * throw the corresponding exceptions and asserting the {@link GlobalExceptionHandler} mapping.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('RELATIONSHIP_JOURNEY')")}
 * gate is method-security (inactive in standalone MockMvc) — covered by the integration test.
 *
 * <p><b>N/A for this controller</b> (no surface to hit): tolerant-primitive JSON converter,
 * malformed-JSON→500, missing-@RequestParam→500, bean-validation VE_101, invalid-enum→TM_071,
 * and any {@code verifyNoInteractions} "skip" path — the controller has no early-return branch,
 * so the service is invoked on every reachable request.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RelationshipJourneyController (unit)")
class RelationshipJourneyControllerUnitTest {

    private static final String BASE = "/relationship-journey";
    private static final String OTHER_UUID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private RelationshipJourneyService relationshipJourneyService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        RelationshipJourneyController controller =
                new RelationshipJourneyController(relationshipJourneyService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder().username("me").email("me@e.com").name("Me")
                .isGuest(false).roles(Set.of(role)).build();

        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static RelationshipJourneyResponse journey(List<MilestoneResponse> milestones) {
        return RelationshipJourneyResponse.builder()
                .otherUserUuid(OTHER_UUID)
                .milestones(milestones)
                .build();
    }

    private static MilestoneResponse milestone(MilestoneType type, String detail) {
        return MilestoneResponse.builder()
                .type(type)
                .label(type != null ? type.getLabel() : null)
                .achievedAt(Instant.parse("2026-01-15T10:30:00Z"))
                .detail(detail)
                .build();
    }

    @Nested
    @DisplayName("GET /relationship-journey/{userUuid}")
    class GetJourney {

        @Test
        @DisplayName("returns the timeline with milestones for a valid request")
        void shouldReturnJourneyWithMilestones() throws Exception {
            RelationshipJourneyResponse resp = journey(List.of(
                    milestone(MilestoneType.BECAME_FRIENDS, "since Jan"),
                    milestone(MilestoneType.ONE_MONTH_FRIENDS, "one month")));
            when(relationshipJourneyService.getJourney(any(), anyString())).thenReturn(resp);

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // Success code is minted directly by SuccessResponseDto.success(...) => TM_000.
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.otherUserUuid").value(OTHER_UUID))
                    .andExpect(jsonPath("$.data.milestones.length()").value(2))
                    .andExpect(jsonPath("$.data.milestones[0].type").value("BECAME_FRIENDS"))
                    .andExpect(jsonPath("$.data.milestones[0].label").value("You became friends"))
                    .andExpect(jsonPath("$.data.milestones[0].detail").value("since Jan"))
                    .andExpect(jsonPath("$.data.milestones[0].achievedAt").exists())
                    .andExpect(jsonPath("$.data.milestones[1].type").value("ONE_MONTH_FRIENDS"))
                    .andExpect(jsonPath("$.data.milestones[1].label").value("One month of friendship"));
        }

        @Test
        @DisplayName("forwards the authenticated user and the raw path segment to the service")
        void shouldPassUserAndPathUuidToService() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenReturn(journey(List.of()));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isOk());

            ArgumentCaptor<User> viewer = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(relationshipJourneyService).getJourney(viewer.capture(), uuid.capture());
            // The exact authenticated principal instance is threaded through unchanged.
            assertThat(viewer.getValue()).isSameAs(testUser);
            assertThat(uuid.getValue()).isEqualTo(OTHER_UUID);
        }

        @Test
        @DisplayName("returns an empty milestone list unchanged")
        void shouldReturnEmptyMilestones() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenReturn(journey(List.of()));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.otherUserUuid").value(OTHER_UUID))
                    .andExpect(jsonPath("$.data.milestones.length()").value(0));
        }

        @Test
        @DisplayName("serializes a null milestone list as null")
        void shouldTolerateNullMilestoneList() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenReturn(journey(null));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.otherUserUuid").value(OTHER_UUID))
                    // Default Jackson (Include.ALWAYS) keeps the key present with a null value.
                    .andExpect(jsonPath("$.data.milestones").value(org.hamcrest.Matchers.nullValue()));
        }

        @Test
        @DisplayName("forwards an arbitrary (non-UUID / injection-shaped) path segment verbatim to the service")
        void shouldForwardArbitraryPathSegmentUnchanged() throws Exception {
            // The controller performs NO parsing or sanitizing of the {userUuid} path variable —
            // it passes whatever the router captured straight through. Prove that a SQLi/XSS-shaped
            // string survives untouched (the service is the trust boundary, not the controller).
            String weird = "1' OR '1'='1<script>";
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenReturn(journey(List.of()));

            mockMvc.perform(get(BASE + "/" + weird))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(relationshipJourneyService).getJourney(eq(testUser), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(weird);
        }

        @Test
        @DisplayName("forwards a unicode/emoji path segment verbatim to the service")
        void shouldForwardUnicodePathSegmentUnchanged() throws Exception {
            String unicode = "café-😀-你好";
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenReturn(journey(List.of()));

            mockMvc.perform(get(BASE + "/" + unicode))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(relationshipJourneyService).getJourney(eq(testUser), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(unicode);
        }

        // ---- error mappings via GlobalExceptionHandler ------------------------------------

        @Test
        @DisplayName("NotFoundException from the service -> 404 with its message code")
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new NotFoundException("no such user", "TM_024"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
        }

        @Test
        @DisplayName("NotFoundException default ctor -> 404/TM_101")
        void shouldReturn404DefaultCode() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new NotFoundException("gone"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        @DisplayName("ForbiddenException (viewer is neither the user nor a friend) -> 403 via ServiceException path")
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            // ForbiddenException is a ServiceException(status=403); it maps through
            // handleServiceException using its own code (NOT the AccessDeniedException/TM_005 path).
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new ForbiddenException("not your journey", "TM_103"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        @DisplayName("Spring AccessDeniedException -> 403/TM_005")
        void shouldReturn403TM005ForAccessDenied() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        @DisplayName("ConflictException from the service -> 409 with its code")
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new ConflictException("conflict", "TM_120"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_120"));
        }

        @Test
        @DisplayName("BadRequestException from the service -> 400 with its code")
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new BadRequestException("bad", "TM_130"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_130"));
        }

        @Test
        @DisplayName("generic ServiceException -> status + code passed through verbatim")
        void shouldPassThroughServiceExceptionStatusAndCode() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new ServiceException(418, "teapot", "TM_999"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isIAmATeapot())
                    .andExpect(jsonPath("$.messageCode").value("TM_999"));
        }

        @Test
        @DisplayName("malformed-UUID IllegalArgumentException from the service -> 400/TM_INVALID_UUID")
        void shouldReturn400InvalidUuidWhenServiceRejectsUuidFormat() throws Exception {
            // The service is where UUID.fromString(otherUserUuid) actually runs; when the string is
            // malformed it throws IllegalArgumentException("Invalid UUID string: ..."), which the
            // handler special-cases to TM_INVALID_UUID.
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

            mockMvc.perform(get(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }

        @Test
        @DisplayName("generic IllegalArgumentException from the service -> 400/TM_071")
        void shouldReturn400TM071ForGenericIllegalArgument() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new IllegalArgumentException("bad argument"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        @DisplayName("unexpected RuntimeException from the service -> 500/TM_002")
        void shouldReturn500WhenServiceThrowsRuntime() throws Exception {
            when(relationshipJourneyService.getJourney(any(), anyString()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }
}
