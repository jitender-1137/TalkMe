package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ReputationResponse;
import com.chat.talkMe.dto.response.ReputationWhyResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ReputationService;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link ReputationController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link ReputationService} and the real
 * {@link GlobalExceptionHandler}. The controller is a thin pass-through: {@code /me},
 * {@code /why} and {@code /prestige} forward the authenticated {@link CustomUserDetails#getUser()}
 * to the service, and {@code /{userUuid}} forwards the raw path string verbatim (it performs
 * <b>no</b> {@code UUID.fromString} guard and <b>no</b> {@code repository.findByUuid} of its own —
 * uuid parsing/lookup happens inside the service, so a malformed uuid surfaces only as whatever
 * the service throws). There are no {@code @RequestBody}, {@code @RequestParam}, {@code @Valid}
 * or {@code Pageable} arguments anywhere, so no tolerant JSON converter, no Pageable resolver,
 * and no bean-validation / missing-param / type-mismatch / malformed-JSON cases apply.
 *
 * <p>Success codes are asserted DIRECTLY from the controller source: {@code /me}, {@code /why}
 * and {@code /{userUuid}} use {@code SuccessResponseDto.success(response)} → {@code TM_000};
 * {@code /prestige} uses {@code success(response, "Prestige successful", "TM_941")} → {@code TM_941}.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("hasRole('USER')")} class gate and the
 * per-method {@code @featureGuard.check('REPUTATION'|'PRESTIGE')} gates are method-security
 * (inactive in standalone MockMvc) — covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReputationController (unit)")
class ReputationControllerUnitTest {

    private static final String BASE = "/reputation";
    private static final String OTHER_UUID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private ReputationService reputationService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ReputationController controller = new ReputationController(reputationService);

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

    // ---- @Builder-only DTO fixtures ------------------------------------------------------------

    private static ReputationResponse rep(int level, String star, int prestige) {
        return ReputationResponse.builder()
                .level(level)
                .starRank(star)
                .prestigeCount(prestige)
                .lifetimePoints(12_345L)
                .pointsIntoLevel(40)
                .pointsForNextLevel(100)
                .progressPercent(40.0)
                .memberSince("2026-01-01")
                .equippedFrame("neon")
                .equippedTitle("Night Owl")
                .build();
    }

    private static ReputationWhyResponse why() {
        return ReputationWhyResponse.builder()
                .contributors(List.of(
                        ReputationWhyResponse.Contributor.builder()
                                .contributorLabel("Lasting friendships").magnitude("HIGH").trend("UP").build(),
                        ReputationWhyResponse.Contributor.builder()
                                .contributorLabel("Reports").magnitude("LOW").trend("DOWN").build()))
                .build();
    }

    // ============================================================================================
    // GET /reputation/me
    // ============================================================================================
    @Nested
    @DisplayName("GET /reputation/me")
    class GetMine {

        @Test
        void shouldReturnCallerSnapshot() throws Exception {
            when(reputationService.getMine(any())).thenReturn(rep(7, "SILVER", 1));

            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.level").value(7))
                    .andExpect(jsonPath("$.data.starRank").value("SILVER"))
                    .andExpect(jsonPath("$.data.prestigeCount").value(1))
                    .andExpect(jsonPath("$.data.lifetimePoints").value(12345))
                    .andExpect(jsonPath("$.data.progressPercent").value(40.0))
                    .andExpect(jsonPath("$.data.memberSince").value("2026-01-01"))
                    .andExpect(jsonPath("$.data.equippedFrame").value("neon"))
                    .andExpect(jsonPath("$.data.equippedTitle").value("Night Owl"));

            // The AUTHENTICATED user (not any other) reaches the service.
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(reputationService).getMine(userCaptor.capture());
            assertThat(userCaptor.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldReturnLevelOneBronzeFloor() throws Exception {
            when(reputationService.getMine(any())).thenReturn(rep(1, "BRONZE", 0));

            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.level").value(1))
                    .andExpect(jsonPath("$.data.starRank").value("BRONZE"))
                    .andExpect(jsonPath("$.data.prestigeCount").value(0));
        }

        @Test
        void shouldMap500WhenServiceThrowsRuntime() throws Exception {
            when(reputationService.getMine(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        void shouldMapServiceExceptionStatusAndCode() throws Exception {
            when(reputationService.getMine(any()))
                    .thenThrow(new BadRequestException("bad", "TM_400"));

            mockMvc.perform(get(BASE + "/me"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }
    }

    // ============================================================================================
    // GET /reputation/why
    // ============================================================================================
    @Nested
    @DisplayName("GET /reputation/why")
    class Why {

        @Test
        void shouldReturnContributorLabelsAndBuckets() throws Exception {
            when(reputationService.why(any())).thenReturn(why());

            mockMvc.perform(get(BASE + "/why"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.contributors.length()").value(2))
                    .andExpect(jsonPath("$.data.contributors[0].contributorLabel").value("Lasting friendships"))
                    .andExpect(jsonPath("$.data.contributors[0].magnitude").value("HIGH"))
                    .andExpect(jsonPath("$.data.contributors[0].trend").value("UP"))
                    .andExpect(jsonPath("$.data.contributors[1].magnitude").value("LOW"))
                    .andExpect(jsonPath("$.data.contributors[1].trend").value("DOWN"));

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(reputationService).why(userCaptor.capture());
            assertThat(userCaptor.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldReturnEmptyContributorList() throws Exception {
            when(reputationService.why(any()))
                    .thenReturn(ReputationWhyResponse.builder().contributors(List.of()).build());

            mockMvc.perform(get(BASE + "/why"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.contributors.length()").value(0));
        }

        @Test
        void shouldMap500WhenServiceThrowsRuntime() throws Exception {
            when(reputationService.why(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/why"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }

    // ============================================================================================
    // GET /reputation/{userUuid}
    // ============================================================================================
    @Nested
    @DisplayName("GET /reputation/{userUuid}")
    class GetFor {

        @Test
        void shouldReturnOtherUserSnapshotAndPassRawUuid() throws Exception {
            when(reputationService.getFor(any())).thenReturn(rep(42, "GOLD", 3));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.level").value(42))
                    .andExpect(jsonPath("$.data.starRank").value("GOLD"))
                    .andExpect(jsonPath("$.data.prestigeCount").value(3));

            // The raw path string is forwarded verbatim; the controller does not parse it.
            ArgumentCaptor<String> uuidCaptor = ArgumentCaptor.forClass(String.class);
            verify(reputationService).getFor(uuidCaptor.capture());
            assertThat(uuidCaptor.getValue()).isEqualTo(OTHER_UUID);
        }

        @Test
        void shouldMap404WhenServiceReportsNotFound() throws Exception {
            when(reputationService.getFor(any()))
                    .thenThrow(new NotFoundException("no user", "TM_024"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));
        }

        @Test
        void shouldMap403WhenServiceReportsForbidden() throws Exception {
            when(reputationService.getFor(any()))
                    .thenThrow(new ForbiddenException("nope", "TM_103"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldMap403WhenSpringAccessDenied() throws Exception {
            when(reputationService.getFor(any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldMap400WhenServiceReportsBadRequest() throws Exception {
            // The service parses the uuid; an unparsable one surfaces as its BadRequestException.
            when(reputationService.getFor(any()))
                    .thenThrow(new BadRequestException("Invalid user id", "TM_400"));

            mockMvc.perform(get(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldMap400InvalidUuidWhenServiceThrowsRawIllegalArgument() throws Exception {
            // If the service re-throws the raw UUID.fromString message, the handler special-cases it.
            when(reputationService.getFor(any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: nope"));

            mockMvc.perform(get(BASE + "/nope"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));
        }

        @Test
        void shouldMap400Tm071WhenServiceThrowsGenericIllegalArgument() throws Exception {
            when(reputationService.getFor(any()))
                    .thenThrow(new IllegalArgumentException("something else"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldMap500WhenServiceThrowsRuntime() throws Exception {
            when(reputationService.getFor(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        void shouldForwardUnicodeAndEmojiPathVerbatim() throws Exception {
            String weird = "uñicöde-😀-你好";
            when(reputationService.getFor(any())).thenReturn(rep(1, "BRONZE", 0));

            mockMvc.perform(get(BASE + "/" + weird))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuidCaptor = ArgumentCaptor.forClass(String.class);
            verify(reputationService).getFor(uuidCaptor.capture());
            assertThat(uuidCaptor.getValue()).isEqualTo(weird);
        }

        @Test
        void shouldForwardXssAndSqliPathVerbatimWithoutInterpreting() throws Exception {
            // The path segment is the only free-form String input; assert pass-through (no sanitising
            // and no interpretation by the controller). Kept free of '/' (path separator), ';'
            // (Spring strips matrix-variable content by default) and spaces so the captured value
            // round-trips byte-for-byte.
            String payload = "<img>'OR'1'='1'--"; // no '/', ';' or space so the path segment round-trips
            when(reputationService.getFor(any())).thenReturn(rep(1, "BRONZE", 0));

            mockMvc.perform(get(BASE + "/" + payload))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuidCaptor = ArgumentCaptor.forClass(String.class);
            verify(reputationService).getFor(uuidCaptor.capture());
            assertThat(uuidCaptor.getValue()).isEqualTo(payload);
        }
    }

    // ============================================================================================
    // POST /reputation/prestige
    // ============================================================================================
    @Nested
    @DisplayName("POST /reputation/prestige")
    class Prestige {

        @Test
        void shouldPrestigeCallerAndReturnTm941() throws Exception {
            when(reputationService.prestige(any())).thenReturn(rep(1, "BRONZE", 4));

            mockMvc.perform(post(BASE + "/prestige"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // Distinct success code taken directly from the controller.
                    .andExpect(jsonPath("$.messageCode").value("TM_941"))
                    .andExpect(jsonPath("$.data.level").value(1))
                    .andExpect(jsonPath("$.data.prestigeCount").value(4));

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(reputationService).prestige(userCaptor.capture());
            assertThat(userCaptor.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldMap400WhenBelowPrestigeThreshold() throws Exception {
            // Impl throws BadRequestException("Prestige requires reaching level 100", "TM_940").
            when(reputationService.prestige(any()))
                    .thenThrow(new BadRequestException("Prestige requires reaching level 100", "TM_940"));

            mockMvc.perform(post(BASE + "/prestige"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_940"));
        }

        @Test
        void shouldMap500WhenServiceThrowsRuntime() throws Exception {
            when(reputationService.prestige(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/prestige"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        void shouldNotTouchOtherServiceReadPaths() throws Exception {
            when(reputationService.prestige(any())).thenReturn(rep(1, "BRONZE", 1));

            mockMvc.perform(post(BASE + "/prestige"))
                    .andExpect(status().isOk());

            verify(reputationService).prestige(any());
            // getMine/why/getFor are unrelated read paths — never hit by a prestige POST.
            org.mockito.Mockito.verifyNoMoreInteractions(reputationService);
        }
    }
}
