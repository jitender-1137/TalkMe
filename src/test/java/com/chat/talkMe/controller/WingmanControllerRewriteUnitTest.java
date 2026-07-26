package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.WingmanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link WingmanController}'s {@code POST /match/wingman/rewrite}
 * endpoint (feature #11 "rewrite my message").
 *
 * <p>The sibling {@code WingmanControllerUnitTest} covers the {@code /icebreakers} and
 * {@code /suggest} routes but not {@code /rewrite}; this companion class fills that gap without
 * touching the existing file.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link WingmanService} (plus the repository
 * collaborators the controller requires for construction) and the real {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b>
 * <ul>
 *   <li>The class-level {@code @PreAuthorize("hasRole('USER')")} and method-level
 *       {@code @featureGuard.check('AI_WINGMAN')} gates are method-security concerns (inactive in
 *       standalone MockMvc) — the entitlement gate is covered by the integration test. Here we drive
 *       the controller's own validation (blank / oversize draft) and its {@code clamp}-and-delegate
 *       wiring.</li>
 *   <li>{@code RewriteRequest} is a plain {@code record} with no Bean-Validation annotations and the
 *       controller does not {@code @Valid} it, so every rejection is done by explicit controller
 *       checks that raise {@link com.chat.talkMe.exception.BadRequestException} (400, service-specific
 *       code) — there is no {@code VE_101}/{@code MethodArgumentNotValid} path.</li>
 *   <li>{@code max} is a boxed {@link Integer}: absent → controller default (5); no unboxed primitive
 *       is present in the body, so the Jackson primitive-coercion gotcha does not apply.</li>
 *   <li>The rewrite handler never dereferences the authenticated principal (it works purely on the
 *       supplied draft), but a principal is still installed for parity with the repo template.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WingmanController#rewrite (unit)")
class WingmanControllerRewriteUnitTest {

    private static final String REWRITE = "/match/wingman/rewrite";
    private static final String SUCCESS_CODE = "TM_000";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private WingmanService wingmanService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private BlockUserRepository blockUserRepository;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        WingmanController controller = new WingmanController(
                wingmanService, userRepository, friendRepository, blockUserRepository);

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

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /match/wingman/rewrite  (happy path)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path")
    class RewriteHappyPath {

        @Test
        void shouldReturn200WithVariantsAndForwardDraftToneAndMax() throws Exception {
            authenticate();
            when(wingmanService.rewrite(any(), any(), anyInt()))
                    .thenReturn(List.of("Variant one", "Variant two", "Variant three"));

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"hey there stranger\",\"tone\":\"flirty\",\"max\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(SUCCESS_CODE))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(3))
                    .andExpect(jsonPath("$.data[0]").value("Variant one"))
                    .andExpect(jsonPath("$.data[2]").value("Variant three"));

            // The raw draft (controller does not trim), the tone, and the clamped max are forwarded.
            verify(wingmanService).rewrite(eq("hey there stranger"), eq("flirty"), eq(3));
        }

        @Test
        void shouldDefaultMaxTo5WhenOmitted() throws Exception {
            authenticate();
            when(wingmanService.rewrite(any(), any(), anyInt())).thenReturn(List.of("v"));

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"just the draft here\"}"))
                    .andExpect(status().isOk());

            // No tone → null; no max → controller DEFAULT_MAX of 5.
            verify(wingmanService).rewrite(eq("just the draft here"), isNull(), eq(5));
        }

        @Test
        void shouldClampMaxDownToHardCapOf10() throws Exception {
            authenticate();
            when(wingmanService.rewrite(any(), any(), anyInt())).thenReturn(List.of("v"));

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"a draft\",\"max\":100}"))
                    .andExpect(status().isOk());

            verify(wingmanService).rewrite(eq("a draft"), isNull(), eq(10)); // HARD_CAP
        }

        @Test
        void shouldClampNonPositiveMaxUpToDefault5() throws Exception {
            authenticate();
            when(wingmanService.rewrite(any(), any(), anyInt())).thenReturn(List.of("v"));

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"a draft\",\"max\":0}"))
                    .andExpect(status().isOk());

            verify(wingmanService).rewrite(eq("a draft"), isNull(), eq(5)); // DEFAULT_MAX
        }

        @Test
        void shouldPassThroughUnicodeAndInjectionTextInDraftVerbatim() throws Exception {
            authenticate();
            when(wingmanService.rewrite(any(), any(), anyInt())).thenReturn(List.of("v"));
            // Controller does not sanitise; the raw draft is forwarded straight to the service.
            String payload = "café ☕ <script>alert(1)</script>'; DROP TABLE users;--";

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"café ☕ <script>alert(1)</script>'; DROP TABLE users;--\"}"))
                    .andExpect(status().isOk());

            verify(wingmanService).rewrite(eq(payload), isNull(), eq(5));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /match/wingman/rewrite  (validation → BadRequestException via handler)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validation")
    class RewriteValidation {

        @Test
        void shouldReturn400WhenDraftIsBlank() throws Exception {
            authenticate();

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_027"));

            verify(wingmanService, never()).rewrite(any(), any(), anyInt());
        }

        @Test
        void shouldReturn400WhenDraftFieldMissing() throws Exception {
            authenticate();
            // Absent draft → record's draft() is null → "Nothing to rewrite" (TM_027).
            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tone\":\"flirty\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_027"));

            verify(wingmanService, never()).rewrite(any(), any(), anyInt());
        }

        @Test
        void shouldReturn400WhenDraftExceeds1000Chars() throws Exception {
            authenticate();
            String oversize = "a".repeat(1001);

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"" + oversize + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_028"));

            verify(wingmanService, never()).rewrite(any(), any(), anyInt());
        }

        @Test
        void shouldAcceptDraftAtExactly1000CharBoundary() throws Exception {
            authenticate();
            when(wingmanService.rewrite(any(), any(), anyInt())).thenReturn(List.of("v"));
            // length() > 1000 is the reject condition, so exactly 1000 is allowed through.
            String atLimit = "a".repeat(1000);

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"" + atLimit + "\"}"))
                    .andExpect(status().isOk());

            verify(wingmanService).rewrite(eq(atLimit), isNull(), eq(5));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /match/wingman/rewrite  (framework / service error mapping)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error mapping")
    class RewriteErrors {

        @Test
        void shouldReturn500WhenServiceThrowsUnexpectedError() throws Exception {
            authenticate();
            when(wingmanService.rewrite(any(), any(), anyInt())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"draft\":\"a valid draft\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            // @RequestBody is required → missing body → HttpMessageNotReadableException → catch-all 500.
            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verifyNoInteractions(wingmanService);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(REWRITE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));

            verifyNoInteractions(wingmanService);
        }
    }
}
