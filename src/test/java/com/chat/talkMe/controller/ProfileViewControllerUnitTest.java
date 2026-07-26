package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.ProfileViewCountResponse;
import com.chat.talkMe.dto.response.ProfileViewResponse;
import com.chat.talkMe.enums.ProfileViewType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ProfileViewService;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link ProfileViewController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link ProfileViewService} and the real
 * {@link GlobalExceptionHandler}. Only {@link AuthenticationPrincipalArgumentResolver} is
 * registered — every endpoint takes a {@code @PathVariable}/{@code @RequestParam} scalar plus
 * {@code @AuthenticationPrincipal}; none has a {@code @RequestBody}, so neither the tolerant
 * Jackson mapper (no unboxed primitive body fields) nor a {@code Pageable} resolver is needed.
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize("hasRole('USER')")} gate is
 * enforced by Spring's method-security interceptor (AOP), which is NOT active in a standalone
 * MockMvc setup — that role gate is covered by the integration test. Filter-chain authentication
 * (JWT, CSRF) is likewise out of scope. Here we verify the controller's request/response wiring,
 * its lenient {@link ProfileViewType} parsing (invalid values silently fall back to PROFILE), and
 * its delegation to the service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileViewController (unit)")
class ProfileViewControllerUnitTest {

    private static final String BASE = "/profile-views";
    private static final String TARGET = "user-uuid-2";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private ProfileViewService profileViewService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ProfileViewController controller = new ProfileViewController(profileViewService);

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
        testUser.setId(1L);
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

    private static ProfileViewResponse viewer(String id, String username, int viewCount,
                                              String viewType, boolean seen) {
        return ProfileViewResponse.builder()
                .viewer(AuthUserResponse.builder().id(id).username(username).name("Viewer").build())
                .lastViewedAt("2026-07-22T10:15:30Z")
                .viewCount(viewCount)
                .viewType(viewType)
                .seen(seen)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /profile-views/{userId}   (record view)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /profile-views/{userId}")
    class RecordView {

        @Test
        void shouldReturn200AndDefaultToProfileWhenTypeAbsent() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("View recorded"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<ProfileViewType> type = ArgumentCaptor.forClass(ProfileViewType.class);
            verify(profileViewService).recordView(eq(testUser), uuid.capture(), type.capture());
            assertThat(uuid.getValue()).isEqualTo(TARGET);
            // default when the optional param is absent
            assertThat(type.getValue()).isEqualTo(ProfileViewType.PROFILE);
        }

        @Test
        void shouldForwardExplicitProfileType() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET).param("type", "PROFILE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_000"));

            ArgumentCaptor<ProfileViewType> type = ArgumentCaptor.forClass(ProfileViewType.class);
            verify(profileViewService).recordView(eq(testUser), eq(TARGET), type.capture());
            assertThat(type.getValue()).isEqualTo(ProfileViewType.PROFILE);
        }

        @Test
        void shouldForwardProfileImageType() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET).param("type", "PROFILE_IMAGE"))
                    .andExpect(status().isOk());

            ArgumentCaptor<ProfileViewType> type = ArgumentCaptor.forClass(ProfileViewType.class);
            verify(profileViewService).recordView(eq(testUser), eq(TARGET), type.capture());
            assertThat(type.getValue()).isEqualTo(ProfileViewType.PROFILE_IMAGE);
        }

        @Test
        void shouldUppercaseLowercaseTypeBeforeParsing() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            // controller does type.toUpperCase() before valueOf
            mockMvc.perform(post(BASE + "/" + TARGET).param("type", "profile_image"))
                    .andExpect(status().isOk());

            ArgumentCaptor<ProfileViewType> type = ArgumentCaptor.forClass(ProfileViewType.class);
            verify(profileViewService).recordView(eq(testUser), eq(TARGET), type.capture());
            assertThat(type.getValue()).isEqualTo(ProfileViewType.PROFILE_IMAGE);
        }

        @Test
        void shouldFallBackToProfileWhenTypeInvalid() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            // The controller catches the valueOf failure and defaults to PROFILE — it does NOT
            // surface a 400/TM_071. So the request succeeds and the service is still invoked.
            mockMvc.perform(post(BASE + "/" + TARGET).param("type", "BOGUS"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_000"));

            ArgumentCaptor<ProfileViewType> type = ArgumentCaptor.forClass(ProfileViewType.class);
            verify(profileViewService).recordView(eq(testUser), eq(TARGET), type.capture());
            assertThat(type.getValue()).isEqualTo(ProfileViewType.PROFILE);
        }

        @Test
        void shouldFallBackToProfileWhenTypeBlank() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            // Empty string is present (so no default kicks in) but not a valid enum → caught → PROFILE.
            mockMvc.perform(post(BASE + "/" + TARGET).param("type", ""))
                    .andExpect(status().isOk());

            ArgumentCaptor<ProfileViewType> type = ArgumentCaptor.forClass(ProfileViewType.class);
            verify(profileViewService).recordView(eq(testUser), eq(TARGET), type.capture());
            assertThat(type.getValue()).isEqualTo(ProfileViewType.PROFILE);
        }

        @Test
        void shouldForwardUnicodeUuidUnchanged() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            String unicodeId = "用户-😀-2";
            mockMvc.perform(post(BASE + "/{id}", unicodeId))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(profileViewService).recordView(eq(testUser), uuid.capture(), any());
            assertThat(uuid.getValue()).isEqualTo(unicodeId);
        }

        @Test
        void shouldForwardMaliciousUuidUnsanitized() throws Exception {
            authenticate();
            doNothing().when(profileViewService).recordView(any(), any(), any());

            // Documents that there is NO controller-layer sanitization — the raw path segment
            // reaches the service verbatim (defence is the service/persistence layer's job).
            // NB: avoid ';' and '/' in a path segment — ';' starts a servlet matrix parameter and
            // '/' adds a segment; both would corrupt the captured value (URI-parsing artifacts, not
            // controller behavior). This XSS+SQLi payload uses neither.
            String payload = "' OR '1'='1 <img src=x onerror=alert(1)>";
            mockMvc.perform(post(BASE + "/{id}", payload))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
            verify(profileViewService).recordView(eq(testUser), uuid.capture(), any());
            assertThat(uuid.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn404WhenTargetUserNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("User not found", "TM_064"))
                    .when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn403WhenServiceForbids() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Not permitted", "TM_005"))
                    .when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsBadRequest() throws Exception {
            authenticate();
            doThrow(new BadRequestException("Bad view target", "TM_071"))
                    .when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn409WhenServiceConflicts() throws Exception {
            authenticate();
            doThrow(new ConflictException("Duplicate view", "TM_409"))
                    .when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_409"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom"))
                    .when(profileViewService).recordView(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /profile-views   (who viewed me)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /profile-views")
    class GetViewers {

        @Test
        void shouldReturn200WithViewerList() throws Exception {
            authenticate();
            when(profileViewService.getViewers(any()))
                    .thenReturn(List.of(viewer("v-1", "alice", 3, "PROFILE", false)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data[0].viewer.username").value("alice"))
                    .andExpect(jsonPath("$.data[0].viewCount").value(3))
                    .andExpect(jsonPath("$.data[0].viewType").value("PROFILE"))
                    .andExpect(jsonPath("$.data[0].seen").value(false));

            verify(profileViewService).getViewers(testUser);
        }

        @Test
        void shouldReturn200WithSeenViewerTrue() throws Exception {
            authenticate();
            when(profileViewService.getViewers(any()))
                    .thenReturn(List.of(viewer("v-1", "bob", 1, "PROFILE_IMAGE", true)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].seen").value(true))
                    .andExpect(jsonPath("$.data[0].viewType").value("PROFILE_IMAGE"));
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(profileViewService.getViewers(any())).thenReturn(List.of());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(profileViewService).getViewers(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(profileViewService.getViewers(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /profile-views/count   (badge counts)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /profile-views/count")
    class GetCount {

        @Test
        void shouldReturn200WithCounts() throws Exception {
            authenticate();
            when(profileViewService.getCounts(any()))
                    .thenReturn(ProfileViewCountResponse.builder().total(12).unseen(4).build());

            mockMvc.perform(get(BASE + "/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.total").value(12))
                    .andExpect(jsonPath("$.data.unseen").value(4));

            verify(profileViewService).getCounts(testUser);
        }

        @Test
        void shouldReturn200WithZeroCounts() throws Exception {
            authenticate();
            when(profileViewService.getCounts(any()))
                    .thenReturn(ProfileViewCountResponse.builder().total(0).unseen(0).build());

            mockMvc.perform(get(BASE + "/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.unseen").value(0));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(profileViewService.getCounts(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/count"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /profile-views/mark-seen   (clear badge)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /profile-views/mark-seen")
    class MarkSeen {

        @Test
        void shouldReturn200AndMarkAllSeen() throws Exception {
            authenticate();
            doNothing().when(profileViewService).markAllSeen(any());

            mockMvc.perform(post(BASE + "/mark-seen"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Marked seen"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // The literal "/mark-seen" route must win over the "/{userId}" template.
            verify(profileViewService).markAllSeen(testUser);
            verify(profileViewService, never()).recordView(any(), any(), any());
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(profileViewService).markAllSeen(any());

            mockMvc.perform(post(BASE + "/mark-seen"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
