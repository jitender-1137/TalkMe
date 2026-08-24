package com.neo.chat.controller;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.TravelCompanionResponse;
import com.neo.chat.dto.response.TripResponse;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ConflictException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.TravelCompanionService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link TravelCompanionController} (Travel Companion).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link TravelCompanionService} and the real
 * {@link GlobalExceptionHandler}. The {@code @PreAuthorize} (role +
 * {@code @featureGuard.check('TRAVEL_COMPANION')}) is inactive under standalone MockMvc —
 * covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TravelCompanionController (unit)")
class TravelCompanionControllerUnitTest {

    private static final String BASE = "/travel";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String TRIP_ID = "trip-uuid-1";
    private static final String TARGET_ID = "target-uuid-1";

    @Mock
    private TravelCompanionService travelCompanionService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        TravelCompanionController controller = new TravelCompanionController(travelCompanionService);

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

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static TripResponse trip() {
        return TripResponse.builder()
                .uuid(TRIP_ID).destination("Tokyo")
                .startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 10))
                .note("hiking").status("ACTIVE").build();
    }

    private static TravelCompanionResponse companion(String username, String presence) {
        return TravelCompanionResponse.builder()
                .userUuid(TARGET_ID).name("Buddy").username(username)
                .avatar("https://cdn/a.png").country("JP").city("Tokyo").mood("CASUAL")
                .presence(presence)
                .tripUuid("their-trip").destination("Tokyo")
                .startDate(LocalDate.of(2026, 9, 3)).endDate(LocalDate.of(2026, 9, 8))
                .build();
    }

    // ── POST /travel/trips ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /travel/trips")
    class AddTrip {

        private String body() {
            return "{\"destination\":\"Tokyo\",\"startDate\":\"2026-09-01\","
                    + "\"endDate\":\"2026-09-10\",\"note\":\"hiking\"}";
        }

        @Test
        void returns200AndForwardsParsedFields() throws Exception {
            authenticate();
            when(travelCompanionService.addTrip(any(), any(), any(), any(), any())).thenReturn(trip());

            mockMvc.perform(post(BASE + "/trips")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Trip added"))
                    .andExpect(jsonPath("$.data.destination").value("Tokyo"))
                    .andExpect(jsonPath("$.data.startDate").value("2026-09-01"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));

            ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
            ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(travelCompanionService)
                    .addTrip(eq(testUser), dest.capture(), start.capture(), end.capture(), note.capture());
            assertThat(dest.getValue()).isEqualTo("Tokyo");
            assertThat(start.getValue()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(end.getValue()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(note.getValue()).isEqualTo("hiking");
        }

        @Test
        void returns400WhenServiceRejectsRange() throws Exception {
            authenticate();
            when(travelCompanionService.addTrip(any(), any(), any(), any(), any()))
                    .thenThrow(new BadRequestException("bad range", "TM_871"));

            mockMvc.perform(post(BASE + "/trips")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_871"));
        }

        @Test
        void returns409WhenAtActiveCap() throws Exception {
            authenticate();
            when(travelCompanionService.addTrip(any(), any(), any(), any(), any()))
                    .thenThrow(new ConflictException("too many", "TM_873"));

            mockMvc.perform(post(BASE + "/trips")
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_873"));
        }

        @Test
        void returns400WhenValidationFailsOnMissingDestination() throws Exception {
            authenticate();

            mockMvc.perform(post(BASE + "/trips")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-10\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── GET /travel/trips ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /travel/trips")
    class GetMyTrips {

        @Test
        void returns200WithTrips() throws Exception {
            authenticate();
            when(travelCompanionService.getMyTrips(any())).thenReturn(List.of(trip()));

            mockMvc.perform(get(BASE + "/trips"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].destination").value("Tokyo"))
                    .andExpect(jsonPath("$.data[0].note").value("hiking"));

            verify(travelCompanionService).getMyTrips(testUser);
        }
    }

    // ── DELETE /travel/trips/{uuid} ──────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /travel/trips/{uuid}")
    class CancelTrip {

        @Test
        void returns200AndForwardsArgs() throws Exception {
            authenticate();

            mockMvc.perform(delete(BASE + "/trips/" + TRIP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Trip cancelled"));

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(travelCompanionService).cancelTrip(uuid.capture(), eq(testUser));
            assertThat(uuid.getValue()).isEqualTo(TRIP_ID);
        }

        @Test
        void returns404WhenTripNotFound() throws Exception {
            authenticate();
            org.mockito.Mockito.doThrow(new NotFoundException("Trip not found", "TM_874"))
                    .when(travelCompanionService).cancelTrip(any(), any());

            mockMvc.perform(delete(BASE + "/trips/ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_874"));
        }

        @Test
        void returns403WhenNotOwner() throws Exception {
            authenticate();
            org.mockito.Mockito.doThrow(new ForbiddenException("not owner", "TM_875"))
                    .when(travelCompanionService).cancelTrip(any(), any());

            mockMvc.perform(delete(BASE + "/trips/" + TRIP_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_875"));
        }
    }

    // ── GET /travel/trips/{uuid}/companions ──────────────────────────────────────

    @Nested
    @DisplayName("GET /travel/trips/{uuid}/companions")
    class Companions {

        @Test
        void returns200WithCompanions() throws Exception {
            authenticate();
            when(travelCompanionService.findCompanions(any(), any()))
                    .thenReturn(List.of(companion("buddy", "ONLINE")));

            mockMvc.perform(get(BASE + "/trips/" + TRIP_ID + "/companions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].username").value("buddy"))
                    .andExpect(jsonPath("$.data[0].presence").value("ONLINE"))
                    .andExpect(jsonPath("$.data[0].destination").value("Tokyo"))
                    .andExpect(jsonPath("$.data[0].startDate").value("2026-09-03"));

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(travelCompanionService).findCompanions(eq(testUser), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(TRIP_ID);
        }

        @Test
        void returns400WhenInvalidTripUuid() throws Exception {
            authenticate();
            when(travelCompanionService.findCompanions(any(), any()))
                    .thenThrow(new BadRequestException("Invalid trip id", "TM_871"));

            mockMvc.perform(get(BASE + "/trips/not-a-uuid/companions"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_871"));
        }
    }

    // ── POST /travel/{userUuid}/connect ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /travel/{userUuid}/connect")
    class Connect {

        @Test
        void returns200WithPersonCard() throws Exception {
            authenticate();
            when(travelCompanionService.connectTraveler(any(), any()))
                    .thenReturn(companion("buddy", "AWAY"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/connect"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userUuid").value(TARGET_ID))
                    .andExpect(jsonPath("$.data.username").value("buddy"))
                    .andExpect(jsonPath("$.data.presence").value("AWAY"));

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(travelCompanionService).connectTraveler(eq(testUser), uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(TARGET_ID);
        }

        @Test
        void returns404WhenTargetNotFound() throws Exception {
            authenticate();
            when(travelCompanionService.connectTraveler(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_876"));

            mockMvc.perform(post(BASE + "/ghost/connect"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_876"));
        }

        @Test
        void returns403WhenBlocked() throws Exception {
            authenticate();
            when(travelCompanionService.connectTraveler(any(), any()))
                    .thenThrow(new ForbiddenException("blocked", "TM_875"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/connect"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_875"));
        }

        @Test
        void returns500OnUnexpectedError() throws Exception {
            authenticate();
            when(travelCompanionService.connectTraveler(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/" + TARGET_ID + "/connect"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
