package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.MusicTrackResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.service.MusicService;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link MusicController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link MusicService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize("hasRole('USER')")} gate is
 * enforced by Spring's method-security interceptor (AOP), which is NOT active in a standalone
 * MockMvc setup — that role gate is covered by the integration test. The single
 * {@code GET /music/search} endpoint takes no {@code @AuthenticationPrincipal}, so no
 * authentication is seeded here; this test verifies request/response wiring, param binding
 * (required {@code q} + optional {@code limit} default) and delegation to the service.
 *
 * <p>No {@code @RequestBody} DTO exists on this controller, so no tolerant Jackson converter is
 * needed; no {@code Pageable} argument is used, so no {@code PageableHandlerMethodArgumentResolver}
 * is registered.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MusicController (unit)")
class MusicControllerUnitTest {

    private static final String BASE = "/music/search";
    // Success code read directly from the controller: SuccessResponseDto.success(data) resolves to
    // the inherited ResponseDto.success(T) factory → messageCode "TM_000", message "Success".
    private static final String SUCCESS_CODE = "TM_000";
    private static final int DEFAULT_LIMIT = 24;
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private MusicService musicService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MusicController controller = new MusicController(musicService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static MusicTrackResponse track(String id, String title, String artist) {
        return MusicTrackResponse.builder()
                .id(id).title(title).artist(artist)
                .artworkUrl("https://cdn/art/" + id + ".jpg")
                .previewUrl("https://cdn/preview/" + id + ".m4a")
                .durationSec(30)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /music/search   (authenticated — no @AuthenticationPrincipal)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /music/search")
    class Search {

        @Test
        void shouldReturn200WithTracksAndForwardQueryAndDefaultLimit() throws Exception {
            when(musicService.search(any(), anyInt()))
                    .thenReturn(List.of(track("t1", "Bohemian Rhapsody", "Queen")));

            mockMvc.perform(get(BASE).param("q", "queen"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].id").value("t1"))
                    .andExpect(jsonPath("$.data[0].title").value("Bohemian Rhapsody"))
                    .andExpect(jsonPath("$.data[0].artist").value("Queen"))
                    .andExpect(jsonPath("$.data[0].previewUrl").value("https://cdn/preview/t1.m4a"))
                    .andExpect(jsonPath("$.data[0].durationSec").value(30));

            // Query reaches the service verbatim; limit defaults to 24 when the param is absent.
            ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(musicService).search(query.capture(), limit.capture());
            assertThat(query.getValue()).isEqualTo("queen");
            assertThat(limit.getValue()).isEqualTo(DEFAULT_LIMIT);
        }

        @Test
        void shouldForwardCustomLimitWhenPresent() throws Exception {
            when(musicService.search(any(), anyInt())).thenReturn(List.of(track("t1", "Song", "Artist")));

            mockMvc.perform(get(BASE).param("q", "song").param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(musicService).search(eq("song"), limit.capture());
            assertThat(limit.getValue()).isEqualTo(5);
        }

        @Test
        void shouldReturn200WithEmptyListWhenNoMatches() throws Exception {
            when(musicService.search(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("q", "asdfnomatch"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(musicService).search("asdfnomatch", DEFAULT_LIMIT);
        }

        @Test
        void shouldReturn200WithMultipleTracks() throws Exception {
            when(musicService.search(any(), anyInt())).thenReturn(List.of(
                    track("t1", "One", "A"),
                    track("t2", "Two", "B")));

            mockMvc.perform(get(BASE).param("q", "mix"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value("t1"))
                    .andExpect(jsonPath("$.data[1].id").value("t2"))
                    .andExpect(jsonPath("$.data[1].title").value("Two"));
        }

        // ── Edge: free-form query forwarded unchanged ────────────────────────

        @Test
        void shouldForwardUnicodeAndEmojiQueryUnchanged() throws Exception {
            when(musicService.search(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("q", "名前 😀 café"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
            verify(musicService).search(query.capture(), anyInt());
            assertThat(query.getValue()).isEqualTo("名前 😀 café");
        }

        @Test
        void shouldForwardXssAndSqliQueryUnchangedWithoutSanitizing() throws Exception {
            when(musicService.search(any(), anyInt())).thenReturn(List.of());

            String nasty = "<script>alert(1)</script>'; DROP TABLE tracks;--";
            mockMvc.perform(get(BASE).param("q", nasty))
                    .andExpect(status().isOk());

            // The controller is a thin proxy: it must pass the raw query through untouched.
            ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
            verify(musicService).search(query.capture(), anyInt());
            assertThat(query.getValue()).isEqualTo(nasty);
        }

        @Test
        void shouldForwardBlankQueryWhenPresentButEmpty() throws Exception {
            // `q` is present (satisfies the required param) but empty — no @Valid/@NotBlank on it,
            // so it binds to "" and reaches the service unchanged (service owns any emptiness policy).
            when(musicService.search(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("q", ""))
                    .andExpect(status().isOk());

            verify(musicService).search("", DEFAULT_LIMIT);
        }

        // ── Edge: limit boundary values (no validation → forwarded verbatim) ──

        @Test
        void shouldForwardLimitOfOne() throws Exception {
            when(musicService.search(any(), anyInt())).thenReturn(List.of(track("t1", "Song", "A")));

            mockMvc.perform(get(BASE).param("q", "x").param("limit", "1"))
                    .andExpect(status().isOk());

            verify(musicService).search("x", 1);
        }

        @Test
        void shouldForwardLargeLimitUnbounded() throws Exception {
            // No @Min/@Max on `limit`, so the controller forwards any int the client supplies.
            when(musicService.search(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("q", "x").param("limit", "1000"))
                    .andExpect(status().isOk());

            verify(musicService).search("x", 1000);
        }

        @Test
        void shouldForwardNegativeLimitUnchanged() throws Exception {
            when(musicService.search(any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("q", "x").param("limit", "-3"))
                    .andExpect(status().isOk());

            verify(musicService).search("x", -3);
        }

        // ── Negative: param binding failures (no dedicated handler → catch-all 500) ──

        @Test
        void shouldReturn500WhenQueryParamMissing() throws Exception {
            // Required @RequestParam("q") absent → MissingServletRequestParameterException, which has
            // no dedicated handler → falls through to the catch-all 500 (pins current behaviour).
            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicService);
        }

        @Test
        void shouldReturn500WhenLimitNotNumeric() throws Exception {
            // Type-mismatch on the int `limit` param → MethodArgumentTypeMismatchException, no dedicated
            // handler → catch-all 500 (pins current behaviour).
            mockMvc.perform(get(BASE).param("q", "x").param("limit", "abc"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(musicService);
        }

        // ── Negative: service-thrown exceptions mapped by GlobalExceptionHandler ──

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            when(musicService.search(any(), anyInt())).thenThrow(new RuntimeException("provider down"));

            mockMvc.perform(get(BASE).param("q", "x"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn400WhenServiceRejectsAsBadRequest() throws Exception {
            when(musicService.search(any(), anyInt()))
                    .thenThrow(new BadRequestException("Invalid search parameters", "TM_071"));

            mockMvc.perform(get(BASE).param("q", "x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            when(musicService.search(any(), anyInt()))
                    .thenThrow(new NotFoundException("Catalog unavailable", "TM_004"));

            mockMvc.perform(get(BASE).param("q", "x"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_004"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            when(musicService.search(any(), anyInt()))
                    .thenThrow(new ForbiddenException("Not permitted", "TM_005"));

            mockMvc.perform(get(BASE).param("q", "x"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldMapCustomServiceExceptionStatusAndCode() throws Exception {
            // A failing external provider surfaced as a mapped ServiceException (e.g. 502-style upstream
            // failure modelled here as a generic ServiceException) → status + messageCode passed through.
            when(musicService.search(any(), anyInt()))
                    .thenThrow(new ServiceException(503, "iTunes provider unavailable", "TM_500"));

            mockMvc.perform(get(BASE).param("q", "x"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.messageCode").value("TM_500"));
        }
    }
}
