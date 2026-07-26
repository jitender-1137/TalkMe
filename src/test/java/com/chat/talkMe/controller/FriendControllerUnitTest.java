package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.FriendRequestResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FriendService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
 * Pure controller unit test for {@link FriendController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link FriendService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> the class-level {@code @PreAuthorize("hasRole('USER')")} is enforced
 * by Spring's method-security interceptor, which is inactive in standalone MockMvc — covered by
 * the integration test. Cross-user / owner-mismatch authorization lives in the service and is
 * driven here by stubbing service exceptions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FriendController (unit)")
class FriendControllerUnitTest {

    private static final String BASE = "/friends";
    private static final String REQ_ID = "req-uuid-1";
    private static final String TARGET_ID = "user-2";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private FriendService friendService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        FriendController controller = new FriendController(friendService);

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

    private static FriendRequestResponse friendRequest(String id, String status) {
        return FriendRequestResponse.builder()
                .id(id).status(status)
                .sender(AuthUserResponse.builder().id("sender-1").username("sender").build())
                .build();
    }

    private static AuthUserResponse friend(String username) {
        return AuthUserResponse.builder().id("f-1").username(username).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /friends/requests
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /friends/requests")
    class SendRequest {

        @Test
        void shouldReturn200AndForwardReceiverId() throws Exception {
            authenticate();
            when(friendService.sendFriendRequest(eq(TARGET_ID), any()))
                    .thenReturn(friendRequest(REQ_ID, "PENDING"));

            mockMvc.perform(post(BASE + "/requests").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receiverId\":\"" + TARGET_ID + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_090"))
                    .andExpect(jsonPath("$.data.id").value(REQ_ID))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));

            ArgumentCaptor<String> receiver = ArgumentCaptor.forClass(String.class);
            verify(friendService).sendFriendRequest(receiver.capture(), eq(testUser));
            assertThat(receiver.getValue()).isEqualTo(TARGET_ID);
        }

        @Test
        void shouldForwardNullReceiverIdWhenKeyMissing() throws Exception {
            authenticate();
            // Controller reads payload.get("receiverId"); an absent key forwards null and the
            // service is responsible for rejecting it.
            when(friendService.sendFriendRequest(isNull(), any()))
                    .thenThrow(new BadRequestException("Receiver is required", "TM_095"));

            mockMvc.perform(post(BASE + "/requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_095"));

            verify(friendService).sendFriendRequest(isNull(), eq(testUser));
        }

        @Test
        void shouldReturn404WhenReceiverNotFound() throws Exception {
            authenticate();
            when(friendService.sendFriendRequest(any(), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_064"));
            mockMvc.perform(post(BASE + "/requests").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receiverId\":\"ghost\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn409WhenRequestAlreadyExists() throws Exception {
            authenticate();
            when(friendService.sendFriendRequest(any(), any()))
                    .thenThrow(new ConflictException("Friend request already sent", "TM_096"));
            mockMvc.perform(post(BASE + "/requests").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receiverId\":\"" + TARGET_ID + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_096"));
        }

        @Test
        void shouldReturn400WhenSendingToSelf() throws Exception {
            authenticate();
            when(friendService.sendFriendRequest(any(), any()))
                    .thenThrow(new BadRequestException("Cannot friend yourself", "TM_097"));
            mockMvc.perform(post(BASE + "/requests").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receiverId\":\"self\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_097"));
        }

        @Test
        void shouldReturn403WhenTargetHasBlockedRequester() throws Exception {
            authenticate();
            when(friendService.sendFriendRequest(any(), any()))
                    .thenThrow(new ForbiddenException("Cannot send request", "TM_069"));
            mockMvc.perform(post(BASE + "/requests").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receiverId\":\"" + TARGET_ID + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_069"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/requests").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(friendService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Accept / decline / cancel
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT/DELETE request lifecycle")
    class Lifecycle {

        @Test
        void shouldAcceptRequest() throws Exception {
            authenticate();
            doNothing().when(friendService).acceptFriendRequest(any(), any());

            mockMvc.perform(put(BASE + "/requests/" + REQ_ID + "/accept"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_091"));

            verify(friendService).acceptFriendRequest(REQ_ID, testUser);
            verify(friendService, never()).rejectFriendRequest(any(), any());
        }

        @Test
        void shouldReturn404WhenAcceptingMissingRequest() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Request not found", "TM_099"))
                    .when(friendService).acceptFriendRequest(any(), any());
            mockMvc.perform(put(BASE + "/requests/" + REQ_ID + "/accept"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_099"));
        }

        @Test
        void shouldReturn403WhenAcceptingSomeoneElsesRequest() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Not the recipient of this request", "TM_069"))
                    .when(friendService).acceptFriendRequest(any(), any());
            mockMvc.perform(put(BASE + "/requests/" + REQ_ID + "/accept"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_069"));
        }

        @Test
        void shouldDeclineRequest() throws Exception {
            authenticate();
            doNothing().when(friendService).rejectFriendRequest(any(), any());

            mockMvc.perform(put(BASE + "/requests/" + REQ_ID + "/decline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_092"));

            verify(friendService).rejectFriendRequest(REQ_ID, testUser);
            verify(friendService, never()).acceptFriendRequest(any(), any());
        }

        @Test
        void shouldCancelRequest() throws Exception {
            authenticate();
            doNothing().when(friendService).cancelFriendRequest(any(), any());

            mockMvc.perform(delete(BASE + "/requests/" + REQ_ID + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_093"));

            verify(friendService).cancelFriendRequest(REQ_ID, testUser);
        }

        @Test
        void shouldReturn404WhenCancelingMissingRequest() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Request not found", "TM_099"))
                    .when(friendService).cancelFriendRequest(any(), any());
            mockMvc.perform(delete(BASE + "/requests/" + REQ_ID + "/cancel"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_099"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /friends  &  GET /friends/requests
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET lists")
    class Lists {

        @Test
        void shouldReturn200WithFriendList() throws Exception {
            authenticate();
            when(friendService.getFriends(any())).thenReturn(List.of(friend("alice")));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].username").value("alice"));

            verify(friendService).getFriends(testUser);
        }

        @Test
        void shouldReturn200WithEmptyFriendList() throws Exception {
            authenticate();
            when(friendService.getFriends(any())).thenReturn(List.of());
            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldReturn200WithPendingRequests() throws Exception {
            authenticate();
            when(friendService.getFriendRequests(any()))
                    .thenReturn(List.of(friendRequest(REQ_ID, "PENDING")));

            mockMvc.perform(get(BASE + "/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(REQ_ID))
                    .andExpect(jsonPath("$.data[0].status").value("PENDING"));

            verify(friendService).getFriendRequests(testUser);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Remove friend / block / unblock
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Remove / block / unblock")
    class RemoveBlock {

        @Test
        void shouldRemoveFriend() throws Exception {
            authenticate();
            doNothing().when(friendService).removeFriend(any(), any());

            mockMvc.perform(delete(BASE + "/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_098"));

            verify(friendService).removeFriend(TARGET_ID, testUser);
        }

        @Test
        void shouldReturn404WhenRemovingNonFriend() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Not friends", "TM_099"))
                    .when(friendService).removeFriend(any(), any());
            mockMvc.perform(delete(BASE + "/" + TARGET_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_099"));
        }

        @Test
        void shouldBlockUser() throws Exception {
            authenticate();
            doNothing().when(friendService).blockUser(any(), any());

            mockMvc.perform(post(BASE + "/block/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_067"));

            verify(friendService).blockUser(TARGET_ID, testUser);
            verify(friendService, never()).unblockUser(any(), any());
        }

        @Test
        void shouldReturn400WhenBlockingSelf() throws Exception {
            authenticate();
            doThrow(new BadRequestException("Cannot block yourself", "TM_070"))
                    .when(friendService).blockUser(any(), any());
            mockMvc.perform(post(BASE + "/block/self"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_070"));
        }

        @Test
        void shouldUnblockUser() throws Exception {
            authenticate();
            doNothing().when(friendService).unblockUser(any(), any());

            mockMvc.perform(delete(BASE + "/block/" + TARGET_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_068"));

            verify(friendService).unblockUser(TARGET_ID, testUser);
            verify(friendService, never()).blockUser(any(), any());
        }

        @Test
        void shouldReturn404WhenUnblockingUserNotBlocked() throws Exception {
            authenticate();
            doThrow(new NotFoundException("User is not blocked", "TM_099"))
                    .when(friendService).unblockUser(any(), any());
            mockMvc.perform(delete(BASE + "/block/" + TARGET_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_099"));
        }
    }
}
