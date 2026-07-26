package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.DiscoverProfileResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.DiscoverService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link DiscoverController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link DiscoverService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize("hasRole('USER')")} is enforced by
 * method-security (inactive in standalone MockMvc) — covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiscoverController (unit)")
class DiscoverControllerUnitTest {

    private static final String BASE = "/discover";
    private static final String TARGET = "user-uuid-2";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private DiscoverService discoverService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        DiscoverController controller = new DiscoverController(discoverService);

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

        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static PaginatedResponse<DiscoverProfileResponse> paged(DiscoverProfileResponse item) {
        return PaginatedResponse.<DiscoverProfileResponse>builder()
                .items(List.of(item))
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor("next-cursor").hasNext(true).hasPrevious(false)
                        .total(1L).page(0).size(20).build())
                .build();
    }

    private static DiscoverProfileResponse profile(String id) {
        return DiscoverProfileResponse.builder().id(id).name("Bob").username("bob").age(28).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /discover
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /discover")
    class GetDiscover {

        @Test
        void shouldReturnPagedResultsWithDefaults() throws Exception {
            when(discoverService.getDiscover(any(), any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any(), any(), any())).thenReturn(paged(profile(TARGET)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.items[0].id").value(TARGET))
                    .andExpect(jsonPath("$.data.pagination.total").value(1));

            // All filters default to null; limit defaults to 20.
            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(discoverService).getDiscover(isNull(), isNull(), isNull(), isNull(), isNull(),
                    isNull(), limit.capture(), isNull(), isNull(), isNull(), isNull(), eq(testUser));
            assertThat(limit.getValue()).isEqualTo(20);
        }

        @Test
        void shouldForwardAllFilterParams() throws Exception {
            when(discoverService.getDiscover(any(), any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any(), any(), any())).thenReturn(paged(profile(TARGET)));

            mockMvc.perform(get(BASE)
                            .param("q", "bob").param("interests", "music,art")
                            .param("distance", "12.5").param("verified", "true").param("isOnline", "false")
                            .param("cursor", "c1").param("limit", "5")
                            .param("minAge", "21").param("maxAge", "35")
                            .param("gender", "female").param("country", "India"))
                    .andExpect(status().isOk());

            verify(discoverService).getDiscover(eq("bob"), eq("music,art"), eq(12.5), eq(true), eq(false),
                    eq("c1"), eq(5), eq(21), eq(35), eq("female"), eq("India"), eq(testUser));
        }

        @Test
        void shouldReturnEmptyPage() throws Exception {
            when(discoverService.getDiscover(any(), any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any(), any(), any()))
                    .thenReturn(PaginatedResponse.<DiscoverProfileResponse>builder().items(List.of()).build());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.items").isEmpty());
        }

        @Test
        void shouldReturn500WhenLimitNotNumeric() throws Exception {
            // Type-mismatch on the int limit param → MethodArgumentTypeMismatchException → catch-all 500.
            mockMvc.perform(get(BASE).param("limit", "abc"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(discoverService);
        }

        @Test
        void shouldReturn500WhenDistanceNotNumeric() throws Exception {
            mockMvc.perform(get(BASE).param("distance", "far"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(discoverService);
        }

        @Test
        void shouldReturn500WhenServiceThrows() throws Exception {
            when(discoverService.getDiscover(any(), any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST/DELETE /discover/{userId}/like
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("like / unlike")
    class LikeUnlike {

        @Test
        void shouldLikeProfile() throws Exception {
            doNothing().when(discoverService).likeProfile(any(), any());

            mockMvc.perform(post(BASE + "/" + TARGET + "/like"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_DISCOVER_002"))
                    .andExpect(jsonPath("$.message").value("User profile liked"));

            verify(discoverService).likeProfile(TARGET, testUser);
            verify(discoverService, never()).unlikeProfile(any(), any());
        }

        @Test
        void shouldUnlikeProfile() throws Exception {
            doNothing().when(discoverService).unlikeProfile(any(), any());

            mockMvc.perform(delete(BASE + "/" + TARGET + "/like"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_DISCOVER_003"))
                    .andExpect(jsonPath("$.message").value("User profile unliked"));

            verify(discoverService).unlikeProfile(TARGET, testUser);
            verify(discoverService, never()).likeProfile(any(), any());
        }

        @Test
        void shouldReturn404WhenLikingMissingUser() throws Exception {
            doThrow(new NotFoundException("User not found", "TM_064"))
                    .when(discoverService).likeProfile(any(), any());
            mockMvc.perform(post(BASE + "/" + TARGET + "/like"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn400WhenLikingSelf() throws Exception {
            doThrow(new com.chat.talkMe.exception.BadRequestException("Cannot like yourself", "TM_DISCOVER_004"))
                    .when(discoverService).likeProfile(any(), any());
            mockMvc.perform(post(BASE + "/self/like"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_DISCOVER_004"));
        }

        @Test
        void shouldReturn403WhenLikingBlockedUser() throws Exception {
            doThrow(new ForbiddenException("Cannot like this user", "TM_069"))
                    .when(discoverService).likeProfile(any(), any());
            mockMvc.perform(post(BASE + "/" + TARGET + "/like"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_069"));
        }

        @Test
        void shouldReturn404WhenUnlikingMissingUser() throws Exception {
            doThrow(new NotFoundException("User not found", "TM_064"))
                    .when(discoverService).unlikeProfile(any(), any());
            mockMvc.perform(delete(BASE + "/" + TARGET + "/like"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }
    }
}
