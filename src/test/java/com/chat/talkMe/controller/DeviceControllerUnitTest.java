package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.RegisterDeviceRequest;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.DeviceService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link DeviceController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link DeviceService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link AuthenticationPrincipalArgumentResolver}
 * for {@code @AuthenticationPrincipal}. No {@code Pageable} endpoint and no unboxed-primitive
 * {@code @RequestBody} field, so neither the {@code PageableHandlerMethodArgumentResolver}
 * nor the tolerant Jackson message converter is needed here.
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF) and
 * any {@code @PreAuthorize} method-security gates are enforced by the security layer / AOP
 * interceptor which is NOT active in a standalone MockMvc setup — those are covered by the
 * integration tests. Here we verify the controller's request/response wiring and its
 * delegation to the service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceController (unit)")
class DeviceControllerUnitTest {

    private static final String BASE = "/devices";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private DeviceService deviceService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        DeviceController controller = new DeviceController(deviceService);

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

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /devices  (register / upsert device profile)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /devices")
    class RegisterDevice {

        @Test
        void shouldReturn200AndForwardRequestWhenValid() throws Exception {
            authenticate();
            doNothing().when(deviceService).registerDevice(any(), any());

            String body = """
                    {"deviceToken":"tok-123","deviceType":"ANDROID","osVersion":"14"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Device profile registered successfully"))
                    .andExpect(jsonPath("$.messageCode").value("TM_055"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<RegisterDeviceRequest> req = ArgumentCaptor.forClass(RegisterDeviceRequest.class);
            verify(deviceService).registerDevice(req.capture(), eq(testUser));
            assertThat(req.getValue().getDeviceToken()).isEqualTo("tok-123");
            assertThat(req.getValue().getDeviceType()).isEqualTo("ANDROID");
            assertThat(req.getValue().getOsVersion()).isEqualTo("14");
        }

        @Test
        void shouldAcceptTokenOnlyWhenOptionalFieldsAbsent() throws Exception {
            authenticate();
            doNothing().when(deviceService).registerDevice(any(), any());

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceToken\":\"tok-123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_055"));

            ArgumentCaptor<RegisterDeviceRequest> req = ArgumentCaptor.forClass(RegisterDeviceRequest.class);
            verify(deviceService).registerDevice(req.capture(), eq(testUser));
            assertThat(req.getValue().getDeviceToken()).isEqualTo("tok-123");
            assertThat(req.getValue().getDeviceType()).isNull();
            assertThat(req.getValue().getOsVersion()).isNull();
        }

        @Test
        void shouldReturn400AndSkipServiceWhenDeviceTokenBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceToken\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(deviceService);
        }

        @Test
        void shouldReturn400WhenDeviceTokenMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(deviceService);
        }

        @Test
        void shouldReturn400WhenDeviceTokenTooLong() throws Exception {
            authenticate();
            String body = """
                    {"deviceToken":"%s"}""".formatted(repeat('t', 256));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(deviceService);
        }

        @Test
        void shouldReturn400WhenDeviceTypeTooLong() throws Exception {
            authenticate();
            String body = """
                    {"deviceToken":"tok","deviceType":"%s"}""".formatted(repeat('d', 51));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(deviceService);
        }

        @Test
        void shouldReturn400WhenOsVersionTooLong() throws Exception {
            authenticate();
            String body = """
                    {"deviceToken":"tok","osVersion":"%s"}""".formatted(repeat('v', 51));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(deviceService);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("User not found", "TM_101"))
                    .when(deviceService).registerDevice(any(), any());
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceToken\":\"tok\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));
        }

        @Test
        void shouldReturn403WhenServiceForbids() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Not permitted", "TM_005"))
                    .when(deviceService).registerDevice(any(), any());
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceToken\":\"tok\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_005"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(deviceService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom"))
                    .when(deviceService).registerDevice(any(), any());
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceToken\":\"tok\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /devices  (unregister device token)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /devices")
    class UnregisterDevice {

        @Test
        void shouldReturn200AndForwardTokenWhenValid() throws Exception {
            authenticate();
            doNothing().when(deviceService).unregisterDevice(any(), any());

            mockMvc.perform(delete(BASE).param("token", "tok-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Device token deleted successfully"))
                    .andExpect(jsonPath("$.messageCode").value("TM_265"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
            verify(deviceService).unregisterDevice(token.capture(), eq(testUser));
            assertThat(token.getValue()).isEqualTo("tok-123");
        }

        @Test
        void shouldReturn500WhenTokenParamMissing() throws Exception {
            authenticate();
            // Required @RequestParam absent → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(delete(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(deviceService);
        }

        @Test
        void shouldReturn404WhenTokenNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Device token not found", "TM_101"))
                    .when(deviceService).unregisterDevice(any(), any());
            mockMvc.perform(delete(BASE).param("token", "ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_101"));

            verify(deviceService).unregisterDevice(eq("ghost"), eq(testUser));
            verify(deviceService, never()).registerDevice(any(), any());
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom"))
                    .when(deviceService).unregisterDevice(any(), any());
            mockMvc.perform(delete(BASE).param("token", "tok-123"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
