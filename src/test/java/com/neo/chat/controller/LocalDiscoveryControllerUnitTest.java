package com.neo.chat.controller;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.LocalPersonResponse;
import com.neo.chat.dto.response.LocalSearchPageResponse;
import com.neo.chat.enums.Interest;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.LocalDiscoveryService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link LocalDiscoveryController} (Local Discovery).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link LocalDiscoveryService} and the real
 * {@link GlobalExceptionHandler}. The {@code @PreAuthorize} (role +
 * {@code @featureGuard.check('LOCAL_DISCOVERY')}) is inactive under standalone MockMvc —
 * covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocalDiscoveryController (unit)")
class LocalDiscoveryControllerUnitTest {

    private static final String BASE = "/local";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private LocalDiscoveryService localDiscoveryService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        LocalDiscoveryController controller = new LocalDiscoveryController(localDiscoveryService);

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

    private static LocalPersonResponse person(String username, String presence) {
        return LocalPersonResponse.builder()
                .userUuid("target-uuid-1").name("Neighbour").username(username)
                .avatar("https://cdn/a.png").country("IN").city("Pune").mood("LOOKING_FOR_FRIENDS")
                .presence(presence)
                .sharedInterests(List.of("PHOTOGRAPHY"))
                .build();
    }

    private static LocalSearchPageResponse page() {
        return LocalSearchPageResponse.builder()
                .items(List.of(person("neighbour", "ONLINE")))
                .nextCursor("1").hasMore(true).build();
    }

    // ── GET /local/search ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /local/search")
    class Search {

        @Test
        void returns200AndForwardsAllParams() throws Exception {
            authenticate();
            when(localDiscoveryService.search(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyInt())).thenReturn(page());

            mockMvc.perform(get(BASE + "/search")
                            .param("city", "Pune")
                            .param("interest", "PHOTOGRAPHY")
                            .param("cursor", "0")
                            .param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items[0].username").value("neighbour"))
                    .andExpect(jsonPath("$.data.items[0].presence").value("ONLINE"))
                    .andExpect(jsonPath("$.data.items[0].sharedInterests[0]").value("PHOTOGRAPHY"))
                    .andExpect(jsonPath("$.data.nextCursor").value("1"))
                    .andExpect(jsonPath("$.data.hasMore").value(true));

            ArgumentCaptor<String> city = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Interest> interest = ArgumentCaptor.forClass(Interest.class);
            ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(localDiscoveryService)
                    .search(eq(testUser), city.capture(), interest.capture(), cursor.capture(), limit.capture());
            assertThat(city.getValue()).isEqualTo("Pune");
            assertThat(interest.getValue()).isEqualTo(Interest.PHOTOGRAPHY);
            assertThat(cursor.getValue()).isEqualTo("0");
            assertThat(limit.getValue()).isEqualTo(10);
        }

        @Test
        void usesDefaultLimitAndNullInterestWhenOmitted() throws Exception {
            authenticate();
            when(localDiscoveryService.search(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(LocalSearchPageResponse.builder()
                            .items(List.of()).nextCursor(null).hasMore(false).build());

            mockMvc.perform(get(BASE + "/search").param("city", "Delhi"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.hasMore").value(false));

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(localDiscoveryService)
                    .search(eq(testUser), eq("Delhi"), isNull(), isNull(), limit.capture());
            assertThat(limit.getValue()).isEqualTo(20); // controller default
        }

        @Test
        void returns400WhenServiceRejectsBlankCity() throws Exception {
            authenticate();
            when(localDiscoveryService.search(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyInt()))
                    .thenThrow(new BadRequestException("A city is required", "TM_870"));

            mockMvc.perform(get(BASE + "/search").param("city", " "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_870"));
        }
    }

    // ── GET /local/nearby ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /local/nearby")
    class Nearby {

        @Test
        void returns200WithResults() throws Exception {
            authenticate();
            when(localDiscoveryService.nearbyByMyCity(any(), any())).thenReturn(page());

            mockMvc.perform(get(BASE + "/nearby").param("interest", "PHOTOGRAPHY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.items[0].username").value("neighbour"));

            ArgumentCaptor<Interest> interest = ArgumentCaptor.forClass(Interest.class);
            verify(localDiscoveryService).nearbyByMyCity(eq(testUser), interest.capture());
            assertThat(interest.getValue()).isEqualTo(Interest.PHOTOGRAPHY);
        }

        @Test
        void returns400WhenViewerHasNoCity() throws Exception {
            authenticate();
            when(localDiscoveryService.nearbyByMyCity(any(), any()))
                    .thenThrow(new BadRequestException("Set your city", "TM_870"));

            mockMvc.perform(get(BASE + "/nearby"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_870"));

            verify(localDiscoveryService).nearbyByMyCity(eq(testUser), isNull());
        }

        @Test
        void returns500OnUnexpectedError() throws Exception {
            authenticate();
            when(localDiscoveryService.nearbyByMyCity(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/nearby"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
