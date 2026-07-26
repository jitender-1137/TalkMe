package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.UpdateSettingRequest;
import com.chat.talkMe.dto.response.UserSettingResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.UserSettingService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link UserSettingController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link UserSettingService} and the real
 * {@link GlobalExceptionHandler}. Only {@link AuthenticationPrincipalArgumentResolver} is
 * registered for {@code @AuthenticationPrincipal}.
 *
 * <p><b>No tolerant Jackson converter is needed here:</b> every field on
 * {@link UpdateSettingRequest} is a boxed type ({@code Boolean}/{@code Integer}/{@code String}),
 * so the standalone default Jackson-3 mapper never trips over an absent/null unboxed primitive.
 * (The primitives on {@link UserSettingResponse} only affect serialization, not request binding.)
 * No {@code Pageable} params exist, so no {@code PageableHandlerMethodArgumentResolver} either.
 *
 * <p><b>Enum validation lives in the service, not the controller:</b> the controller performs no
 * {@code Enum.valueOf} — it forwards the raw {@code value}/DTO to {@link UserSettingService}, which
 * throws {@link BadRequestException} (400) with {@code TM_067}/{@code TM_068} for bad enum strings.
 * Those cases are therefore exercised by stubbing the service, and the asserted codes are exactly
 * what the service throws.
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF) is
 * enforced by Spring's security layer / method-security interceptor, which is NOT active in a
 * standalone MockMvc setup — those gates are covered by the integration tests. Here we verify the
 * controller's request/response wiring and its delegation to the service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserSettingController (unit)")
class UserSettingControllerUnitTest {

    private static final String BASE = "/settings";
    private static final String SETTING_ID = "setting-uuid-1";
    private static final String UPDATED_CODE = "TM_066";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private UserSettingService userSettingService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        UserSettingController controller = new UserSettingController(userSettingService);

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

    private static UserSettingResponse settings() {
        return UserSettingResponse.builder()
                .id(SETTING_ID)
                .theme("DARK")
                .language("en")
                .notificationsEnabled(true)
                .safeModeEnabled(true)
                .soundEnabled(false)
                .messagingPrivacy("EVERYONE")
                .groupAddPrivacy("FRIENDS_ONLY")
                .emailLoginAlerts(true)
                .emailUnreadMessages(false)
                .emailAnnouncements(true)
                .nightOwlMode("AUTO")
                .nightStartHour(22)
                .nightEndHour(6)
                .nightAmbientSound("RAIN")
                .nightAccent("#8b5cf6")
                .build();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /settings
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /settings")
    class GetSettings {

        @Test
        void shouldReturn200WithSettingsAndForwardUser() throws Exception {
            authenticate();
            when(userSettingService.getSettings(any())).thenReturn(settings());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // SuccessResponseDto.success(response) → default message/code.
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.id").value(SETTING_ID))
                    .andExpect(jsonPath("$.data.theme").value("DARK"))
                    .andExpect(jsonPath("$.data.language").value("en"))
                    // Lombok boolean getter isX → JSON field x.
                    .andExpect(jsonPath("$.data.notificationsEnabled").value(true))
                    .andExpect(jsonPath("$.data.soundEnabled").value(false))
                    .andExpect(jsonPath("$.data.messagingPrivacy").value("EVERYONE"))
                    .andExpect(jsonPath("$.data.groupAddPrivacy").value("FRIENDS_ONLY"))
                    .andExpect(jsonPath("$.data.emailUnreadMessages").value(false))
                    .andExpect(jsonPath("$.data.nightOwlMode").value("AUTO"))
                    .andExpect(jsonPath("$.data.nightStartHour").value(22))
                    .andExpect(jsonPath("$.data.nightEndHour").value(6));

            verify(userSettingService).getSettings(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(userSettingService.getSettings(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /settings  (bulk update — @Valid @RequestBody)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /settings")
    class UpdateSettings {

        @Test
        void shouldReturn200AndForwardEveryFieldWhenFullBody() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            String body = """
                    {"theme":"DARK","language":"en","notificationsEnabled":true,
                     "safeModeEnabled":false,"soundEnabled":true,"messagingPrivacy":"FRIENDS_ONLY",
                     "groupAddPrivacy":"NOBODY","emailLoginAlerts":false,"emailUnreadMessages":true,
                     "emailAnnouncements":false,"nightOwlMode":"ON","nightStartHour":21,
                     "nightEndHour":7,"nightAmbientSound":"WAVES","nightAccent":"#123abc"}""";

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Settings updated successfully"))
                    .andExpect(jsonPath("$.messageCode").value(UPDATED_CODE))
                    .andExpect(jsonPath("$.data.id").value(SETTING_ID));

            ArgumentCaptor<UpdateSettingRequest> req = ArgumentCaptor.forClass(UpdateSettingRequest.class);
            verify(userSettingService).updateSettings(req.capture(), eq(testUser));
            UpdateSettingRequest sent = req.getValue();
            assertThat(sent.getTheme()).isEqualTo("DARK");
            assertThat(sent.getLanguage()).isEqualTo("en");
            assertThat(sent.getNotificationsEnabled()).isTrue();
            assertThat(sent.getSafeModeEnabled()).isFalse();
            assertThat(sent.getSoundEnabled()).isTrue();
            assertThat(sent.getMessagingPrivacy()).isEqualTo("FRIENDS_ONLY");
            assertThat(sent.getGroupAddPrivacy()).isEqualTo("NOBODY");
            assertThat(sent.getEmailLoginAlerts()).isFalse();
            assertThat(sent.getEmailUnreadMessages()).isTrue();
            assertThat(sent.getEmailAnnouncements()).isFalse();
            assertThat(sent.getNightOwlMode()).isEqualTo("ON");
            assertThat(sent.getNightStartHour()).isEqualTo(21);
            assertThat(sent.getNightEndHour()).isEqualTo(7);
            assertThat(sent.getNightAmbientSound()).isEqualTo("WAVES");
            assertThat(sent.getNightAccent()).isEqualTo("#123abc");
        }

        @Test
        void shouldSupportPartialUpdateLeavingUnsentFieldsNull() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"theme\":\"LIGHT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(UPDATED_CODE));

            ArgumentCaptor<UpdateSettingRequest> req = ArgumentCaptor.forClass(UpdateSettingRequest.class);
            verify(userSettingService).updateSettings(req.capture(), eq(testUser));
            UpdateSettingRequest sent = req.getValue();
            assertThat(sent.getTheme()).isEqualTo("LIGHT");
            // Everything not in the JSON stays null (unchanged semantics preserved by the service).
            assertThat(sent.getLanguage()).isNull();
            assertThat(sent.getNotificationsEnabled()).isNull();
            assertThat(sent.getSafeModeEnabled()).isNull();
            assertThat(sent.getMessagingPrivacy()).isNull();
            assertThat(sent.getGroupAddPrivacy()).isNull();
            assertThat(sent.getNightOwlMode()).isNull();
            assertThat(sent.getNightStartHour()).isNull();
        }

        @Test
        void shouldForwardBooleanFalseDirection() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"notificationsEnabled\":false,\"soundEnabled\":false}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<UpdateSettingRequest> req = ArgumentCaptor.forClass(UpdateSettingRequest.class);
            verify(userSettingService).updateSettings(req.capture(), any());
            assertThat(req.getValue().getNotificationsEnabled()).isFalse();
            assertThat(req.getValue().getSoundEnabled()).isFalse();
        }

        @Test
        void shouldAcceptEmptyJsonObjectAsNoOpAndStillDelegate() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(UPDATED_CODE));

            ArgumentCaptor<UpdateSettingRequest> req = ArgumentCaptor.forClass(UpdateSettingRequest.class);
            verify(userSettingService).updateSettings(req.capture(), eq(testUser));
            assertThat(req.getValue().getTheme()).isNull();
        }

        @Test
        void shouldForwardUnicodeAndEmojiInFreeFormFields() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nightAmbientSound\":\"雨 😀 rain\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<UpdateSettingRequest> req = ArgumentCaptor.forClass(UpdateSettingRequest.class);
            verify(userSettingService).updateSettings(req.capture(), any());
            assertThat(req.getValue().getNightAmbientSound()).isEqualTo("雨 😀 rain");
        }

        @Test
        void shouldForwardXssAndSqliPayloadsVerbatimWithoutSanitizing() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            String payload = "<script>alert(1)</script>'; DROP TABLE users;--";
            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nightAccent\":\"%s\"}".formatted(payload)))
                    .andExpect(status().isOk());

            ArgumentCaptor<UpdateSettingRequest> req = ArgumentCaptor.forClass(UpdateSettingRequest.class);
            verify(userSettingService).updateSettings(req.capture(), any());
            assertThat(req.getValue().getNightAccent()).isEqualTo(payload);
        }

        // ── @Size boundary values ────────────────────────────────────────────

        @Test
        void shouldAcceptThemeAtMaxLength() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"theme\":\"%s\"}".formatted(repeat('t', 30))))
                    .andExpect(status().isOk());

            verify(userSettingService).updateSettings(any(), eq(testUser));
        }

        @Test
        void shouldReturn400WhenThemeExceedsMaxLength() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"theme\":\"%s\"}".formatted(repeat('t', 31))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(userSettingService);
        }

        @Test
        void shouldAcceptLanguageAtMaxLength() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"%s\"}".formatted(repeat('l', 10))))
                    .andExpect(status().isOk());

            verify(userSettingService).updateSettings(any(), eq(testUser));
        }

        @Test
        void shouldReturn400WhenLanguageExceedsMaxLength() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"%s\"}".formatted(repeat('l', 11))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(userSettingService);
        }

        @Test
        void shouldAcceptMessagingPrivacyAtMaxLength() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenReturn(settings());

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"messagingPrivacy\":\"%s\"}".formatted(repeat('m', 20))))
                    .andExpect(status().isOk());

            verify(userSettingService).updateSettings(any(), eq(testUser));
        }

        @Test
        void shouldReturn400WhenMessagingPrivacyExceedsMaxLength() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"messagingPrivacy\":\"%s\"}".formatted(repeat('m', 21))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(userSettingService);
        }

        // ── Service-side enum validation (controller does no Enum.valueOf) ────

        @Test
        void shouldReturn400WhenServiceRejectsMessagingPrivacyEnum() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any()))
                    .thenThrow(new BadRequestException(
                            "messagingPrivacy must be EVERYONE or FRIENDS_ONLY", "TM_067"));

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"messagingPrivacy\":\"NONSENSE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_067"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsGroupAddPrivacyEnum() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any()))
                    .thenThrow(new BadRequestException(
                            "groupAddPrivacy must be EVERYONE, FRIENDS_ONLY or NOBODY", "TM_068"));

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupAddPrivacy\":\"NONSENSE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_068"));
        }

        // ── Service exception → status mapping ────────────────────────────────

        @Test
        void shouldReturn404WhenServiceThrowsNotFound() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any()))
                    .thenThrow(new NotFoundException("Settings not found", "TM_101"));

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"X\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenServiceThrowsForbidden() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any()))
                    .thenThrow(new ForbiddenException("Not permitted", "TM_103"));

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"X\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_103"));
        }

        @Test
        void shouldReturn409WhenServiceThrowsConflict() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any()))
                    .thenThrow(new ConflictException("Concurrent modification", "TM_109"));

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"X\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_109"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(userSettingService.updateSettings(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"X\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        // ── Request-binding failures (no dedicated handler → catch-all 500) ───

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // HttpMessageNotReadableException has no dedicated handler → catch-all 500 (pins behaviour).
            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(userSettingService);
        }

        @Test
        void shouldReturn500WhenBodyMissing() throws Exception {
            authenticate();
            // Absent required @RequestBody → HttpMessageNotReadableException → catch-all 500.
            mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(userSettingService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /settings/messaging-privacy  (param-based)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /settings/messaging-privacy")
    class UpdateMessagingPrivacy {

        private static final String URL = BASE + "/messaging-privacy";

        @Test
        void shouldReturn200AndForwardValueAndUser() throws Exception {
            authenticate();
            when(userSettingService.updateMessagingPrivacy(any(), any())).thenReturn(settings());

            mockMvc.perform(put(URL).param("value", "FRIENDS_ONLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Settings updated successfully"))
                    .andExpect(jsonPath("$.messageCode").value(UPDATED_CODE))
                    .andExpect(jsonPath("$.data.id").value(SETTING_ID));

            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(userSettingService).updateMessagingPrivacy(value.capture(), eq(testUser));
            assertThat(value.getValue()).isEqualTo("FRIENDS_ONLY");
        }

        @Test
        void shouldForwardRawValueVerbatimIncludingSuspiciousInput() throws Exception {
            authenticate();
            when(userSettingService.updateMessagingPrivacy(any(), any())).thenReturn(settings());

            String payload = "'; DROP TABLE users;--";
            mockMvc.perform(put(URL).param("value", payload))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(userSettingService).updateMessagingPrivacy(value.capture(), eq(testUser));
            assertThat(value.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn400WhenServiceRejectsInvalidEnum() throws Exception {
            authenticate();
            when(userSettingService.updateMessagingPrivacy(any(), any()))
                    .thenThrow(new BadRequestException(
                            "messagingPrivacy must be EVERYONE or FRIENDS_ONLY", "TM_067"));

            mockMvc.perform(put(URL).param("value", "NONSENSE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_067"));
        }

        @Test
        void shouldReturn500WhenValueParamMissing() throws Exception {
            authenticate();
            // Required @RequestParam absent → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(put(URL))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(userSettingService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(userSettingService.updateMessagingPrivacy(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(put(URL).param("value", "EVERYONE"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /settings/group-add-privacy  (param-based)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /settings/group-add-privacy")
    class UpdateGroupAddPrivacy {

        private static final String URL = BASE + "/group-add-privacy";

        @Test
        void shouldReturn200AndForwardValueAndUser() throws Exception {
            authenticate();
            when(userSettingService.updateGroupAddPrivacy(any(), any())).thenReturn(settings());

            mockMvc.perform(put(URL).param("value", "NOBODY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value(UPDATED_CODE))
                    .andExpect(jsonPath("$.data.groupAddPrivacy").value("FRIENDS_ONLY"));

            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(userSettingService).updateGroupAddPrivacy(value.capture(), eq(testUser));
            assertThat(value.getValue()).isEqualTo("NOBODY");
        }

        @Test
        void shouldReturn400WhenServiceRejectsInvalidEnum() throws Exception {
            authenticate();
            when(userSettingService.updateGroupAddPrivacy(any(), any()))
                    .thenThrow(new BadRequestException(
                            "groupAddPrivacy must be EVERYONE, FRIENDS_ONLY or NOBODY", "TM_068"));

            mockMvc.perform(put(URL).param("value", "NONSENSE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_068"));
        }

        @Test
        void shouldReturn500WhenValueParamMissing() throws Exception {
            authenticate();
            mockMvc.perform(put(URL))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(userSettingService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(userSettingService.updateGroupAddPrivacy(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(put(URL).param("value", "EVERYONE"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
