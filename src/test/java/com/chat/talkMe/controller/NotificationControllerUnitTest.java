package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NotificationResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.NotificationService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link NotificationController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link NotificationService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link PageableHandlerMethodArgumentResolver}
 * (the {@code GET /notifications} endpoint takes a {@code @PageableDefault} {@code Pageable})
 * and {@link AuthenticationPrincipalArgumentResolver} for {@code @AuthenticationPrincipal}.
 *
 * <p>No endpoint declares a {@code @RequestBody}, so no custom JSON message converter is
 * registered — the standalone default Jackson mapper suffices.
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF) and
 * any {@code @PreAuthorize} method-security gates are enforced by Spring's security layer,
 * which is NOT active in a standalone MockMvc setup — those are covered by integration tests.
 * Here we verify the controller's request/response wiring and its delegation to the service,
 * which owns the ownership/existence checks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationController (unit)")
class NotificationControllerUnitTest {

    private static final String BASE = "/notifications";
    private static final String NOTIF_ID = "notif-uuid-1";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private NotificationService notificationService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        NotificationController controller = new NotificationController(notificationService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new PageableHandlerMethodArgumentResolver())
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

    private static NotificationResponse notification(String id) {
        return NotificationResponse.builder()
                .id(id).title("New like").content("Alice liked your post").type("LIKE")
                .isRead(false).referenceId("post-1")
                .actorId("actor-1").actorName("Alice").actorAvatar("https://cdn/a.png")
                .imageUrl("https://cdn/post.png").createdAt("2026-07-21T10:00:00Z")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /notifications  (paginated)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /notifications")
    class GetNotifications {

        @Test
        void shouldReturn200WithNotificationPage() throws Exception {
            authenticate();
            Page<NotificationResponse> page =
                    new PageImpl<>(List.of(notification(NOTIF_ID)), PageRequest.of(0, 20), 1);
            when(notificationService.getNotifications(any(), any())).thenReturn(page);

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.data.content[0].id").value(NOTIF_ID))
                    .andExpect(jsonPath("$.data.content[0].title").value("New like"))
                    .andExpect(jsonPath("$.data.content[0].type").value("LIKE"))
                    // Lombok boolean field `isRead` → JSON property `read`.
                    .andExpect(jsonPath("$.data.content[0].read").value(false))
                    .andExpect(jsonPath("$.data.content[0].actorName").value("Alice"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        void shouldReturn200WithEmptyPage() throws Exception {
            authenticate();
            when(notificationService.getNotifications(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        void shouldForwardAuthenticatedUserToService() throws Exception {
            authenticate();
            when(notificationService.getNotifications(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            verify(notificationService).getNotifications(any(Pageable.class), eq(testUser));
        }

        @Test
        void shouldApplyDefaultPageSizeAndSort() throws Exception {
            authenticate();
            when(notificationService.getNotifications(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationService).getNotifications(pageable.capture(), eq(testUser));
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(0);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        void shouldHonorCustomPageAndSizeParams() throws Exception {
            authenticate();
            when(notificationService.getNotifications(any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

            mockMvc.perform(get(BASE).param("page", "2").param("size", "5"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationService).getNotifications(pageable.capture(), eq(testUser));
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(notificationService.getNotifications(any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /notifications/{id}/read
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /notifications/{id}/read")
    class MarkAsRead {

        @Test
        void shouldReturn200AndForwardIdAndUser() throws Exception {
            authenticate();
            doNothing().when(notificationService).markAsRead(any(), any());

            mockMvc.perform(put(BASE + "/" + NOTIF_ID + "/read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_252"))
                    .andExpect(jsonPath("$.message").value("Notification marked as read"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(notificationService).markAsRead(NOTIF_ID, testUser);
            verify(notificationService, never()).markAllAsRead(any());
        }

        @Test
        void shouldReturn404WhenNotificationNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Notification not found", "TM_251"))
                    .when(notificationService).markAsRead(any(), any());

            mockMvc.perform(put(BASE + "/" + NOTIF_ID + "/read"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_251"));
        }

        @Test
        void shouldReturn403WhenNotOwner() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Not your notification", "TM_254"))
                    .when(notificationService).markAsRead(any(), any());

            mockMvc.perform(put(BASE + "/" + NOTIF_ID + "/read"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_254"));
        }

        @Test
        void shouldReturn400WhenServiceRejectsInvalidId() throws Exception {
            authenticate();
            // A malformed UUID surfaces as IllegalArgumentException → GlobalExceptionHandler 400/TM_071.
            doThrow(new IllegalArgumentException("bad id"))
                    .when(notificationService).markAsRead(any(), any());

            mockMvc.perform(put(BASE + "/" + NOTIF_ID + "/read"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom"))
                    .when(notificationService).markAsRead(any(), any());

            mockMvc.perform(put(BASE + "/" + NOTIF_ID + "/read"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /notifications/read-all
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /notifications/read-all")
    class MarkAllAsRead {

        @Test
        void shouldReturn200AndForwardUser() throws Exception {
            authenticate();
            doNothing().when(notificationService).markAllAsRead(any());

            mockMvc.perform(put(BASE + "/read-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_253"))
                    .andExpect(jsonPath("$.message").value("All notifications marked as read"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(notificationService).markAllAsRead(testUser);
            verify(notificationService, never()).markAsRead(any(), any());
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom"))
                    .when(notificationService).markAllAsRead(any());

            mockMvc.perform(put(BASE + "/read-all"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
