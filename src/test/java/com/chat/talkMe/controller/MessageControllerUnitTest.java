package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.ReactToMessageRequest;
import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.response.MessagePageResponse;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.MessageService;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link MessageController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link MessageService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link PageableHandlerMethodArgumentResolver}
 * (needed for the {@code /search} endpoint's {@code Pageable}) and
 * {@link AuthenticationPrincipalArgumentResolver} for {@code @AuthenticationPrincipal}.
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF, chat
 * membership enforced by the security layer) is out of scope — the controller relies on the
 * security filter chain + service-layer checks, which the integration tests exercise.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageController (unit)")
class MessageControllerUnitTest {

    private static final String CHAT = "chat-uuid-1";
    private static final String BASE = "/chats/" + CHAT + "/messages";
    private static final String MSG = "msg-uuid-1";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private MessageService messageService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        MessageController controller = new MessageController(messageService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // Match the app's runtime JSON semantics: the app publishes a @Primary Jackson ObjectMapper
        // that tolerates absent/null primitives (e.g. SendMessageRequest.forwarded defaults to false).
        // The standalone default Jackson-3 mapper is stricter, so align it here.
        JsonMapper jsonMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
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

    private static MessageResponse msg(String id, String content) {
        return MessageResponse.builder()
                .id(id).chatId(CHAT).content(content)
                .messageType("TEXT").status("SENT").build();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/messages
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST (send message)")
    class SendMessage {

        @Test
        void shouldReturn200AndForwardArgumentsWhenValid() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any())).thenReturn(msg(MSG, "hello"));

            String body = """
                    {"content":"hello","clientId":"cid-1","messageType":"TEXT"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Message sent successfully"))
                    .andExpect(jsonPath("$.messageCode").value("TM_160"))
                    .andExpect(jsonPath("$.data.id").value(MSG))
                    .andExpect(jsonPath("$.data.content").value("hello"));

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(messageService).sendMessage(chatId.capture(), req.capture(), eq(testUser));
            assertThat(chatId.getValue()).isEqualTo(CHAT);
            assertThat(req.getValue().getContent()).isEqualTo("hello");
            assertThat(req.getValue().getClientId()).isEqualTo("cid-1");
            assertThat(req.getValue().getMessageType()).isEqualTo("TEXT");
        }

        @Test
        void shouldAllowEmptyContentForMediaOnlyMessage() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any())).thenReturn(msg(MSG, ""));

            String body = """
                    {"content":"","messageType":"IMAGE","fileUrl":"https://cdn/x.png","mimeType":"image/png"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(messageService).sendMessage(any(), req.capture(), any());
            assertThat(req.getValue().getFileUrl()).isEqualTo("https://cdn/x.png");
        }

        @Test
        void shouldForwardAllowDownloadFlagForMediaMessage() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any()))
                    .thenReturn(MessageResponse.builder()
                            .id(MSG).chatId(CHAT).content("")
                            .messageType("IMAGE").status("SENT").allowDownload(true).build());

            String body = """
                    {"content":"","messageType":"IMAGE","fileUrl":"https://cdn/x.png","mimeType":"image/png","allowDownload":true}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.allowDownload").value(true));

            ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(messageService).sendMessage(any(), req.capture(), any());
            assertThat(req.getValue().isAllowDownload()).isTrue();
        }

        @Test
        void shouldDefaultAllowDownloadToFalseWhenOmitted() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any()))
                    .thenReturn(MessageResponse.builder()
                            .id(MSG).chatId(CHAT).content("")
                            .messageType("IMAGE").status("SENT").allowDownload(false).build());

            // No allowDownload key → the tolerant converter defaults the primitive to false.
            String body = """
                    {"content":"","messageType":"IMAGE","fileUrl":"https://cdn/x.png","mimeType":"image/png"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.allowDownload").value(false));

            ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(messageService).sendMessage(any(), req.capture(), any());
            assertThat(req.getValue().isAllowDownload()).isFalse();
        }

        @Test
        void shouldAcceptUnicodeAndEmojiContent() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any())).thenReturn(msg(MSG, "hi 😀 名前"));

            String body = """
                    {"content":"hi 😀 名前"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(messageService).sendMessage(any(), req.capture(), any());
            assertThat(req.getValue().getContent()).isEqualTo("hi 😀 名前");
        }

        @Test
        void shouldReturn400AndSkipServiceWhenContentTooLong() throws Exception {
            authenticate();
            String body = """
                    {"content":"%s"}""".formatted(repeat('x', 8193));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(messageService);
        }

        @Test
        void shouldReturn400WhenClientIdTooLong() throws Exception {
            authenticate();
            String body = """
                    {"content":"hi","clientId":"%s"}""".formatted(repeat('c', 101));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(messageService);
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn422WhenContentModerationBlocks() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any()))
                    .thenThrow(new ServiceException(422, "Message blocked by moderation", "TM_143"));

            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"bad\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.messageCode").value("TM_143"));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(messageService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(messageService.sendMessage(any(), any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatId}/messages  (cursor pagination)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET (list messages)")
    class GetMessages {

        @Test
        void shouldReturn200WithPage() throws Exception {
            authenticate();
            MessagePageResponse page = MessagePageResponse.builder()
                    .items(List.of(msg(MSG, "hi"))).nextCursor(42L).hasMore(true).build();
            when(messageService.getMessages(any(), any(), anyInt(), any())).thenReturn(page);

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items[0].id").value(MSG))
                    .andExpect(jsonPath("$.data.nextCursor").value(42))
                    .andExpect(jsonPath("$.data.hasMore").value(true));
        }

        @Test
        void shouldUseDefaultLimitAndNullCursorWhenParamsAbsent() throws Exception {
            authenticate();
            when(messageService.getMessages(any(), any(), anyInt(), any()))
                    .thenReturn(MessagePageResponse.builder().items(List.of()).hasMore(false).build());

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            ArgumentCaptor<Long> cursor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getMessages(eq(CHAT), cursor.capture(), limit.capture(), eq(testUser));
            assertThat(cursor.getValue()).isNull();
            assertThat(limit.getValue()).isEqualTo(30);
        }

        @Test
        void shouldForwardCursorAndLimitQueryParams() throws Exception {
            authenticate();
            when(messageService.getMessages(any(), any(), anyInt(), any()))
                    .thenReturn(MessagePageResponse.builder().items(List.of()).hasMore(false).build());

            mockMvc.perform(get(BASE).param("cursor", "100").param("limit", "10"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Long> cursor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getMessages(eq(CHAT), cursor.capture(), limit.capture(), eq(testUser));
            assertThat(cursor.getValue()).isEqualTo(100L);
            assertThat(limit.getValue()).isEqualTo(10);
        }

        @Test
        void shouldReturn500WhenCursorNotNumeric() throws Exception {
            authenticate();
            // Type-mismatch on the cursor param has no dedicated handler → catch-all 500 (pins current behaviour).
            mockMvc.perform(get(BASE).param("cursor", "abc"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(messageService);
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(messageService.getMessages(any(), any(), anyInt(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));
            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatId}/messages/sync
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /sync")
    class SyncMessages {

        @Test
        void shouldReturn200WithMessagesAfterSequence() throws Exception {
            authenticate();
            when(messageService.getMessagesAfter(eq(CHAT), eq(5L), eq(testUser)))
                    .thenReturn(List.of(msg(MSG, "hi")));

            mockMvc.perform(get(BASE + "/sync").param("afterSequence", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(MSG));

            verify(messageService).getMessagesAfter(CHAT, 5L, testUser);
        }

        @Test
        void shouldReturn500WhenAfterSequenceMissing() throws Exception {
            authenticate();
            // Missing required param → MissingServletRequestParameterException → catch-all 500.
            mockMvc.perform(get(BASE + "/sync"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(messageService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatId}/messages/search
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /search")
    class SearchMessages {

        @Test
        void shouldReturn200WithSearchResults() throws Exception {
            authenticate();
            Page<MessageResponse> page = new PageImpl<>(
                    List.of(msg(MSG, "found it")), PageRequest.of(0, 50), 1);
            when(messageService.searchMessages(any(), any(), any(), any())).thenReturn(page);

            mockMvc.perform(get(BASE + "/search").param("query", "found"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].id").value(MSG));

            verify(messageService).searchMessages(eq(CHAT), eq("found"), any(Pageable.class), eq(testUser));
        }

        @Test
        void shouldApplyDefaultPageSizeAndSort() throws Exception {
            authenticate();
            when(messageService.searchMessages(any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

            mockMvc.perform(get(BASE + "/search").param("query", "x")).andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(messageService).searchMessages(any(), eq("x"), pageable.capture(), any());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
            Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        void shouldHonorCustomPageAndSizeParams() throws Exception {
            authenticate();
            when(messageService.searchMessages(any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

            mockMvc.perform(get(BASE + "/search").param("query", "x").param("page", "2").param("size", "5"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(messageService).searchMessages(any(), any(), pageable.capture(), any());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }

        @Test
        void shouldReturn500WhenQueryMissing() throws Exception {
            authenticate();
            mockMvc.perform(get(BASE + "/search"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(messageService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PATCH /chats/{chatId}/messages/{messageId}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH (edit message)")
    class EditMessage {

        @Test
        void shouldReturn200AndForwardNewContent() throws Exception {
            authenticate();
            when(messageService.editMessage(any(), any(), any(), any())).thenReturn(msg(MSG, "edited"));

            mockMvc.perform(patch(BASE + "/" + MSG)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"edited\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_167"))
                    .andExpect(jsonPath("$.data.content").value("edited"));

            verify(messageService).editMessage(CHAT, MSG, "edited", testUser);
        }

        @Test
        void shouldReturn404WhenMessageNotFound() throws Exception {
            authenticate();
            when(messageService.editMessage(any(), any(), any(), any()))
                    .thenThrow(new NotFoundException("Message not found", "TM_161"));
            mockMvc.perform(patch(BASE + "/" + MSG)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_161"));
        }

        @Test
        void shouldReturn403WhenNotTheSender() throws Exception {
            authenticate();
            when(messageService.editMessage(any(), any(), any(), any()))
                    .thenThrow(new ForbiddenException("Only the sender can edit", "TM_168"));
            mockMvc.perform(patch(BASE + "/" + MSG)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_168"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /chats/{chatId}/messages/{messageId}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE (delete message)")
    class DeleteMessage {

        @Test
        void shouldReturn200WhenDeleted() throws Exception {
            authenticate();
            doNothing().when(messageService).deleteMessage(any(), any(), any());

            mockMvc.perform(delete(BASE + "/" + MSG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_163"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(messageService).deleteMessage(CHAT, MSG, testUser);
        }

        @Test
        void shouldReturn404WhenMessageNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Message not found", "TM_161"))
                    .when(messageService).deleteMessage(any(), any(), any());
            mockMvc.perform(delete(BASE + "/" + MSG))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_161"));
        }

        @Test
        void shouldReturn403WhenNotAllowed() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Not allowed", "TM_168"))
                    .when(messageService).deleteMessage(any(), any(), any());
            mockMvc.perform(delete(BASE + "/" + MSG))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_168"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Self-destruct: reveal / consume
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /reveal & /consume")
    class SelfDestruct {

        @Test
        void shouldReturn200WhenRevealed() throws Exception {
            authenticate();
            when(messageService.revealSelfDestruct(any(), any(), any())).thenReturn(msg(MSG, "secret"));

            mockMvc.perform(post(BASE + "/" + MSG + "/reveal"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(MSG));

            verify(messageService).revealSelfDestruct(CHAT, MSG, testUser);
        }

        @Test
        void shouldReturn403WhenNonReceiverReveals() throws Exception {
            authenticate();
            when(messageService.revealSelfDestruct(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Only the receiver can open this", "TM_165"));
            mockMvc.perform(post(BASE + "/" + MSG + "/reveal"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_165"));
        }

        @Test
        void shouldReturn200WhenConsumed() throws Exception {
            authenticate();
            doNothing().when(messageService).consumeSelfDestruct(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + MSG + "/consume"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_164"));

            verify(messageService).consumeSelfDestruct(CHAT, MSG, testUser);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Pin / unpin
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST/DELETE /pin")
    class Pin {

        @Test
        void shouldPinWithPinnedTrue() throws Exception {
            authenticate();
            when(messageService.setMessagePinned(any(), any(), eq(true), any())).thenReturn(msg(MSG, "hi"));

            mockMvc.perform(post(BASE + "/" + MSG + "/pin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_287"));

            verify(messageService).setMessagePinned(CHAT, MSG, true, testUser);
        }

        @Test
        void shouldUnpinWithPinnedFalse() throws Exception {
            authenticate();
            when(messageService.setMessagePinned(any(), any(), eq(false), any())).thenReturn(msg(MSG, "hi"));

            mockMvc.perform(delete(BASE + "/" + MSG + "/pin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_288"));

            verify(messageService).setMessagePinned(CHAT, MSG, false, testUser);
        }

        @Test
        void shouldReturn403WhenPinNotPermitted() throws Exception {
            authenticate();
            when(messageService.setMessagePinned(any(), any(), eq(true), any()))
                    .thenThrow(new ForbiddenException("Not permitted to pin", "TM_289"));
            mockMvc.perform(post(BASE + "/" + MSG + "/pin"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_289"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Star / unstar
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST/DELETE /star")
    class Star {

        @Test
        void shouldStarWithStarredTrue() throws Exception {
            authenticate();
            doNothing().when(messageService).setMessageStarred(any(), any(), eq(true), any());

            mockMvc.perform(post(BASE + "/" + MSG + "/star"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_308"));

            verify(messageService).setMessageStarred(CHAT, MSG, true, testUser);
        }

        @Test
        void shouldUnstarWithStarredFalse() throws Exception {
            authenticate();
            doNothing().when(messageService).setMessageStarred(any(), any(), eq(false), any());

            mockMvc.perform(delete(BASE + "/" + MSG + "/star"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_309"));

            verify(messageService).setMessageStarred(CHAT, MSG, false, testUser);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Reactions
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reactions")
    class Reactions {

        @Test
        void shouldReturn200AndForwardEmojiWhenReacting() throws Exception {
            authenticate();
            when(messageService.reactToMessage(any(), any(), any(), any())).thenReturn(msg(MSG, "hi"));

            mockMvc.perform(post(BASE + "/" + MSG + "/reactions")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"emoji\":\"👍\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_152"));

            ArgumentCaptor<ReactToMessageRequest> req = ArgumentCaptor.forClass(ReactToMessageRequest.class);
            verify(messageService).reactToMessage(eq(CHAT), eq(MSG), req.capture(), eq(testUser));
            assertThat(req.getValue().getEmoji()).isEqualTo("👍");
        }

        @Test
        void shouldReturn400AndSkipServiceWhenEmojiBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + MSG + "/reactions")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"emoji\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(messageService);
        }

        @Test
        void shouldReturn400WhenEmojiMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE + "/" + MSG + "/reactions")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(messageService);
        }

        @Test
        void shouldReturn404WhenReactingToMissingMessage() throws Exception {
            authenticate();
            when(messageService.reactToMessage(any(), any(), any(), any()))
                    .thenThrow(new NotFoundException("Message not found", "TM_161"));
            mockMvc.perform(post(BASE + "/" + MSG + "/reactions")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"emoji\":\"👍\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_161"));
        }

        @Test
        void shouldReturn200AndForwardEmojiPathVarWhenRemovingReaction() throws Exception {
            authenticate();
            when(messageService.removeReaction(any(), any(), any(), any())).thenReturn(msg(MSG, "hi"));

            // Emoji supplied as a path variable — URI template var lets MockMvc encode it.
            mockMvc.perform(delete(BASE + "/{m}/reactions/{e}", MSG, "👍"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_153"));

            verify(messageService).removeReaction(CHAT, MSG, "👍", testUser);
        }
    }
}
