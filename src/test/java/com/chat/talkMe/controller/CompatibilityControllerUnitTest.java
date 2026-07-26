package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.CompatibilityService;
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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link CompatibilityController}.
 *
 * <p>Standalone {@link MockMvc} with mocked {@link CompatibilityService} + {@link UserRepository}
 * and the real {@link GlobalExceptionHandler}. Exercises the controller's two branches: the
 * {@code UUID.fromString} guard (invalid UUID → 400/TM_INVALID_UUID) and the
 * {@code orElseThrow(NotFoundException)} (unknown user → 404/TM_024).
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('COMPATIBILITY_METER')")}
 * gate is method-security (inactive in standalone MockMvc) — covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CompatibilityController (unit)")
class CompatibilityControllerUnitTest {

    private static final String BASE = "/match/compatibility";
    private static final String OTHER_UUID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private CompatibilityService compatibilityService;
    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        CompatibilityController controller =
                new CompatibilityController(compatibilityService, userRepository);

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
        otherUser = User.builder().username("other").email("o@e.com").name("Other")
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

    private static CompatibilityScore score(int overall) {
        return CompatibilityScore.builder().overall(overall).bucket("HIGH").explanation("great").build();
    }

    @Nested
    @DisplayName("GET /match/compatibility/{userUuid}")
    class Compatibility {

        @Test
        void shouldReturnScoreForValidUser() throws Exception {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(otherUser));
            when(compatibilityService.score(testUser, otherUser)).thenReturn(score(88));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.overall").value(88))
                    .andExpect(jsonPath("$.data.bucket").value("HIGH"));

            // The path UUID is parsed and looked up; the score is computed between me and other.
            ArgumentCaptor<UUID> uuid = ArgumentCaptor.forClass(UUID.class);
            verify(userRepository).findByUuid(uuid.capture());
            assertThat(uuid.getValue()).isEqualTo(UUID.fromString(OTHER_UUID));
            verify(compatibilityService).score(testUser, otherUser);
        }

        @Test
        void shouldReturn400AndSkipLookupWhenUuidMalformed() throws Exception {
            mockMvc.perform(get(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    // UUID.fromString throws IllegalArgumentException("Invalid UUID string: ...")
                    .andExpect(jsonPath("$.messageCode").value("TM_INVALID_UUID"));

            verifyNoInteractions(userRepository, compatibilityService);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(userRepository.findByUuid(any())).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_024"));

            verify(userRepository).findByUuid(any());
            verify(compatibilityService, never()).score(any(), any());
        }

        @Test
        void shouldReturn500WhenScoringFails() throws Exception {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(otherUser));
            when(compatibilityService.score(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }

        @Test
        void shouldReturnZeroScoreBucket() throws Exception {
            when(userRepository.findByUuid(any())).thenReturn(Optional.of(otherUser));
            when(compatibilityService.score(any(), any())).thenReturn(score(0));

            mockMvc.perform(get(BASE + "/" + OTHER_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.overall").value(0));
        }
    }
}
