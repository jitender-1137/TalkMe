package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateEventRequest;
import com.chat.talkMe.dto.response.EventResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.EventService;
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
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
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
 * Pure controller unit test for {@link EventController} (Midnight Events, feature #24).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link EventService} and the real
 * {@link GlobalExceptionHandler}. A tolerant Jackson-3 mapper (FAIL_ON_NULL_FOR_PRIMITIVES
 * disabled) is wired because {@link CreateEventRequest#getMaxAttendees()} is an unboxed
 * {@code int} — matching the app's {@code @Primary} ObjectMapper so an absent/null
 * {@code maxAttendees} defaults to 0 rather than 500-ing during deserialization.
 *
 * <p><b>Scope boundary:</b> every route is gated by
 * {@code @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")}. That is enforced by Spring's
 * method-security interceptor (AOP), which is NOT active in a standalone MockMvc setup — the
 * entitlement gate is covered by the integration test. Here we verify request/response wiring
 * and delegation to the service, which owns the host/RSVP/state authorization checks.
 *
 * <p><b>Not applicable here:</b> this controller declares no {@code @RequestParam} and no numeric
 * path/query params (the {@code uuid} path var is a String), so the "missing required param → 500"
 * and "numeric type-mismatch → 500" edge cases have no surface to exercise. The controller also
 * never surfaces a 409 — "event full" / "no longer accepting RSVPs" are BadRequestException (400).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventController (unit)")
class EventControllerUnitTest {

    private static final String BASE = "/events";
    private static final String EVENT_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private EventService eventService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        EventController controller = new EventController(eventService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // CreateEventRequest.maxAttendees is an unboxed primitive int; align the standalone
        // Jackson-3 mapper with the app's @Primary mapper so an absent/null value defaults to 0.
        JsonMapper jsonMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
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

    private static EventResponse event() {
        return event(EVENT_UUID, "SCHEDULED", null);
    }

    private static EventResponse event(String uuid, String eventStatus, String myRsvp) {
        return EventResponse.builder()
                .eventUuid(uuid)
                .title("Midnight Jam")
                .description("Late-night vibes")
                .startAt(Instant.parse("2030-01-01T00:00:00Z"))
                .category("music")
                .status(eventStatus)
                .maxAttendees(50)
                .hostUuid("host-uuid")
                .hostName("Host")
                .hostUsername("host")
                .hostedByMe(true)
                .goingCount(3)
                .interestedCount(2)
                .myRsvp(myRsvp)
                .attended(false)
                .build();
    }

    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /events  (create)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /events")
    class CreateEvent {

        @Test
        void shouldReturn200AndForwardRequestAndUser() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Midnight Jam\",\"description\":\"Late-night vibes\","
                                    + "\"startAt\":\"2030-01-01T00:00:00Z\",\"category\":\"music\","
                                    + "\"maxAttendees\":50}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_952"))
                    .andExpect(jsonPath("$.message").value("Event scheduled"))
                    .andExpect(jsonPath("$.data.eventUuid").value(EVENT_UUID))
                    .andExpect(jsonPath("$.data.title").value("Midnight Jam"))
                    .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.data.maxAttendees").value(50))
                    .andExpect(jsonPath("$.data.goingCount").value(3))
                    .andExpect(jsonPath("$.data.hostedByMe").value(true));

            ArgumentCaptor<CreateEventRequest> req = ArgumentCaptor.forClass(CreateEventRequest.class);
            verify(eventService).createEvent(req.capture(), eq(testUser));
            assertThat(req.getValue().getTitle()).isEqualTo("Midnight Jam");
            assertThat(req.getValue().getDescription()).isEqualTo("Late-night vibes");
            assertThat(req.getValue().getStartAt()).isEqualTo(FUTURE);
            assertThat(req.getValue().getCategory()).isEqualTo("music");
            assertThat(req.getValue().getMaxAttendees()).isEqualTo(50);
        }

        @Test
        void shouldDefaultOptionalFieldsWhenAbsent() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());

            // Only the required fields present: description/endAt/category null, maxAttendees → 0.
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Minimal\",\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<CreateEventRequest> req = ArgumentCaptor.forClass(CreateEventRequest.class);
            verify(eventService).createEvent(req.capture(), eq(testUser));
            assertThat(req.getValue().getTitle()).isEqualTo("Minimal");
            assertThat(req.getValue().getDescription()).isNull();
            assertThat(req.getValue().getEndAt()).isNull();
            assertThat(req.getValue().getCategory()).isNull();
            assertThat(req.getValue().getMaxAttendees()).isZero();
        }

        @Test
        void shouldDefaultMaxAttendeesToZeroWhenExplicitlyNull() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());

            // Explicit null on the unboxed primitive — tolerated by the aligned mapper → 0.
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"NullSeats\",\"startAt\":\"2030-01-01T00:00:00Z\","
                                    + "\"maxAttendees\":null}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<CreateEventRequest> req = ArgumentCaptor.forClass(CreateEventRequest.class);
            verify(eventService).createEvent(req.capture(), eq(testUser));
            assertThat(req.getValue().getMaxAttendees()).isZero();
        }

        @Test
        void shouldForwardEndAtWhenPresent() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Windowed\",\"startAt\":\"2030-01-01T00:00:00Z\","
                                    + "\"endAt\":\"2030-01-01T02:00:00Z\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<CreateEventRequest> req = ArgumentCaptor.forClass(CreateEventRequest.class);
            verify(eventService).createEvent(req.capture(), eq(testUser));
            assertThat(req.getValue().getEndAt()).isEqualTo(Instant.parse("2030-01-01T02:00:00Z"));
        }

        // ── Validation: title ──────────────────────────────────────────────────

        @Test
        void shouldReturn400WhenTitleBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"   \",\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn400WhenTitleMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn400WhenTitleTooLong() throws Exception {
            authenticate();
            String longTitle = "a".repeat(141); // @Size(max = 140)
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"" + longTitle + "\",\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldAcceptTitleAtMaxLengthBoundary() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());
            String boundaryTitle = "a".repeat(140); // exactly at @Size(max = 140)
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"" + boundaryTitle + "\",\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isOk());
            verify(eventService).createEvent(any(), eq(testUser));
        }

        @Test
        void shouldReturn400WhenDescriptionTooLong() throws Exception {
            authenticate();
            String longDesc = "d".repeat(2001); // @Size(max = 2000)
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"description\":\"" + longDesc + "\","
                                    + "\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn400WhenCategoryTooLong() throws Exception {
            authenticate();
            String longCat = "c".repeat(61); // @Size(max = 60)
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"startAt\":\"2030-01-01T00:00:00Z\","
                                    + "\"category\":\"" + longCat + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        // ── Validation: startAt ────────────────────────────────────────────────

        @Test
        void shouldReturn400WhenStartAtNull() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"startAt\":null}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn400WhenStartAtMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn500WhenStartAtHasInvalidFormat() throws Exception {
            authenticate();
            // An unparseable Instant string fails during Jackson deserialization
            // (HttpMessageNotReadableException) BEFORE @Valid runs; there is no dedicated handler,
            // so it falls through to the catch-all → 500/TM_002 (NOT a 400 bean-validation error).
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"startAt\":\"not-a-timestamp\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(eventService);
        }

        // ── Validation: maxAttendees ───────────────────────────────────────────

        @Test
        void shouldReturn400WhenMaxAttendeesNegative() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"startAt\":\"2030-01-01T00:00:00Z\","
                                    + "\"maxAttendees\":-1}")) // @PositiveOrZero
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldAcceptMaxAttendeesZeroBoundary() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"startAt\":\"2030-01-01T00:00:00Z\","
                                    + "\"maxAttendees\":0}")) // 0 is allowed by @PositiveOrZero
                    .andExpect(status().isOk());
            verify(eventService).createEvent(any(), eq(testUser));
        }

        // ── Malformed body / service faults ────────────────────────────────────

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Broken JSON → HttpMessageNotReadableException → catch-all 500 (no dedicated handler).
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn400WhenServiceRejectsPastStartTime() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any()))
                    .thenThrow(new BadRequestException("Event start time must be in the future", "TM_957"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_957"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"ok\",\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        // ── Free-form String pass-through (unicode / injection) ─────────────────

        @Test
        void shouldForwardUnicodeAndEmojiTitleVerbatim() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());
            String title = "深夜ジャム 🌙🎶 café"; // 🌙🎶

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"" + title + "\",\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<CreateEventRequest> req = ArgumentCaptor.forClass(CreateEventRequest.class);
            verify(eventService).createEvent(req.capture(), eq(testUser));
            assertThat(req.getValue().getTitle()).isEqualTo(title);
        }

        @Test
        void shouldPassThroughXssAndSqliPayloadInTitleWithoutSanitizing() throws Exception {
            authenticate();
            when(eventService.createEvent(any(), any())).thenReturn(event());
            // Controller must not sanitize/escape — it forwards the raw String to the service.
            String title = "<script>alert(1)</script>'; DROP TABLE events;--";

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"<script>alert(1)</script>'; DROP TABLE events;--\","
                                    + "\"startAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<CreateEventRequest> req = ArgumentCaptor.forClass(CreateEventRequest.class);
            verify(eventService).createEvent(req.capture(), eq(testUser));
            assertThat(req.getValue().getTitle()).isEqualTo(title);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /events/upcoming
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /events/upcoming")
    class Upcoming {

        @Test
        void shouldReturn200WithListAndForwardViewer() throws Exception {
            authenticate();
            when(eventService.listUpcoming(any()))
                    .thenReturn(List.of(event(EVENT_UUID, "SCHEDULED", "GOING"),
                            event("22222222-2222-2222-2222-222222222222", "SCHEDULED", null)));

            mockMvc.perform(get(BASE + "/upcoming"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].eventUuid").value(EVENT_UUID))
                    .andExpect(jsonPath("$.data[0].myRsvp").value("GOING"));

            verify(eventService).listUpcoming(testUser);
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(eventService.listUpcoming(any())).thenReturn(List.of());
            mockMvc.perform(get(BASE + "/upcoming"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
            verify(eventService).listUpcoming(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(eventService.listUpcoming(any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/upcoming"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /events/{uuid}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /events/{uuid}")
    class GetEvent {

        @Test
        void shouldReturn200AndForwardUuidAndViewer() throws Exception {
            authenticate();
            when(eventService.getEvent(eq(EVENT_UUID), any())).thenReturn(event(EVENT_UUID, "LIVE", "INTERESTED"));

            mockMvc.perform(get(BASE + "/" + EVENT_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.eventUuid").value(EVENT_UUID))
                    .andExpect(jsonPath("$.data.status").value("LIVE"))
                    .andExpect(jsonPath("$.data.myRsvp").value("INTERESTED"));

            verify(eventService).getEvent(EVENT_UUID, testUser);
        }

        @Test
        void shouldReturn404WhenEventNotFound() throws Exception {
            authenticate();
            when(eventService.getEvent(any(), any()))
                    .thenThrow(new NotFoundException("Event not found", "TM_955"));
            mockMvc.perform(get(BASE + "/" + EVENT_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_955"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(eventService.getEvent(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/" + EVENT_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /events/{uuid}/rsvp
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /events/{uuid}/rsvp")
    class Rsvp {

        @Test
        void shouldReturn200AndForwardUserUuidAndStatusForGoing() throws Exception {
            authenticate();
            when(eventService.rsvp(any(), any(), any())).thenReturn(event(EVENT_UUID, "SCHEDULED", "GOING"));

            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"GOING\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_953"))
                    .andExpect(jsonPath("$.message").value("RSVP updated"))
                    .andExpect(jsonPath("$.data.myRsvp").value("GOING"));

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> statusCap = ArgumentCaptor.forClass(String.class);
            verify(eventService).rsvp(eq(testUser), uuid.capture(), statusCap.capture());
            assertThat(uuid.getValue()).isEqualTo(EVENT_UUID);
            assertThat(statusCap.getValue()).isEqualTo("GOING");
        }

        @Test
        void shouldToggleRsvpToDeclined() throws Exception {
            authenticate();
            when(eventService.rsvp(any(), any(), any())).thenReturn(event(EVENT_UUID, "SCHEDULED", "DECLINED"));

            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"DECLINED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myRsvp").value("DECLINED"));

            verify(eventService).rsvp(eq(testUser), eq(EVENT_UUID), eq("DECLINED"));
        }

        @Test
        void shouldReturn400WhenStatusBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"  \"}")) // @NotBlank
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn400WhenStatusMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn400WhenStatusIsInvalidEnumValue() throws Exception {
            authenticate();
            // "MAYBE" passes @NotBlank, reaches the service which does RsvpStatus.valueOf and
            // rethrows as BadRequestException(TM_960) — a 400, NOT the generic TM_071.
            when(eventService.rsvp(any(), any(), any()))
                    .thenThrow(new BadRequestException("status must be GOING, INTERESTED or DECLINED", "TM_960"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"MAYBE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_960"));
            verify(eventService).rsvp(eq(testUser), eq(EVENT_UUID), eq("MAYBE"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(eventService);
        }

        @Test
        void shouldReturn404WhenEventNotFound() throws Exception {
            authenticate();
            when(eventService.rsvp(any(), any(), any()))
                    .thenThrow(new NotFoundException("Event not found", "TM_955"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"GOING\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_955"));
        }

        @Test
        void shouldReturn400WhenEventFull() throws Exception {
            authenticate();
            // Seat cap reached — surfaced as BadRequestException(TM_959), i.e. 400 (this controller
            // has no 409 path).
            when(eventService.rsvp(any(), any(), any()))
                    .thenThrow(new BadRequestException("This event is full", "TM_959"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"GOING\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_959"));
        }

        @Test
        void shouldReturn400WhenEventNoLongerAcceptingRsvps() throws Exception {
            authenticate();
            when(eventService.rsvp(any(), any(), any()))
                    .thenThrow(new BadRequestException("This event is no longer accepting RSVPs", "TM_958"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"GOING\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_958"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(eventService.rsvp(any(), any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/rsvp").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"GOING\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /events/{uuid}/cancel
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /events/{uuid}/cancel")
    class Cancel {

        @Test
        void shouldReturn200AndForwardUuidAndHostAndNotRsvp() throws Exception {
            authenticate();
            when(eventService.cancelEvent(eq(EVENT_UUID), any())).thenReturn(event(EVENT_UUID, "CANCELLED", null));

            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_954"))
                    .andExpect(jsonPath("$.message").value("Event cancelled"))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));

            verify(eventService).cancelEvent(EVENT_UUID, testUser);
            verify(eventService, never()).rsvp(any(), any(), any());
        }

        @Test
        void shouldReturn403WhenNotHost() throws Exception {
            authenticate();
            when(eventService.cancelEvent(any(), any()))
                    .thenThrow(new ForbiddenException("Only the host can cancel this event", "TM_956"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/cancel"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_956"));
        }

        @Test
        void shouldReturn404WhenEventNotFound() throws Exception {
            authenticate();
            when(eventService.cancelEvent(any(), any()))
                    .thenThrow(new NotFoundException("Event not found", "TM_955"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/cancel"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_955"));
        }

        @Test
        void shouldReturn400WhenEventCanNoLongerBeCancelled() throws Exception {
            authenticate();
            when(eventService.cancelEvent(any(), any()))
                    .thenThrow(new BadRequestException("This event can no longer be cancelled", "TM_961"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/cancel"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_961"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(eventService.cancelEvent(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE + "/" + EVENT_UUID + "/cancel"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
