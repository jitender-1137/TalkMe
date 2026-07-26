package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.TranslateRequest;
import com.chat.talkMe.dto.response.TranslateBatchResponse;
import com.chat.talkMe.dto.response.TranslateResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.TranslationService;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link TranslationController} (feature INSTANT_TRANSLATE).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link TranslationService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>{@code @PreAuthorize("@featureGuard.check('INSTANT_TRANSLATE')")} is enforced by Spring's
 *       method-security interceptor (AOP), which is NOT active in a standalone MockMvc setup — the
 *       entitlement gate is covered by the integration test. Here we verify request/response wiring,
 *       {@code @Valid} bean-validation, and delegation to the mocked service.</li>
 *   <li>Unlike the {@code MusicSessionController} test, this controller <b>does</b> annotate its body
 *       {@code @Valid} and {@link TranslateRequest} carries {@code @NotBlank}/{@code @Size} — so a
 *       {@link org.springframework.web.bind.MethodArgumentNotValidException} path exists and is wired
 *       via a {@link LocalValidatorFactoryBean}. {@link GlobalExceptionHandler} maps it to
 *       {@code 400 / VE_101}.</li>
 *   <li>The single {@code translatedText}/{@code target}/... fields are all free-form Strings; the
 *       controller passes the parsed request straight to the service without sanitising.</li>
 * </ul>
 *
 * <p>The happy path emits {@code messageCode == "TM_000"} directly from the controller
 * ({@code SuccessResponseDto.success(response)} → the inherited {@code ResponseDto.success(data)}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TranslationController (unit)")
class TranslationControllerUnitTest {

    private static final String BASE = "/translate";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private TranslationService translationService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        TranslationController controller = new TranslationController(translationService);

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
        testUser.setId(42L);
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

    /** A live provider translation result. */
    private static TranslateResponse translated(String text) {
        return TranslateResponse.builder()
                .translatedText(text)
                .detectedSource("en")
                .target("es")
                .cached(false)
                .provider("azure")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /translate — happy path & pass-through
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /translate — success")
    class Translate {

        @Test
        void shouldReturn200AndForwardParsedRequestAndUser() throws Exception {
            authenticate();
            when(translationService.translate(any(), any())).thenReturn(translated("hola"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\",\"source\":\"en\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.data.translatedText").value("hola"))
                    .andExpect(jsonPath("$.data.detectedSource").value("en"))
                    .andExpect(jsonPath("$.data.target").value("es"))
                    .andExpect(jsonPath("$.data.cached").value(false))
                    .andExpect(jsonPath("$.data.provider").value("azure"));

            ArgumentCaptor<TranslateRequest> req = ArgumentCaptor.forClass(TranslateRequest.class);
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(translationService).translate(user.capture(), req.capture());
            assertThat(req.getValue().getText()).isEqualTo("hello");
            assertThat(req.getValue().getTarget()).isEqualTo("es");
            assertThat(req.getValue().getSource()).isEqualTo("en");
            // The authenticated principal's wrapped User instance is forwarded verbatim.
            assertThat(user.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldForwardNullSourceWhenOmitted() throws Exception {
            authenticate();
            when(translationService.translate(any(), any())).thenReturn(translated("hola"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<TranslateRequest> req = ArgumentCaptor.forClass(TranslateRequest.class);
            verify(translationService).translate(eq(testUser), req.capture());
            assertThat(req.getValue().getSource()).isNull();
        }

        @Test
        void shouldPassThroughUnicodeEmojiAndInjectionTextVerbatim() throws Exception {
            authenticate();
            // The controller does not sanitise; the raw plaintext must reach the service untouched.
            String hostile = "日本語 🎧 <script>alert('xss')</script>'; DROP TABLE messages;--";
            when(translationService.translate(any(), any())).thenReturn(translated("ok"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"" + hostile.replace("\"", "\\\"")
                                    + "\",\"target\":\"fr\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<TranslateRequest> req = ArgumentCaptor.forClass(TranslateRequest.class);
            verify(translationService).translate(eq(testUser), req.capture());
            assertThat(req.getValue().getText()).isEqualTo(hostile);
            assertThat(req.getValue().getTarget()).isEqualTo("fr");
        }

        @Test
        void shouldEchoServiceFailOpenResponse() throws Exception {
            authenticate();
            // Service fails open (both providers down) → provider "none", input echoed. The
            // controller relays that 200 response unchanged.
            when(translationService.translate(any(), any())).thenReturn(
                    TranslateResponse.builder()
                            .translatedText("hello").target("es").cached(false).provider("none").build());

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.provider").value("none"))
                    .andExpect(jsonPath("$.data.translatedText").value("hello"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /translate — bean validation (@Valid → VE_101 / 400)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /translate — @Valid rejects before the service")
    class Validation {

        @Test
        void shouldReturn400WhenTextBlank() throws Exception {
            authenticate();
            // "   " is @NotBlank-blank → MethodArgumentNotValidException → VE_101.
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"   \",\"target\":\"es\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE))
                    .andExpect(jsonPath("$.errors.text").exists());
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn400WhenTextMissing() throws Exception {
            authenticate();
            // text absent → null → @NotBlank fails.
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target\":\"es\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE))
                    .andExpect(jsonPath("$.errors.text").exists());
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn400WhenTargetBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"  \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE))
                    .andExpect(jsonPath("$.errors.target").exists());
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn400WhenTargetMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE))
                    .andExpect(jsonPath("$.errors.target").exists());
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn400WhenTextExceedsMaxSize() throws Exception {
            authenticate();
            // @Size(max = 5000) — 5001 chars trips the size constraint.
            String oversize = "a".repeat(5001);
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"" + oversize + "\",\"target\":\"es\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE))
                    .andExpect(jsonPath("$.errors.text").exists());
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn200AtMaxSizeBoundary() throws Exception {
            authenticate();
            // Exactly 5000 chars is allowed — boundary must NOT trip @Size.
            String atLimit = "a".repeat(5000);
            when(translationService.translate(any(), any())).thenReturn(translated("ok"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"" + atLimit + "\",\"target\":\"es\"}"))
                    .andExpect(status().isOk());

            verify(translationService).translate(eq(testUser), any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /translate — service-thrown exception mapping via GlobalExceptionHandler
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /translate — service exception mapping")
    class ServiceErrors {

        @Test
        void shouldReturn429WhenDailyCapExceeded() throws Exception {
            authenticate();
            when(translationService.translate(any(), any()))
                    .thenThrow(new TooManyRequestsException(
                            "Daily translation limit reached", "TM_TRANSLATE_CAP"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_TRANSLATE_CAP"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsBadRequest() throws Exception {
            authenticate();
            when(translationService.translate(any(), any()))
                    .thenThrow(new BadRequestException("Bad target language", "TM_400"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_400"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(translationService.translate(any(), any()))
                    .thenThrow(new ForbiddenException("Translation not permitted", "TM_005"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(translationService.translate(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /translate — malformed / missing body & unauthenticated
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /translate — body & principal edge cases")
    class BodyAndPrincipal {

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unparseable JSON → HttpMessageNotReadableException → catch-all 500 (pinned).
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            // @RequestBody is required → missing body → HttpMessageNotReadableException → 500.
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn500AndNotCallServiceWhenUnauthenticated() throws Exception {
            // Body is valid so @Valid passes; then @AuthenticationPrincipal resolves null →
            // userDetails.getUser() NPEs inside the controller → catch-all 500.
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hello\",\"target\":\"es\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verify(translationService, never()).translate(any(), any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /translate/batch — happy path & validation
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /translate/batch")
    class BatchEndpoint {

        @Test
        void shouldReturn200AndForwardItemsAndUser() throws Exception {
            authenticate();
            TranslateBatchResponse resp = TranslateBatchResponse.builder()
                    .provider("azure")
                    .results(java.util.List.of(
                            TranslateBatchResponse.Result.builder()
                                    .id("m1").translatedText("hola").detectedSource("en").cached(false).build(),
                            TranslateBatchResponse.Result.builder()
                                    .id("m2").translatedText("mundo").detectedSource("en").cached(true).build()))
                    .build();
            when(translationService.translateBatch(any(), any())).thenReturn(resp);

            mockMvc.perform(post(BASE + "/batch").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[{\"id\":\"m1\",\"text\":\"hello\"},"
                                    + "{\"id\":\"m2\",\"text\":\"world\"}],\"target\":\"es\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.provider").value("azure"))
                    .andExpect(jsonPath("$.data.results[0].id").value("m1"))
                    .andExpect(jsonPath("$.data.results[0].translatedText").value("hola"))
                    .andExpect(jsonPath("$.data.results[1].id").value("m2"))
                    .andExpect(jsonPath("$.data.results[1].cached").value(true));

            verify(translationService).translateBatch(eq(testUser), any());
        }

        @Test
        void shouldReturn400WhenItemsEmpty() throws Exception {
            authenticate();
            // @NotEmpty on items → MethodArgumentNotValidException → VE_101.
            mockMvc.perform(post(BASE + "/batch").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[],\"target\":\"es\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn400WhenTargetMissing() throws Exception {
            authenticate();
            // @NotBlank target → VE_101.
            mockMvc.perform(post(BASE + "/batch").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[{\"id\":\"m1\",\"text\":\"hi\"}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn400WhenAnItemIdIsBlank() throws Exception {
            authenticate();
            // @Valid cascades to each item; @NotBlank id → VE_101 (the batch never reaches the service).
            mockMvc.perform(post(BASE + "/batch").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[{\"id\":\"  \",\"text\":\"hi\"}],\"target\":\"es\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(translationService);
        }

        @Test
        void shouldReturn429WhenServiceSignalsCapExceeded() throws Exception {
            authenticate();
            when(translationService.translateBatch(any(), any()))
                    .thenThrow(new TooManyRequestsException(
                            "Daily translation limit reached", "TM_TRANSLATE_CAP"));

            mockMvc.perform(post(BASE + "/batch").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\":[{\"id\":\"m1\",\"text\":\"hi\"}],\"target\":\"es\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.messageCode").value("TM_TRANSLATE_CAP"));
        }
    }
}
