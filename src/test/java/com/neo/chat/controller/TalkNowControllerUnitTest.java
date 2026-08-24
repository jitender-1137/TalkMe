package com.neo.chat.controller;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.TalkNowAvailabilityResponse;
import com.neo.chat.dto.response.TalkNowCardResponse;
import com.neo.chat.dto.response.TalkNowMatchResponse;
import com.neo.chat.enums.TalkNowIntent;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.GlobalExceptionHandler;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.TalkNowService;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link TalkNowController} (Talk Now — TALK_NOW).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link TalkNowService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>The class/method {@code @PreAuthorize} ({@code hasRole('USER')} and
 *       {@code @featureGuard.check('TALK_NOW')}) are enforced by Spring's method-security
 *       interceptor, inactive in standalone MockMvc — covered by the integration test.</li>
 *   <li>{@link com.neo.chat.dto.request.DeclareAvailableRequest} carries NO Bean-Validation
 *       annotations and the controller uses no {@code @Valid}: a missing/null intent is rejected
 *       inside the service (TM_936), driven here by stubbing the service to throw. A
 *       {@link LocalValidatorFactoryBean} is still wired for parity with the repo template.</li>
 * </ul>
 *
 * <p>Success codes are read directly from the controller (TM_000 on every endpoint).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TalkNowController (unit)")
class TalkNowControllerUnitTest {

    private static final String BASE = "/talk-now";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private TalkNowService talkNowService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        TalkNowController controller = new TalkNowController(talkNowService);

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

    private static TalkNowAvailabilityResponse snapshot() {
        TalkNowCardResponse card = TalkNowCardResponse.builder()
                .username("alice").name("Alice").avatar("https://cdn/alice.png")
                .intent(TalkNowIntent.NEED_ADVICE).country("US").language("en")
                .compatibilityBucket("HIGH").compatibilityScore(88)
                .build();
        return TalkNowAvailabilityResponse.builder()
                .countsByIntent(Map.of("NEED_ADVICE", 1))
                .total(1)
                .available(List.of(card))
                .myIntent(TalkNowIntent.JUST_TALK)
                .declared(true)
                .build();
    }

    private static TalkNowMatchResponse matched() {
        return TalkNowMatchResponse.builder()
                .matched(true).waiting(false)
                .intent(TalkNowIntent.CAREER_CHAT)
                .partnerUuid("partner-uuid").partnerUsername("bob").partnerName("Bob")
                .partnerAvatar("https://cdn/bob.png").partnerCountry("IN").partnerLanguage("en")
                .partnerIntent(TalkNowIntent.CAREER_CHAT)
                .compatibility(CompatibilityScore.builder().overall(80).bucket("HIGH").build())
                .message("Matched — opening chat")
                .build();
    }

    private static TalkNowMatchResponse waiting() {
        return TalkNowMatchResponse.builder()
                .matched(false).waiting(true)
                .intent(TalkNowIntent.STUDY_TOGETHER)
                .message("No one is available right now — you're marked available and waiting")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /talk-now/available -> declareAvailable
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /talk-now/available")
    class DeclareAvailable {

        @Test
        void shouldReturn200AndSnapshotAndForwardArgs() throws Exception {
            authenticate();
            when(talkNowService.declareAvailable(any(), any(), any(), any())).thenReturn(snapshot());

            mockMvc.perform(post(BASE + "/available").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"intent\":\"JUST_TALK\",\"language\":\"en\",\"country\":\"IN\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.declared").value(true))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.myIntent").value("JUST_TALK"))
                    .andExpect(jsonPath("$.data.available[0].username").value("alice"))
                    .andExpect(jsonPath("$.data.available[0].compatibilityBucket").value("HIGH"))
                    .andExpect(jsonPath("$.data.countsByIntent.NEED_ADVICE").value(1));

            ArgumentCaptor<TalkNowIntent> intent = ArgumentCaptor.forClass(TalkNowIntent.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(talkNowService).declareAvailable(user.capture(), intent.capture(), eq("en"), eq("IN"));
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(intent.getValue()).isEqualTo(TalkNowIntent.JUST_TALK);
            verify(talkNowService, never()).cancel(any());
        }

        @Test
        void shouldReturn400WhenIntentMissing() throws Exception {
            authenticate();
            // No @Valid on the DTO: a null intent reaches the service, which rejects it (TM_936).
            when(talkNowService.declareAvailable(any(), isNull(), any(), any()))
                    .thenThrow(new BadRequestException("An intent is required", "TM_936"));

            mockMvc.perform(post(BASE + "/available").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"en\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_936"));
        }

        @Test
        void shouldReturn500OnMalformedJson() throws Exception {
            authenticate();
            // Unparseable JSON → HttpMessageNotReadableException → catch-all 500 (repo-pinned behavior).
            mockMvc.perform(post(BASE + "/available").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verify(talkNowService, never()).declareAvailable(any(), any(), any(), any());
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(talkNowService.declareAvailable(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/available").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"intent\":\"JUST_TALK\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /talk-now/available -> cancel
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /talk-now/available")
    class Cancel {

        @Test
        void shouldReturn200AndForwardCaller() throws Exception {
            authenticate();
            doNothing().when(talkNowService).cancel(any());

            mockMvc.perform(delete(BASE + "/available"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("No longer available"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(talkNowService).cancel(user.capture());
            assertThat(user.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(talkNowService).cancel(any());

            mockMvc.perform(delete(BASE + "/available"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /talk-now/available -> getAvailable
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /talk-now/available")
    class GetAvailable {

        @Test
        void shouldReturn200WithSnapshot() throws Exception {
            authenticate();
            when(talkNowService.getAvailable(any())).thenReturn(snapshot());

            mockMvc.perform(get(BASE + "/available"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.available[0].username").value("alice"))
                    .andExpect(jsonPath("$.data.available[0].intent").value("NEED_ADVICE"));

            verify(talkNowService).getAvailable(testUser);
            verify(talkNowService, never()).matchNow(any(), any());
        }

        @Test
        void shouldReturn200WithEmptySnapshot() throws Exception {
            authenticate();
            when(talkNowService.getAvailable(any())).thenReturn(TalkNowAvailabilityResponse.builder()
                    .countsByIntent(Map.of()).total(0).available(List.of()).declared(false).build());

            mockMvc.perform(get(BASE + "/available"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.available").isArray())
                    .andExpect(jsonPath("$.data.available").isEmpty())
                    .andExpect(jsonPath("$.data.declared").value(false));
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(talkNowService.getAvailable(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/available"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /talk-now/match -> matchNow
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /talk-now/match")
    class Match {

        @Test
        void shouldReturn200AndMatchedPartnerAndForwardIntent() throws Exception {
            authenticate();
            when(talkNowService.matchNow(any(), any())).thenReturn(matched());

            mockMvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"intent\":\"CAREER_CHAT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data.matched").value(true))
                    .andExpect(jsonPath("$.data.waiting").value(false))
                    .andExpect(jsonPath("$.data.partnerUsername").value("bob"))
                    .andExpect(jsonPath("$.data.partnerUuid").value("partner-uuid"))
                    .andExpect(jsonPath("$.data.compatibility.bucket").value("HIGH"));

            ArgumentCaptor<TalkNowIntent> intent = ArgumentCaptor.forClass(TalkNowIntent.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(talkNowService).matchNow(user.capture(), intent.capture());
            assertThat(user.getValue()).isSameAs(testUser);
            assertThat(intent.getValue()).isEqualTo(TalkNowIntent.CAREER_CHAT);
        }

        @Test
        void shouldReturn200AndWaitingWhenNoneAvailable() throws Exception {
            authenticate();
            when(talkNowService.matchNow(any(), any())).thenReturn(waiting());

            mockMvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"intent\":\"STUDY_TOGETHER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.matched").value(false))
                    .andExpect(jsonPath("$.data.waiting").value(true))
                    .andExpect(jsonPath("$.data.partnerUsername").doesNotExist());
        }

        @Test
        void shouldReturn400WhenIntentMissing() throws Exception {
            authenticate();
            when(talkNowService.matchNow(any(), isNull()))
                    .thenThrow(new BadRequestException("An intent is required", "TM_936"));

            mockMvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_936"));
        }

        @Test
        void shouldReturn500OnUnexpectedRuntimeException() throws Exception {
            authenticate();
            when(talkNowService.matchNow(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"intent\":\"CAREER_CHAT\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
