package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NightOwlDashboardResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.NightOwlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link NightOwlController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link NightOwlService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('NIGHT_OWL')")} entitlement
 * gate is enforced by Spring method-security (inactive in standalone MockMvc) — covered by the
 * integration test. This suite verifies request/response wiring and service delegation only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NightOwlController (unit)")
class NightOwlControllerUnitTest {

    private static final String DASHBOARD = "/night-owl/dashboard";

    @Mock
    private NightOwlService nightOwlService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        NightOwlController controller = new NightOwlController(nightOwlService);

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

    @Nested
    @DisplayName("GET /night-owl/dashboard")
    class Dashboard {

        @Test
        void shouldReturnPopulatedDashboard() throws Exception {
            when(nightOwlService.getDashboard(testUser)).thenReturn(
                    NightOwlDashboardResponse.builder()
                            .nightUsersOnline(7)
                            .trendingTopics(List.of("late-night", "music"))
                            .build());

            mockMvc.perform(get(DASHBOARD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.nightUsersOnline").value(7))
                    .andExpect(jsonPath("$.data.trendingTopics[0]").value("late-night"));

            verify(nightOwlService).getDashboard(testUser);
        }

        @Test
        void shouldReturnEmptyDashboard() throws Exception {
            when(nightOwlService.getDashboard(any()))
                    .thenReturn(NightOwlDashboardResponse.builder().nightUsersOnline(0).build());

            mockMvc.perform(get(DASHBOARD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nightUsersOnline").value(0));
        }

        @Test
        void shouldReturn403WhenServiceReportsEntitlementDenied() throws Exception {
            when(nightOwlService.getDashboard(any()))
                    .thenThrow(new ForbiddenException("Night Owl not unlocked", "TM_403"));

            mockMvc.perform(get(DASHBOARD))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_403"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            when(nightOwlService.getDashboard(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(DASHBOARD))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }
}
