package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.response.ChatKeyResponse;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ChatService;
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
 * Pure controller unit test for {@link ChatController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link ChatService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize("hasRole('USER')")} / {@code hasAnyRole(...)}
 * are enforced by Spring's method-security interceptor (AOP) which is NOT active in a standalone
 * MockMvc setup — those role gates are covered by the integration test. Here we verify the
 * controller's request/response wiring and its delegation to the service, which own the
 * membership/authorization checks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController (unit)")
class ChatControllerUnitTest {

    private static final String BASE = "/chats";
    private static final String CHAT_ID = "chat-uuid-1";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private ChatService chatService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ChatController controller = new ChatController(chatService);

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

    private static ChatResponse chat(String id, String type) {
        return ChatResponse.builder().id(id).name("A Chat").chatType(type).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats  (create)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats")
    class CreateChat {

        @Test
        void shouldReturn200AndForwardRecipientForPrivateChat() throws Exception {
            authenticate();
            when(chatService.createChat(any(), any())).thenReturn(chat(CHAT_ID, "PRIVATE"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":\"user-2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_120"))
                    .andExpect(jsonPath("$.data.id").value(CHAT_ID))
                    .andExpect(jsonPath("$.data.chatType").value("PRIVATE"));

            ArgumentCaptor<CreateChatRequest> req = ArgumentCaptor.forClass(CreateChatRequest.class);
            verify(chatService).createChat(req.capture(), eq(testUser));
            assertThat(req.getValue().getRecipientId()).isEqualTo("user-2");
        }

        @Test
        void shouldForwardNameAndMembersForGroupChat() throws Exception {
            authenticate();
            when(chatService.createChat(any(), any())).thenReturn(chat(CHAT_ID, "GROUP"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Squad\",\"memberIds\":[\"u1\",\"u2\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.chatType").value("GROUP"));

            ArgumentCaptor<CreateChatRequest> req = ArgumentCaptor.forClass(CreateChatRequest.class);
            verify(chatService).createChat(req.capture(), any());
            assertThat(req.getValue().getName()).isEqualTo("Squad");
            assertThat(req.getValue().getMemberIds()).containsExactly("u1", "u2");
        }

        @Test
        void shouldReturn404WhenRecipientNotFound() throws Exception {
            authenticate();
            when(chatService.createChat(any(), any()))
                    .thenThrow(new NotFoundException("Recipient not found", "TM_064"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":\"ghost\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldReturn403WhenMessagingRestricted() throws Exception {
            authenticate();
            when(chatService.createChat(any(), any()))
                    .thenThrow(new ForbiddenException("Recipient only accepts messages from friends", "TM_143"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":\"user-2\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_143"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(chatService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(chatService.createChat(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":\"user-2\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats  &  GET /chats/{id}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /chats")
    class GetChats {

        @Test
        void shouldReturn200WithChatList() throws Exception {
            authenticate();
            when(chatService.getChats(any())).thenReturn(List.of(chat(CHAT_ID, "PRIVATE")));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(CHAT_ID));

            verify(chatService).getChats(testUser);
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(chatService.getChats(any())).thenReturn(List.of());
            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldReturn200WithSingleChat() throws Exception {
            authenticate();
            when(chatService.getChatByUuid(eq(CHAT_ID), any())).thenReturn(chat(CHAT_ID, "PRIVATE"));

            mockMvc.perform(get(BASE + "/" + CHAT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(CHAT_ID));

            verify(chatService).getChatByUuid(CHAT_ID, testUser);
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(chatService.getChatByUuid(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));
            mockMvc.perform(get(BASE + "/" + CHAT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn403WhenNotAMemberOfChat() throws Exception {
            authenticate();
            when(chatService.getChatByUuid(any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));
            mockMvc.perform(get(BASE + "/" + CHAT_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{id}/key
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /chats/{id}/key")
    class GetChatKey {

        @Test
        void shouldReturn200WithKeyWhenEncryptionEnabled() throws Exception {
            authenticate();
            when(chatService.getChatKey(eq(CHAT_ID), any())).thenReturn(
                    ChatKeyResponse.builder().enabled(true).key("base64key").algo("AES-256-GCM").version(1).build());

            mockMvc.perform(get(BASE + "/" + CHAT_ID + "/key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.enabled").value(true))
                    .andExpect(jsonPath("$.data.key").value("base64key"))
                    .andExpect(jsonPath("$.data.version").value(1));

            verify(chatService).getChatKey(CHAT_ID, testUser);
        }

        @Test
        void shouldReturn200WithNullKeyWhenEncryptionDisabled() throws Exception {
            authenticate();
            when(chatService.getChatKey(any(), any())).thenReturn(
                    ChatKeyResponse.builder().enabled(false).key(null).build());

            mockMvc.perform(get(BASE + "/" + CHAT_ID + "/key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(jsonPath("$.data.key").doesNotExist());
        }

        @Test
        void shouldReturn403WhenRequesterNotAMember() throws Exception {
            authenticate();
            when(chatService.getChatKey(any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));
            mockMvc.perform(get(BASE + "/" + CHAT_ID + "/key"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Toggle endpoints: archive / mute / pin  (boolean query param)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT toggles (archive/mute/pin)")
    class Toggles {

        @Test
        void shouldArchiveWhenArchiveTrue() throws Exception {
            authenticate();
            doNothing().when(chatService).archiveChat(any(), any(), eq(true));

            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/archive").param("archive", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_122"))
                    .andExpect(jsonPath("$.message").value("Chat archived successfully"));

            verify(chatService).archiveChat(CHAT_ID, testUser, true);
        }

        @Test
        void shouldUnarchiveWhenArchiveFalse() throws Exception {
            authenticate();
            doNothing().when(chatService).archiveChat(any(), any(), eq(false));

            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/archive").param("archive", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_123"))
                    .andExpect(jsonPath("$.message").value("Chat unarchived successfully"));

            verify(chatService).archiveChat(CHAT_ID, testUser, false);
        }

        @Test
        void shouldReturn500WhenArchiveParamMissing() throws Exception {
            authenticate();
            // Required boolean param absent → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/archive"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(chatService);
        }

        @Test
        void shouldReturn404WhenArchivingMissingChat() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Chat not found", "TM_140"))
                    .when(chatService).archiveChat(any(), any(), eq(true));
            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/archive").param("archive", "true"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldMuteWhenMuteTrue() throws Exception {
            authenticate();
            doNothing().when(chatService).muteChat(any(), any(), eq(true));
            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/mute").param("mute", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_124"));
            verify(chatService).muteChat(CHAT_ID, testUser, true);
        }

        @Test
        void shouldUnmuteWhenMuteFalse() throws Exception {
            authenticate();
            doNothing().when(chatService).muteChat(any(), any(), eq(false));
            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/mute").param("mute", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_125"));
            verify(chatService).muteChat(CHAT_ID, testUser, false);
        }

        @Test
        void shouldPinWhenPinTrue() throws Exception {
            authenticate();
            doNothing().when(chatService).pinChat(any(), any(), eq(true));
            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/pin").param("pin", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_128"));
            verify(chatService).pinChat(CHAT_ID, testUser, true);
        }

        @Test
        void shouldUnpinWhenPinFalse() throws Exception {
            authenticate();
            doNothing().when(chatService).pinChat(any(), any(), eq(false));
            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/pin").param("pin", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_129"));
            verify(chatService).pinChat(CHAT_ID, testUser, false);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /chats/{id}/clear  &  DELETE /chats/{id}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE (clear / delete)")
    class ClearAndDelete {

        @Test
        void shouldReturn200WhenChatCleared() throws Exception {
            authenticate();
            doNothing().when(chatService).clearChat(any(), any());
            mockMvc.perform(delete(BASE + "/" + CHAT_ID + "/clear"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_126"));
            verify(chatService).clearChat(CHAT_ID, testUser);
        }

        @Test
        void shouldReturn200WhenChatDeleted() throws Exception {
            authenticate();
            doNothing().when(chatService).deleteChat(any(), any());
            mockMvc.perform(delete(BASE + "/" + CHAT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_127"));
            verify(chatService).deleteChat(CHAT_ID, testUser);
        }

        @Test
        void shouldReturn404WhenDeletingMissingChat() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Chat not found", "TM_140"))
                    .when(chatService).deleteChat(any(), any());
            mockMvc.perform(delete(BASE + "/" + CHAT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn403WhenDeleteNotPermitted() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Only the owner can delete", "TM_142"))
                    .when(chatService).deleteChat(any(), any());
            mockMvc.perform(delete(BASE + "/" + CHAT_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_142"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Read / unread / delivered status
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT read/unread/delivered")
    class Status {

        @Test
        void shouldMarkReadAndNotTouchUnread() throws Exception {
            authenticate();
            doNothing().when(chatService).markRead(any(), any());

            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_149"));

            verify(chatService).markRead(CHAT_ID, testUser);
            verify(chatService, never()).markUnread(any(), any());
        }

        @Test
        void shouldMarkUnread() throws Exception {
            authenticate();
            doNothing().when(chatService).markUnread(any(), any());

            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/unread"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_149"));

            verify(chatService).markUnread(CHAT_ID, testUser);
            verify(chatService, never()).markRead(any(), any());
        }

        @Test
        void shouldMarkDelivered() throws Exception {
            authenticate();
            doNothing().when(chatService).markDelivered(any(), any());

            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/delivered"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_150"));

            verify(chatService).markDelivered(CHAT_ID, testUser);
        }

        @Test
        void shouldMarkAllChatsDelivered() throws Exception {
            authenticate();
            doNothing().when(chatService).markAllChatsDelivered(any());

            mockMvc.perform(put(BASE + "/deliver-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_151"));

            verify(chatService).markAllChatsDelivered(testUser);
        }

        @Test
        void shouldReturn404WhenMarkingReadOnMissingChat() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Chat not found", "TM_140"))
                    .when(chatService).markRead(any(), any());
            mockMvc.perform(put(BASE + "/" + CHAT_ID + "/read"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }
    }
}
