package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.BucketItemResponse;
import com.chat.talkMe.dto.response.BucketListResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.ServiceException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.BucketListService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link BucketListController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link BucketListService} and the real
 * {@link GlobalExceptionHandler}. Registers {@link AuthenticationPrincipalArgumentResolver}
 * for {@code @AuthenticationPrincipal}. No {@code PageableHandlerMethodArgumentResolver}
 * (no {@code Pageable} endpoint) and no custom message converters (the only {@code @RequestBody}
 * DTO, {@link com.chat.talkMe.dto.request.BucketItemRequest}, has no unboxed primitive fields, so
 * the strict Jackson-3 default mapper is fine).
 *
 * <p><b>Scope boundary:</b> the {@code @PreAuthorize("@featureGuard.check('BUCKET_LIST')")}
 * feature gate and filter-chain authentication/authorization (JWT, roles, CSRF, chat membership
 * enforced by the service) are out of scope — those are exercised by integration tests. This
 * suite pins the controller's request→service wiring, success envelopes, bean-validation, and
 * exception→HTTP mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BucketListController (unit)")
class BucketListControllerUnitTest {

    private static final String CHAT = "chat-uuid-1";
    private static final String BASE = "/chats/" + CHAT + "/bucket-list";
    private static final String ITEMS = BASE + "/items";
    private static final String ITEM = "item-uuid-1";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String INVALID_ARG_CODE = "TM_071";
    private static final String INVALID_UUID_CODE = "TM_INVALID_UUID";
    private static final String ACCESS_DENIED_CODE = "TM_005";

    @Mock
    private BucketListService bucketListService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        BucketListController controller = new BucketListController(bucketListService);

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
        testUser.setId(1L);
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

    private static BucketItemResponse item(String id, String text, boolean completed, int orderIndex) {
        return BucketItemResponse.builder()
                .id(id).text(text).completed(completed)
                .completedByUserId(completed ? 1L : null)
                .completedAt(completed ? Instant.parse("2026-07-22T00:00:00Z") : null)
                .createdByUserId(1L).orderIndex(orderIndex)
                .build();
    }

    private static BucketListResponse listWith(BucketItemResponse... items) {
        return BucketListResponse.builder().chatId(CHAT).items(List.of(items)).build();
    }

    private static BucketListResponse emptyList() {
        return BucketListResponse.builder().chatId(CHAT).items(List.of()).build();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /chats/{chatId}/bucket-list
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET (get list)")
    class GetList {

        @Test
        void shouldReturn200WithListAndForwardUserAndChatId() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any()))
                    .thenReturn(listWith(item(ITEM, "Skydive", false, 0)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // getList uses SuccessResponseDto.success(response) → inherited TM_000/"Success".
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.data.chatId").value(CHAT))
                    .andExpect(jsonPath("$.data.items[0].id").value(ITEM))
                    .andExpect(jsonPath("$.data.items[0].text").value("Skydive"))
                    .andExpect(jsonPath("$.data.items[0].completed").value(false))
                    .andExpect(jsonPath("$.data.items[0].orderIndex").value(0));

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            verify(bucketListService).getList(eq(testUser), chatId.capture());
            assertThat(chatId.getValue()).isEqualTo(CHAT);
        }

        @Test
        void shouldReturn200WithEmptyItemsList() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any())).thenReturn(emptyList());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.chatId").value(CHAT))
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.items").isEmpty());

            verify(bucketListService).getList(testUser, CHAT);
        }

        @Test
        void shouldExposeCompletedItemMetadataFields() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any()))
                    .thenReturn(listWith(item(ITEM, "Done thing", true, 3)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].completed").value(true))
                    .andExpect(jsonPath("$.data.items[0].completedByUserId").value(1))
                    .andExpect(jsonPath("$.data.items[0].createdByUserId").value(1))
                    .andExpect(jsonPath("$.data.items[0].orderIndex").value(3));
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn403WhenAccessDenied() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any()))
                    .thenThrow(new AccessDeniedException("denied"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value(ACCESS_DENIED_CODE));
        }

        @Test
        void shouldReturn400WhenServiceThrowsInvalidUuid() throws Exception {
            authenticate();
            // Service parsing a bad chat UUID surfaces as IllegalArgumentException("Invalid UUID string...").
            when(bucketListService.getList(any(), any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: xyz"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_UUID_CODE));
        }

        @Test
        void shouldReturn400WhenServiceThrowsIllegalArgument() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any()))
                    .thenThrow(new IllegalArgumentException("bad argument"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_ARG_CODE));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(bucketListService.getList(any(), any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/bucket-list/items
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /items (add item)")
    class AddItem {

        @Test
        void shouldReturn200AndForwardTextUserAndChatId() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenReturn(listWith(item(ITEM, "Learn to surf", false, 0)));

            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"Learn to surf\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Item added"))
                    .andExpect(jsonPath("$.messageCode").value("TM_813"))
                    .andExpect(jsonPath("$.data.items[0].text").value("Learn to surf"));

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(bucketListService).addItem(eq(testUser), chatId.capture(), text.capture());
            assertThat(chatId.getValue()).isEqualTo(CHAT);
            assertThat(text.getValue()).isEqualTo("Learn to surf");
        }

        @Test
        void shouldAcceptTextAtMaxBoundaryLength() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenReturn(listWith(item(ITEM, "x", false, 0)));

            String maxText = repeat('a', 1000); // @Size(max = 1000) inclusive boundary
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"%s\"}".formatted(maxText)))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(bucketListService).addItem(any(), any(), text.capture());
            assertThat(text.getValue()).hasSize(1000);
        }

        @Test
        void shouldAcceptUnicodeAndEmojiText() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenReturn(listWith(item(ITEM, "旅行 ✈️ 😀", false, 0)));

            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"旅行 ✈️ 😀\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(bucketListService).addItem(any(), any(), text.capture());
            assertThat(text.getValue()).isEqualTo("旅行 ✈️ 😀");
        }

        @Test
        void shouldForwardXssAndSqlInjectionTextVerbatim() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenReturn(listWith(item(ITEM, "payload", false, 0)));

            // Controller/validation must NOT sanitize free-form body text — it passes through raw.
            String payload = "<script>alert(1)</script>'; DROP TABLE items;--";
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"<script>alert(1)</script>'; DROP TABLE items;--\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(bucketListService).addItem(any(), any(), text.capture());
            assertThat(text.getValue()).isEqualTo(payload);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenTextBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(bucketListService);
        }

        @Test
        void shouldReturn400WhenTextWhitespaceOnly() throws Exception {
            authenticate();
            // @NotBlank rejects whitespace-only strings.
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(bucketListService);
        }

        @Test
        void shouldReturn400WhenTextMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(bucketListService);
        }

        @Test
        void shouldReturn400WhenTextNull() throws Exception {
            authenticate();
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":null}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(bucketListService);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenTextTooLong() throws Exception {
            authenticate();
            String tooLong = repeat('a', 1001); // one past @Size(max = 1000)
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"%s\"}".formatted(tooLong)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(bucketListService);
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            // HttpMessageNotReadableException has no dedicated handler → catch-all 500 (pins behaviour).
            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(bucketListService);
        }

        @Test
        void shouldReturn404WhenChatNotFound() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenThrow(new NotFoundException("Chat not found", "TM_140"));

            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hi\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_140"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));

            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hi\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn409WhenServiceReportsConflict() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenThrow(new ServiceException(409, "List is full", "TM_816"));

            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hi\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_816"));
        }

        @Test
        void shouldReturn400WhenServiceReportsBadRequest() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenThrow(new ServiceException(400, "Bad input", "TM_817"));

            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hi\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_817"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(bucketListService.addItem(any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(ITEMS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"hi\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/{chatId}/bucket-list/items/{itemUuid}/toggle
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /items/{itemUuid}/toggle (toggle item)")
    class ToggleItem {

        @Test
        void shouldReturn200AndForwardIdsWhenTogglingToCompleted() throws Exception {
            authenticate();
            when(bucketListService.toggleItem(any(), any(), any()))
                    .thenReturn(listWith(item(ITEM, "Task", true, 0)));

            mockMvc.perform(post(ITEMS + "/" + ITEM + "/toggle"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Item updated"))
                    .andExpect(jsonPath("$.messageCode").value("TM_814"))
                    .andExpect(jsonPath("$.data.items[0].completed").value(true));

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> itemUuid = ArgumentCaptor.forClass(String.class);
            verify(bucketListService).toggleItem(eq(testUser), chatId.capture(), itemUuid.capture());
            assertThat(chatId.getValue()).isEqualTo(CHAT);
            assertThat(itemUuid.getValue()).isEqualTo(ITEM);
        }

        @Test
        void shouldReturn200WhenTogglingBackToIncomplete() throws Exception {
            authenticate();
            when(bucketListService.toggleItem(any(), any(), any()))
                    .thenReturn(listWith(item(ITEM, "Task", false, 0)));

            mockMvc.perform(post(ITEMS + "/" + ITEM + "/toggle"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_814"))
                    .andExpect(jsonPath("$.data.items[0].completed").value(false));

            verify(bucketListService).toggleItem(testUser, CHAT, ITEM);
        }

        @Test
        void shouldReturn404WhenItemNotFound() throws Exception {
            authenticate();
            when(bucketListService.toggleItem(any(), any(), any()))
                    .thenThrow(new NotFoundException("Item not found", "TM_818"));

            mockMvc.perform(post(ITEMS + "/" + ITEM + "/toggle"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_818"));
        }

        @Test
        void shouldReturn403WhenNotAMember() throws Exception {
            authenticate();
            when(bucketListService.toggleItem(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Not a member of this chat", "TM_141"));

            mockMvc.perform(post(ITEMS + "/" + ITEM + "/toggle"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsInvalidUuid() throws Exception {
            authenticate();
            when(bucketListService.toggleItem(any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: nope"));

            mockMvc.perform(post(ITEMS + "/not-a-uuid/toggle"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_UUID_CODE));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(bucketListService.toggleItem(any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(post(ITEMS + "/" + ITEM + "/toggle"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DELETE /chats/{chatId}/bucket-list/items/{itemUuid}
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /items/{itemUuid} (remove item)")
    class RemoveItem {

        @Test
        void shouldReturn200AndForwardIdsWhenRemoved() throws Exception {
            authenticate();
            when(bucketListService.removeItem(any(), any(), any())).thenReturn(emptyList());

            mockMvc.perform(delete(ITEMS + "/" + ITEM))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Item removed"))
                    .andExpect(jsonPath("$.messageCode").value("TM_815"))
                    .andExpect(jsonPath("$.data.items").isEmpty());

            ArgumentCaptor<String> chatId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> itemUuid = ArgumentCaptor.forClass(String.class);
            verify(bucketListService).removeItem(eq(testUser), chatId.capture(), itemUuid.capture());
            assertThat(chatId.getValue()).isEqualTo(CHAT);
            assertThat(itemUuid.getValue()).isEqualTo(ITEM);
        }

        @Test
        void shouldReturn200WithRemainingItemsAfterRemoval() throws Exception {
            authenticate();
            when(bucketListService.removeItem(any(), any(), any()))
                    .thenReturn(listWith(item("other-uuid", "Keeper", false, 0)));

            mockMvc.perform(delete(ITEMS + "/" + ITEM))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].id").value("other-uuid"))
                    .andExpect(jsonPath("$.data.items[0].text").value("Keeper"));

            verify(bucketListService).removeItem(testUser, CHAT, ITEM);
        }

        @Test
        void shouldReturn404WhenItemNotFound() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Item not found", "TM_818"))
                    .when(bucketListService).removeItem(any(), any(), any());

            mockMvc.perform(delete(ITEMS + "/" + ITEM))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_818"));
        }

        @Test
        void shouldReturn403WhenNotAllowed() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Not allowed", "TM_141"))
                    .when(bucketListService).removeItem(any(), any(), any());

            mockMvc.perform(delete(ITEMS + "/" + ITEM))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_141"));
        }

        @Test
        void shouldReturn400WhenServiceThrowsInvalidUuid() throws Exception {
            authenticate();
            doThrow(new IllegalArgumentException("Invalid UUID string: nope"))
                    .when(bucketListService).removeItem(any(), any(), any());

            mockMvc.perform(delete(ITEMS + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(INVALID_UUID_CODE));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            doThrow(new RuntimeException("boom"))
                    .when(bucketListService).removeItem(any(), any(), any());

            mockMvc.perform(delete(ITEMS + "/" + ITEM))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }
}
