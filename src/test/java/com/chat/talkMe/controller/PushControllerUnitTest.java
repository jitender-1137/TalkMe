package com.chat.talkMe.controller;

import com.chat.talkMe.config.WebPushProperties;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SavePushSubscriptionRequest;
import com.chat.talkMe.enums.InstallationType;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.security.JwtTokenProvider;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.ChatService;
import com.chat.talkMe.service.NotificationDispatchService;
import com.chat.talkMe.service.WebPushService;
import io.jsonwebtoken.Claims;
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

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link PushController}.
 *
 * <p>Standalone {@link MockMvc} with mocked collaborators ({@link WebPushService},
 * {@link UserRepository}, {@link NotificationDispatchService}, {@link JwtTokenProvider},
 * {@link ChatService}) and the real {@link GlobalExceptionHandler}. The VAPID key is served
 * from a real {@link WebPushProperties} instance (a bean, not a {@code @Value} field) whose
 * public key is set via its setter in {@code setUp}. {@link AuthenticationPrincipalArgumentResolver}
 * resolves {@code @AuthenticationPrincipal} for the authenticated endpoints.
 *
 * <p>No tolerant Jackson mapper is registered (no {@code @RequestBody} DTO has an unboxed
 * primitive field) and no {@link org.springframework.data.web.PageableHandlerMethodArgumentResolver}
 * is needed (no endpoint takes a {@code Pageable}).
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT bearer auth, roles,
 * CSRF) is enforced by Spring's security filter chain, which is NOT active in a standalone
 * MockMvc setup — those gates are covered by the integration tests. The two public endpoints
 * ({@code /vapid-public-key}, {@code /delivered}) and the param-only {@code DELETE /subscribe}
 * carry no {@code @AuthenticationPrincipal} and are exercised without a security context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PushController (unit)")
class PushControllerUnitTest {

    private static final String BASE = "/push";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String VAPID_KEY = "BPublicVapidKey_abc123";

    @Mock
    private WebPushService webPushService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationDispatchService notificationDispatchService;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private ChatService chatService;

    private WebPushProperties webPushProperties;
    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        webPushProperties = new WebPushProperties();
        webPushProperties.getVapid().setPublicKey(VAPID_KEY);

        PushController controller = new PushController(
                webPushService, webPushProperties, userRepository,
                notificationDispatchService, jwtTokenProvider, chatService);

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

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /push/vapid-public-key   (PUBLIC — no @AuthenticationPrincipal)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /vapid-public-key (public)")
    class VapidPublicKey {

        @Test
        void shouldReturnPublicKeyWhenRequested() throws Exception {
            // Public endpoint — deliberately not authenticated.
            mockMvc.perform(get(BASE + "/vapid-public-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.publicKey").value(VAPID_KEY));

            verifyNoInteractions(webPushService, userRepository,
                    notificationDispatchService, jwtTokenProvider, chatService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /push/subscribe   (authenticated, @Valid body)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /subscribe")
    class Subscribe {

        @Test
        void shouldReturn200AndForwardSubscriptionWhenValid() throws Exception {
            authenticate();
            doNothing().when(webPushService).saveSubscription(any(), any());

            String body = """
                    {"endpoint":"https://push.example/ep-1","p256dh":"p256dh-key","auth":"auth-key","installationType":"IOS_HOME"}""";
            mockMvc.perform(post(BASE + "/subscribe").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Push subscription saved"))
                    .andExpect(jsonPath("$.messageCode").value("TM_280"));

            ArgumentCaptor<SavePushSubscriptionRequest> req =
                    ArgumentCaptor.forClass(SavePushSubscriptionRequest.class);
            verify(webPushService).saveSubscription(eq(testUser), req.capture());
            assertThat(req.getValue().getEndpoint()).isEqualTo("https://push.example/ep-1");
            assertThat(req.getValue().getP256dh()).isEqualTo("p256dh-key");
            assertThat(req.getValue().getAuth()).isEqualTo("auth-key");
            assertThat(req.getValue().getInstallationType()).isEqualTo(InstallationType.IOS_HOME);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenEndpointBlank() throws Exception {
            authenticate();
            String body = """
                    {"endpoint":"","p256dh":"p256dh-key","auth":"auth-key"}""";
            mockMvc.perform(post(BASE + "/subscribe").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(webPushService);
        }

        @Test
        void shouldReturn400WhenAuthKeyMissing() throws Exception {
            authenticate();
            String body = """
                    {"endpoint":"https://push.example/ep-1","p256dh":"p256dh-key"}""";
            mockMvc.perform(post(BASE + "/subscribe").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(webPushService);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unparseable JSON → HttpMessageNotReadableException → catch-all 500 (pins current behaviour).
            mockMvc.perform(post(BASE + "/subscribe").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(webPushService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom")).when(webPushService).saveSubscription(any(), any());
            String body = """
                    {"endpoint":"https://push.example/ep-1","p256dh":"p256dh-key","auth":"auth-key"}""";
            mockMvc.perform(post(BASE + "/subscribe").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /push/subscribe   (no @AuthenticationPrincipal — endpoint param only)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /subscribe")
    class Unsubscribe {

        @Test
        void shouldReturn200AndForwardEndpointWhenUnsubscribing() throws Exception {
            // No @AuthenticationPrincipal on this endpoint — not authenticated.
            doNothing().when(webPushService).removeSubscription(any());

            mockMvc.perform(delete(BASE + "/subscribe").param("endpoint", "https://push.example/ep-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Push subscription removed"))
                    .andExpect(jsonPath("$.messageCode").value("TM_281"));

            ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
            verify(webPushService).removeSubscription(endpoint.capture());
            assertThat(endpoint.getValue()).isEqualTo("https://push.example/ep-1");
        }

        @Test
        void shouldReturn500WhenEndpointParamMissing() throws Exception {
            // Required @RequestParam absent → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(delete(BASE + "/subscribe"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(webPushService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /push/installation   (authenticated, @Valid body)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /installation")
    class Installation {

        @Test
        void shouldReturn200AndPersistInstallationType() throws Exception {
            authenticate();
            when(userRepository.save(any())).thenReturn(testUser);

            mockMvc.perform(put(BASE + "/installation").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"installationType\":\"PWA\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Installation type updated"))
                    .andExpect(jsonPath("$.messageCode").value("TM_282"));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getInstallationType()).isEqualTo(InstallationType.PWA);
        }

        @Test
        void shouldReturn400AndSkipSaveWhenInstallationTypeMissing() throws Exception {
            authenticate();
            mockMvc.perform(put(BASE + "/installation").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // Unparseable JSON → HttpMessageNotReadableException → catch-all 500 (pins current behaviour).
            mockMvc.perform(put(BASE + "/installation").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(userRepository);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /push/unread-count   (authenticated)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /unread-count")
    class UnreadCount {

        @Test
        void shouldReturn200WithRecomputedCount() throws Exception {
            authenticate();
            when(notificationDispatchService.recomputeUnread(any())).thenReturn(7);

            mockMvc.perform(get(BASE + "/unread-count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.totalUnread").value(7));

            verify(notificationDispatchService).recomputeUnread(testUser);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(notificationDispatchService.recomputeUnread(any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(get(BASE + "/unread-count"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /push/delivered   (PUBLIC — signed delivery token in body is the authz)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /delivered (public)")
    class AckDelivered {

        @Test
        void shouldReturn200AndMarkDeliveredWhenTokenValid() throws Exception {
            // Public endpoint — not authenticated; the delivery token is the authorization.
            Claims claims = mock(Claims.class);
            when(claims.getSubject()).thenReturn("testuser");
            when(claims.get("chatUuid", String.class)).thenReturn("chat-uuid-1");
            when(jwtTokenProvider.parseDeliveryToken("tok-1")).thenReturn(claims);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

            mockMvc.perform(post(BASE + "/delivered").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"tok-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("ok"))
                    .andExpect(jsonPath("$.messageCode").value("TM_283"));

            verify(chatService).markDelivered("chat-uuid-1", testUser);
        }

        @Test
        void shouldReturn200AndSkipWhenTokenAbsent() throws Exception {
            mockMvc.perform(post(BASE + "/delivered").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_283"));

            verifyNoInteractions(jwtTokenProvider, userRepository, chatService);
        }

        @Test
        void shouldReturn200AndSkipWhenTokenInvalid() throws Exception {
            when(jwtTokenProvider.parseDeliveryToken("bad-tok")).thenReturn(null);

            mockMvc.perform(post(BASE + "/delivered").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"bad-tok\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_283"));

            verify(jwtTokenProvider).parseDeliveryToken("bad-tok");
            verifyNoInteractions(userRepository, chatService);
        }

        @Test
        void shouldReturn200AndSkipWhenUserNotFound() throws Exception {
            Claims claims = mock(Claims.class);
            when(claims.getSubject()).thenReturn("ghost");
            when(claims.get("chatUuid", String.class)).thenReturn("chat-uuid-1");
            when(jwtTokenProvider.parseDeliveryToken("tok-2")).thenReturn(claims);
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            mockMvc.perform(post(BASE + "/delivered").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"tok-2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_283"));

            verify(chatService, never()).markDelivered(any(), any());
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            // Unparseable JSON → HttpMessageNotReadableException → catch-all 500 (pins current behaviour).
            mockMvc.perform(post(BASE + "/delivered").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(jwtTokenProvider, userRepository, chatService);
        }
    }
}
