package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStatusResponse;
import com.chat.talkMe.enums.ConsentType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ConsentAcceptanceService;
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

import java.util.Map;
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
 * Pure controller unit test for {@link ConsentController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link ConsentAcceptanceService} and the real
 * {@link GlobalExceptionHandler}. Only {@link AuthenticationPrincipalArgumentResolver} is
 * registered — no {@code Pageable} resolver is needed (the controller takes no {@code Pageable})
 * and no tolerant Jackson mapper is needed (the only {@code @RequestBody} DTO,
 * {@link com.chat.talkMe.dto.request.ConsentAcceptRequest}, has no unboxed primitive fields — the
 * primitive booleans on {@link ConsentStatusResponse} are serialized only, never deserialized).
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF) and any
 * {@code @PreAuthorize} method-security gates are enforced by Spring's security interceptor, which
 * is inactive in a standalone MockMvc setup — those are covered by the integration test. Here we
 * verify request/response wiring, validation, the {@code X-Forwarded-For} client-IP resolution, and
 * delegation to the service (which owns the consent-version/age logic).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentController (unit)")
class ConsentControllerUnitTest {

    private static final String BASE = "/consent";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private ConsentAcceptanceService consentAcceptanceService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ConsentController controller = new ConsentController(consentAcceptanceService);

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void authenticate() {
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static ConsentStatusResponse fullStatus() {
        return ConsentStatusResponse.builder()
                .accepted(Map.of(
                        "AGE_18_PLUS", true,
                        "COMMUNITY_GUIDELINES", true,
                        "FLIRT_LOBBY", true))
                .requiredVersions(Map.of(
                        "AGE_18_PLUS", "1",
                        "COMMUNITY_GUIDELINES", "2",
                        "FLIRT_LOBBY", "1"))
                .flirtLobbyReady(true)
                .ageVerified(true)
                .build();
    }

    private static ConsentStatusResponse emptyStatus() {
        return ConsentStatusResponse.builder()
                .accepted(Map.of())
                .requiredVersions(Map.of())
                .flirtLobbyReady(false)
                .ageVerified(false)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /consent/status
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /consent/status")
    class Status {

        @Test
        void shouldReturn200WithFullStatus() throws Exception {
            authenticate();
            when(consentAcceptanceService.getStatus(any())).thenReturn(fullStatus());

            mockMvc.perform(get(BASE + "/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // Success code read directly from the controller: SuccessResponseDto.success(data)
                    // → ResponseDto.success(data, "Success", "TM_000").
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.accepted.AGE_18_PLUS").value(true))
                    .andExpect(jsonPath("$.data.requiredVersions.COMMUNITY_GUIDELINES").value("2"))
                    // Lombok boolean isFlirtLobbyReady()/isAgeVerified() → JSON flirtLobbyReady/ageVerified.
                    .andExpect(jsonPath("$.data.flirtLobbyReady").value(true))
                    .andExpect(jsonPath("$.data.ageVerified").value(true));

            verify(consentAcceptanceService).getStatus(testUser);
            verify(consentAcceptanceService, never()).accept(any(), any(), any(), any());
        }

        @Test
        void shouldReturn200WithEmptyMapsWhenNothingAccepted() throws Exception {
            authenticate();
            when(consentAcceptanceService.getStatus(any())).thenReturn(emptyStatus());

            mockMvc.perform(get(BASE + "/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accepted").isMap())
                    .andExpect(jsonPath("$.data.accepted").isEmpty())
                    .andExpect(jsonPath("$.data.flirtLobbyReady").value(false))
                    .andExpect(jsonPath("$.data.ageVerified").value(false));
        }

        @Test
        void shouldForwardAuthenticatedUserToService() throws Exception {
            authenticate();
            when(consentAcceptanceService.getStatus(any())).thenReturn(fullStatus());

            mockMvc.perform(get(BASE + "/status")).andExpect(status().isOk());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(consentAcceptanceService).getStatus(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(user.getValue().getUsername()).isEqualTo("testuser");
        }

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(consentAcceptanceService.getStatus(any()))
                    .thenThrow(new NotFoundException("User not found", "TM_064"));

            mockMvc.perform(get(BASE + "/status"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(consentAcceptanceService.getStatus(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/status"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /consent/accept
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /consent/accept")
    class Accept {

        @Test
        void shouldReturn200AndForwardTypeVersionUserAndIp() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(fullStatus());

            String body = """
                    {"type":"AGE_18_PLUS","version":"v2"}""";
            mockMvc.perform(post(BASE + "/accept")
                            .header("X-Forwarded-For", "203.0.113.5")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // Success message + code read directly from the controller.
                    .andExpect(jsonPath("$.message").value("Consent recorded"))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.flirtLobbyReady").value(true));

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<ConsentType> type = ArgumentCaptor.forClass(ConsentType.class);
            ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
            verify(consentAcceptanceService).accept(user.capture(), type.capture(), version.capture(), ip.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(type.getValue()).isEqualTo(ConsentType.AGE_18_PLUS);
            assertThat(version.getValue()).isEqualTo("v2");
            assertThat(ip.getValue()).isEqualTo("203.0.113.5");
            verify(consentAcceptanceService, never()).getStatus(any());
        }

        @Test
        void shouldUseRemoteAddrWhenNoForwardedForHeader() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(fullStatus());

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"1\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
            verify(consentAcceptanceService).accept(any(), any(), any(), ip.capture());
            // MockHttpServletRequest default remote address.
            assertThat(ip.getValue()).isEqualTo("127.0.0.1");
        }

        @Test
        void shouldForwardFirstIpWhenForwardedForHasMultiple() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(fullStatus());

            mockMvc.perform(post(BASE + "/accept")
                            .header("X-Forwarded-For", "  70.41.3.18 , 150.172.238.178 , 10.0.0.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"1\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
            verify(consentAcceptanceService).accept(any(), any(), any(), ip.capture());
            assertThat(ip.getValue()).isEqualTo("70.41.3.18");
        }

        @Test
        void shouldForwardNullVersionWhenOmitted() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(fullStatus());

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"COMMUNITY_GUIDELINES\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<ConsentType> type = ArgumentCaptor.forClass(ConsentType.class);
            ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
            verify(consentAcceptanceService).accept(eq(testUser), type.capture(), version.capture(), any());
            assertThat(type.getValue()).isEqualTo(ConsentType.COMMUNITY_GUIDELINES);
            assertThat(version.getValue()).isNull();
        }

        @Test
        void shouldAcceptFlirtLobbyType() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(fullStatus());

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"FLIRT_LOBBY\",\"version\":\"1\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<ConsentType> type = ArgumentCaptor.forClass(ConsentType.class);
            verify(consentAcceptanceService).accept(any(), type.capture(), any(), any());
            assertThat(type.getValue()).isEqualTo(ConsentType.FLIRT_LOBBY);
        }

        @Test
        void shouldReturn200WithEmptyStatusPayload() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(emptyStatus());

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accepted").isEmpty())
                    .andExpect(jsonPath("$.data.flirtLobbyReady").value(false));
        }

        // ── Edge: free-form version string is forwarded verbatim (no @Size/sanitization) ──

        @Test
        void shouldPassThroughUnicodeAndEmojiVersion() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(fullStatus());

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"v😀-名前\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
            verify(consentAcceptanceService).accept(any(), any(), version.capture(), any());
            assertThat(version.getValue()).isEqualTo("v😀-名前");
        }

        @Test
        void shouldPassThroughXssAndSqliVersionVerbatim() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any())).thenReturn(fullStatus());

            String nasty = "<script>alert(1)</script>'; DROP TABLE consent;--";
            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"" + nasty + "\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
            verify(consentAcceptanceService).accept(any(), any(), version.capture(), any());
            // Controller is a pass-through: no escaping/sanitizing happens at this layer.
            assertThat(version.getValue()).isEqualTo(nasty);
        }

        // ── Negative: validation / body parsing ──

        @Test
        void shouldReturn400AndSkipServiceWhenTypeMissing() throws Exception {
            authenticate();
            // type is @NotNull; an absent key deserializes to null → MethodArgumentNotValidException.
            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":\"1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(consentAcceptanceService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenTypeExplicitlyNull() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":null,\"version\":\"1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(consentAcceptanceService);
        }

        @Test
        void shouldReturn500AndSkipServiceWhenTypeInvalidEnum() throws Exception {
            authenticate();
            // An unknown enum literal in the body fails Jackson deserialization
            // (HttpMessageNotReadableException). There is no dedicated handler for it, so it
            // falls through to the catch-all → 500/TM_002 (pins current behaviour). Note: the
            // enum arrives in the @RequestBody, not a @RequestParam, so this is NOT a TM_071 case.
            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"NOT_A_REAL_TYPE\",\"version\":\"1\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(consentAcceptanceService);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/accept").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(consentAcceptanceService);
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            // No request body → HttpMessageNotReadableException → catch-all 500 (no dedicated handler).
            mockMvc.perform(post(BASE + "/accept").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(consentAcceptanceService);
        }

        // ── Negative: service exception → status mapping ──

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any()))
                    .thenThrow(new NotFoundException("Consent type not configured", "TM_064"));

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"1\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any()))
                    .thenThrow(new ForbiddenException("Account is not age-verified", "TM_005"));

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"FLIRT_LOBBY\",\"version\":\"1\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any()))
                    .thenThrow(new ConflictException("Consent already recorded at this version", "TM_096"));

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"1\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_096"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any()))
                    .thenThrow(new BadRequestException("Stale consent version", "TM_071"));

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"old\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn422WhenServiceThrowsServiceException() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any()))
                    .thenThrow(new ServiceException(422, "Consent could not be processed", "TM_143"));

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"1\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.messageCode").value("TM_143"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(consentAcceptanceService.accept(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGE_18_PLUS\",\"version\":\"1\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
