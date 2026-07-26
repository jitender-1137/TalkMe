package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CityDistrictDetailResponse;
import com.chat.talkMe.dto.response.CityDistrictResponse;
import com.chat.talkMe.exception.FeatureLockedException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.CityService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link CityController} — the Virtual Night City (feature #25)
 * endpoints: {@code GET /city}, {@code GET /city/{slug}}, {@code POST /city/{slug}/enter},
 * {@code POST /city/{slug}/leave}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link CityService} and the real
 * {@link GlobalExceptionHandler} (backed by an empty {@link StaticMessageSource}, so message
 * codes fall through to the handler's default strings and the {@code messageCode} field is the
 * stable assertion target). A {@link LocalValidatorFactoryBean} and the
 * {@link AuthenticationPrincipalArgumentResolver} are wired so {@code @AuthenticationPrincipal
 * CustomUserDetails} is resolved from the {@link SecurityContextHolder}.
 *
 * <p>There is no {@code @RequestBody} and no {@link org.springframework.data.domain.Pageable} on
 * any endpoint — every parameter is a {@code @PathVariable} or the injected principal — so no
 * tolerant Jackson converter and no {@code PageableHandlerMethodArgumentResolver} are needed.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('VIRTUAL_CITY')")} gates
 * on read/enter and the {@code @PreAuthorize("hasRole('USER')")} gate on leave are Spring
 * method-security (AOP) checks that are NOT active in a standalone MockMvc setup — a locked or
 * unauthenticated caller would be rejected before the controller in production. Those gates are
 * covered by the integration test. Here we still exercise the "service throws
 * {@link FeatureLockedException}" path, which the {@link GlobalExceptionHandler} maps to
 * 403 / {@code TM_FEATURE_LOCKED} regardless of the (inactive) annotation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CityController (unit)")
class CityControllerUnitTest {

    private static final String BASE = "/city";

    @Mock
    private CityService cityService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        CityController controller = new CityController(cityService);

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

    // ── Fixtures ─────────────────────────────────────────────────────────────────

    private static CityDistrictResponse card(String slug) {
        return CityDistrictResponse.builder()
                .slug(slug)
                .label("Neon Alley")
                .emoji("🌃")
                .tagline("Where the night never sleeps")
                .liveCount(3)
                .roomCount(2)
                .build();
    }

    private static CityDistrictDetailResponse detail(String slug) {
        return CityDistrictDetailResponse.builder()
                .district(card(slug))
                .rooms(List.of())
                .onlineUsernames(List.of("alice", "bob"))
                .build();
    }

    // ── GET /city ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /city (districts)")
    class Districts {

        @Test
        @DisplayName("returns 200/TM_000 with the full district list")
        void shouldReturnAllDistricts() throws Exception {
            when(cityService.listDistricts()).thenReturn(List.of(card("neon-alley"), card("rooftop")));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].slug").value("neon-alley"))
                    .andExpect(jsonPath("$.data[0].liveCount").value(3))
                    .andExpect(jsonPath("$.data[0].roomCount").value(2))
                    .andExpect(jsonPath("$.data[1].slug").value("rooftop"));

            verify(cityService).listDistricts();
        }

        @Test
        @DisplayName("returns 200 with an empty array when no districts exist")
        void shouldReturnEmptyList() throws Exception {
            when(cityService.listDistricts()).thenReturn(List.of());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("maps a service RuntimeException to 500/TM_002")
        void shouldReturn500WhenServiceThrowsRuntime() throws Exception {
            when(cityService.listDistricts()).thenThrow(new RuntimeException("redis down hard"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        @DisplayName("maps a FeatureLockedException to 403/TM_FEATURE_LOCKED")
        void shouldReturn403WhenFeatureLocked() throws Exception {
            when(cityService.listDistricts()).thenThrow(new FeatureLockedException());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_FEATURE_LOCKED"));
        }
    }

    // ── GET /city/{slug} ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /city/{slug} (district detail)")
    class District {

        @Test
        @DisplayName("returns 200/TM_000 with the district detail")
        void shouldReturnDistrictDetail() throws Exception {
            when(cityService.getDistrict(eq("neon-alley"), any(User.class)))
                    .thenReturn(detail("neon-alley"));

            mockMvc.perform(get(BASE + "/neon-alley"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.district.slug").value("neon-alley"))
                    .andExpect(jsonPath("$.data.onlineUsernames.length()").value(2))
                    .andExpect(jsonPath("$.data.onlineUsernames[0]").value("alice"))
                    .andExpect(jsonPath("$.data.rooms").isArray());
        }

        @Test
        @DisplayName("forwards the path slug and the authenticated user to the service (getDistrict(slug, user))")
        void shouldForwardSlugAndUser() throws Exception {
            when(cityService.getDistrict(anyString(), any(User.class))).thenReturn(detail("rooftop"));

            mockMvc.perform(get(BASE + "/rooftop")).andExpect(status().isOk());

            ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(cityService).getDistrict(slug.capture(), user.capture());
            assertThat(slug.getValue()).isEqualTo("rooftop");
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(user.getValue().getUsername()).isEqualTo("me");
        }

        @Test
        @DisplayName("supports a detail with an empty rooms list and empty roster")
        void shouldReturnEmptyRoomsAndRoster() throws Exception {
            CityDistrictDetailResponse empty = CityDistrictDetailResponse.builder()
                    .district(card("quiet-quarter"))
                    .rooms(List.of())
                    .onlineUsernames(List.of())
                    .build();
            when(cityService.getDistrict(eq("quiet-quarter"), any(User.class))).thenReturn(empty);

            mockMvc.perform(get(BASE + "/quiet-quarter"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.rooms.length()").value(0))
                    .andExpect(jsonPath("$.data.onlineUsernames.length()").value(0));
        }

        @Test
        @DisplayName("maps an unknown slug (NotFoundException) to 404/TM_970")
        void shouldReturn404ForUnknownSlug() throws Exception {
            when(cityService.getDistrict(eq("does-not-exist"), any(User.class)))
                    .thenThrow(new NotFoundException("Unknown city district: does-not-exist", "TM_970"));

            mockMvc.perform(get(BASE + "/does-not-exist"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_970"));
        }

        @Test
        @DisplayName("maps a ForbiddenException to 403/TM_103")
        void shouldReturn403WhenForbidden() throws Exception {
            when(cityService.getDistrict(anyString(), any(User.class)))
                    .thenThrow(new ForbiddenException("nope"));

            mockMvc.perform(get(BASE + "/neon-alley"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        @DisplayName("maps a FeatureLockedException to 403/TM_FEATURE_LOCKED")
        void shouldReturn403WhenFeatureLocked() throws Exception {
            when(cityService.getDistrict(anyString(), any(User.class)))
                    .thenThrow(new FeatureLockedException());

            mockMvc.perform(get(BASE + "/neon-alley"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_FEATURE_LOCKED"));
        }

        @Test
        @DisplayName("maps a service RuntimeException to 500/TM_002")
        void shouldReturn500WhenServiceThrowsRuntime() throws Exception {
            when(cityService.getDistrict(anyString(), any(User.class)))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/neon-alley"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        @DisplayName("forwards a unicode/emoji slug verbatim to the service")
        void shouldForwardUnicodeSlug() throws Exception {
            String unicodeSlug = "野外-🌃"; // 野外-🌃
            when(cityService.getDistrict(anyString(), any(User.class)))
                    .thenReturn(detail(unicodeSlug));

            mockMvc.perform(get(BASE + "/" + unicodeSlug).characterEncoding("UTF-8"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
            verify(cityService).getDistrict(slug.capture(), any(User.class));
            assertThat(slug.getValue()).isEqualTo(unicodeSlug);
        }
    }

    // ── POST /city/{slug}/enter ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /city/{slug}/enter")
    class Enter {

        @Test
        @DisplayName("returns 200/TM_971 (\"Entered district\") with the district detail")
        void shouldEnterDistrict() throws Exception {
            when(cityService.enterDistrict(any(User.class), eq("neon-alley")))
                    .thenReturn(detail("neon-alley"));

            mockMvc.perform(post(BASE + "/neon-alley/enter"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_971"))
                    .andExpect(jsonPath("$.message").value("Entered district"))
                    .andExpect(jsonPath("$.data.district.slug").value("neon-alley"))
                    .andExpect(jsonPath("$.data.onlineUsernames").isArray());
        }

        @Test
        @DisplayName("forwards the authenticated user and path slug to the service (enterDistrict(user, slug))")
        void shouldForwardUserAndSlug() throws Exception {
            when(cityService.enterDistrict(any(User.class), anyString())).thenReturn(detail("rooftop"));

            mockMvc.perform(post(BASE + "/rooftop/enter")).andExpect(status().isOk());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
            verify(cityService).enterDistrict(user.capture(), slug.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(slug.getValue()).isEqualTo("rooftop");
        }

        @Test
        @DisplayName("maps an unknown slug (NotFoundException) to 404/TM_970")
        void shouldReturn404ForUnknownSlug() throws Exception {
            when(cityService.enterDistrict(any(User.class), eq("ghost-town")))
                    .thenThrow(new NotFoundException("Unknown city district: ghost-town", "TM_970"));

            mockMvc.perform(post(BASE + "/ghost-town/enter"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_970"));
        }

        @Test
        @DisplayName("maps a FeatureLockedException to 403/TM_FEATURE_LOCKED")
        void shouldReturn403WhenFeatureLocked() throws Exception {
            when(cityService.enterDistrict(any(User.class), anyString()))
                    .thenThrow(new FeatureLockedException());

            mockMvc.perform(post(BASE + "/neon-alley/enter"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_FEATURE_LOCKED"));
        }

        @Test
        @DisplayName("maps a service RuntimeException to 500/TM_002")
        void shouldReturn500WhenServiceThrowsRuntime() throws Exception {
            when(cityService.enterDistrict(any(User.class), anyString()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/neon-alley/enter"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        @DisplayName("forwards a unicode/emoji slug verbatim to the service")
        void shouldForwardUnicodeSlug() throws Exception {
            String unicodeSlug = "🌆-harbor"; // 🌆-harbor
            when(cityService.enterDistrict(any(User.class), anyString())).thenReturn(detail(unicodeSlug));

            mockMvc.perform(post(BASE + "/" + unicodeSlug + "/enter").characterEncoding("UTF-8"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
            verify(cityService).enterDistrict(any(User.class), slug.capture());
            assertThat(slug.getValue()).isEqualTo(unicodeSlug);
        }
    }

    // ── POST /city/{slug}/leave ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /city/{slug}/leave")
    class Leave {

        @Test
        @DisplayName("returns 200/TM_972 (\"Left district\") with a null data payload")
        void shouldLeaveDistrict() throws Exception {
            doNothing().when(cityService).leaveDistrict(any(User.class), eq("neon-alley"));

            mockMvc.perform(post(BASE + "/neon-alley/leave"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_972"))
                    .andExpect(jsonPath("$.message").value("Left district"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("forwards the authenticated user and path slug to the service (leaveDistrict(user, slug))")
        void shouldForwardUserAndSlug() throws Exception {
            doNothing().when(cityService).leaveDistrict(any(User.class), anyString());

            mockMvc.perform(post(BASE + "/rooftop/leave")).andExpect(status().isOk());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
            verify(cityService).leaveDistrict(user.capture(), slug.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(slug.getValue()).isEqualTo("rooftop");
        }

        @Test
        @DisplayName("maps an unknown slug (NotFoundException) to 404/TM_970")
        void shouldReturn404ForUnknownSlug() throws Exception {
            doThrow(new NotFoundException("Unknown city district: nowhere", "TM_970"))
                    .when(cityService).leaveDistrict(any(User.class), eq("nowhere"));

            mockMvc.perform(post(BASE + "/nowhere/leave"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_970"));
        }

        @Test
        @DisplayName("maps a service RuntimeException to 500/TM_002")
        void shouldReturn500WhenServiceThrowsRuntime() throws Exception {
            doThrow(new RuntimeException("boom"))
                    .when(cityService).leaveDistrict(any(User.class), anyString());

            mockMvc.perform(post(BASE + "/neon-alley/leave"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        @DisplayName("forwards a unicode/emoji slug verbatim to the service")
        void shouldForwardUnicodeSlug() throws Exception {
            String unicodeSlug = "夜店-🍺"; // 夜店-🍺
            doNothing().when(cityService).leaveDistrict(any(User.class), anyString());

            mockMvc.perform(post(BASE + "/" + unicodeSlug + "/leave").characterEncoding("UTF-8"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
            verify(cityService).leaveDistrict(any(User.class), slug.capture());
            assertThat(slug.getValue()).isEqualTo(unicodeSlug);
        }

        @Test
        @DisplayName("does not invoke enter/read paths on a leave")
        void shouldOnlyCallLeave() throws Exception {
            doNothing().when(cityService).leaveDistrict(any(User.class), anyString());

            mockMvc.perform(post(BASE + "/neon-alley/leave")).andExpect(status().isOk());

            verify(cityService).leaveDistrict(any(User.class), anyString());
            verify(cityService, never()).enterDistrict(any(), anyString());
            verify(cityService, never()).getDistrict(anyString(), any());
        }
    }
}
