package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.AdminCreateUserRequest;
import com.chat.talkMe.dto.request.AdminUserFilter;
import com.chat.talkMe.dto.response.AdminStatsResponse;
import com.chat.talkMe.dto.response.AdminUserView;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AdminService;
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
 * Pure controller unit test for {@link AdminController} (SuperAdmin API).
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link AdminService} and the real
 * {@link GlobalExceptionHandler}. A tolerant JSON converter is used because several boolean/int
 * request params + the ban/verify/soft-delete flows exercise primitive binding, and the create
 * flow's body is validated.
 *
 * <p><b>Scope boundary — IMPORTANT:</b> every route is gated by the class-level
 * {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} AND by {@code SecurityConfig} (defense in depth).
 * Neither is active in standalone MockMvc, so this suite does NOT prove authorization — it verifies
 * the controller's request/response wiring, param binding, and service delegation only. The
 * SUPER_ADMIN gate is covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminController (unit)")
class AdminControllerUnitTest {

    private static final String BASE = "/admin";
    private static final String UUID = "user-uuid-1";
    private static final String ADMIN_NAME = "testadmin";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String OK_CODE = "TM_000";

    @Mock
    private AdminService adminService;

    private MockMvc mockMvc;
    private User adminUser;

    @BeforeEach
    void setUp() {
        AdminController controller = new AdminController(adminService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        JsonMapper jsonMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_SUPER_ADMIN").build();
        adminUser = User.builder()
                .username(ADMIN_NAME).email("admin@e.com").name("Admin")
                .isGuest(false).roles(Set.of(role))
                .build();

        CustomUserDetails principal = new CustomUserDetails(adminUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static AdminUserView userView(String id) {
        return AdminUserView.builder().id(id).username("bob").name("Bob").email("bob@e.com").build();
    }

    private static <T> PaginatedResponse<T> paged(T item) {
        return PaginatedResponse.<T>builder()
                .items(List.of(item))
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .page(0).size(25).total(1L).hasNext(false).hasPrevious(false).build())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Read / analytics
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Read & analytics")
    class ReadAnalytics {

        @Test
        void shouldReturnStats() throws Exception {
            when(adminService.getStats()).thenReturn(
                    AdminStatsResponse.builder().totalUsers(42).activeUsers(40).build());

            mockMvc.perform(get(BASE + "/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE))
                    .andExpect(jsonPath("$.data.totalUsers").value(42));

            verify(adminService).getStats();
        }

        @Test
        void shouldListUsersWithDefaultPaging() throws Exception {
            when(adminService.listUsers(any(), anyInt(), anyInt())).thenReturn(paged(userView(UUID)));

            mockMvc.perform(get(BASE + "/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].id").value(UUID))
                    .andExpect(jsonPath("$.data.pagination.total").value(1));

            ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
            verify(adminService).listUsers(any(AdminUserFilter.class), page.capture(), size.capture());
            assertThat(page.getValue()).isEqualTo(0);
            assertThat(size.getValue()).isEqualTo(25);
        }

        @Test
        void shouldBindFilterAndPagingFromQueryParams() throws Exception {
            when(adminService.listUsers(any(), anyInt(), anyInt())).thenReturn(paged(userView(UUID)));

            mockMvc.perform(get(BASE + "/users")
                            .param("query", "bob").param("verified", "true")
                            .param("page", "2").param("size", "10"))
                    .andExpect(status().isOk());

            ArgumentCaptor<AdminUserFilter> filter = ArgumentCaptor.forClass(AdminUserFilter.class);
            ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
            verify(adminService).listUsers(filter.capture(), page.capture(), anyInt());
            assertThat(filter.getValue().getQuery()).isEqualTo("bob");
            assertThat(filter.getValue().getVerified()).isTrue();
            assertThat(page.getValue()).isEqualTo(2);
        }

        @Test
        void shouldGetSingleUser() throws Exception {
            when(adminService.getUser(UUID)).thenReturn(userView(UUID));
            mockMvc.perform(get(BASE + "/users/" + UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(UUID));
            verify(adminService).getUser(UUID);
        }

        @Test
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(adminService.getUser(any())).thenThrow(new NotFoundException("User not found", "TM_064"));
            mockMvc.perform(get(BASE + "/users/" + UUID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldListChatsForwardingAdminNameAndDefaults() throws Exception {
            when(adminService.listChats(any(), any(), eq(true), anyInt(), anyInt(), eq(ADMIN_NAME)))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminChatView>builder()
                            .items(List.of()).build());

            mockMvc.perform(get(BASE + "/chats")).andExpect(status().isOk());

            verify(adminService).listChats(any(), any(), eq(true), eq(0), eq(25), eq(ADMIN_NAME));
        }

        @Test
        void shouldGetChatMessagesForwardingAdminName() throws Exception {
            when(adminService.getChatMessages(eq("chat-1"), anyInt(), anyInt(), eq(ADMIN_NAME)))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminMessageView>builder()
                            .items(List.of()).build());

            mockMvc.perform(get(BASE + "/chats/chat-1/messages")).andExpect(status().isOk());

            verify(adminService).getChatMessages("chat-1", 0, 50, ADMIN_NAME);
        }

        @Test
        void shouldGetSignupTimeseriesWithDefaultDays() throws Exception {
            when(adminService.getSignupTimeseries(anyInt())).thenReturn(List.of());
            mockMvc.perform(get(BASE + "/stats/timeseries")).andExpect(status().isOk());
            verify(adminService).getSignupTimeseries(30);
        }

        @Test
        void shouldGetAnalyticsWithDefaultRange() throws Exception {
            when(adminService.getAnalytics(eq("30d")))
                    .thenReturn(new com.chat.talkMe.dto.response.AdminAnalyticsResponse());
            mockMvc.perform(get(BASE + "/analytics")).andExpect(status().isOk());
            verify(adminService).getAnalytics("30d");
        }

        @Test
        void shouldGetMetricTimeseriesWithDefaults() throws Exception {
            when(adminService.getTimeseries(eq("messages"), eq("30d"), any(), any(), any()))
                    .thenReturn(new com.chat.talkMe.dto.response.AdminTimeseriesResult());
            mockMvc.perform(get(BASE + "/timeseries")).andExpect(status().isOk());
            verify(adminService).getTimeseries("messages", "30d", null, null, null);
        }

        @Test
        void shouldListAuditWithDefaults() throws Exception {
            when(adminService.listAudit(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminAuditView>builder()
                            .items(List.of()).build());
            mockMvc.perform(get(BASE + "/audit")).andExpect(status().isOk());
            // Optional filter params default to null; page/size default to 0/50.
            verify(adminService).listAudit(null, null, null, null, null, 0, 50);
        }

        @Test
        void shouldListAuditForwardingFilterParams() throws Exception {
            when(adminService.listAudit(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminAuditView>builder()
                            .items(List.of()).build());

            mockMvc.perform(get(BASE + "/audit")
                            .param("action", "BAN").param("targetType", "USER").param("admin", "root")
                            .param("from", "2026-01-01").param("to", "2026-07-01")
                            .param("page", "2").param("size", "10"))
                    .andExpect(status().isOk());

            verify(adminService).listAudit("BAN", "USER", "root", "2026-01-01", "2026-07-01", 2, 10);
        }

        @Test
        void shouldGetUserFriends() throws Exception {
            when(adminService.getUserFriends("u-9")).thenReturn(List.of());
            mockMvc.perform(get(BASE + "/social/friends").param("userId", "u-9"))
                    .andExpect(status().isOk());
            verify(adminService).getUserFriends("u-9");
        }

        @Test
        void shouldReturn500WhenRequiredUserIdParamMissingOnFriends() throws Exception {
            mockMvc.perform(get(BASE + "/social/friends"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(adminService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  User moderation actions
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("User moderation")
    class Moderation {

        @Test
        void shouldBanUserForwardingFlagAndAdmin() throws Exception {
            when(adminService.setBanned(eq(UUID), eq(true), eq(ADMIN_NAME))).thenReturn(userView(UUID));

            mockMvc.perform(post(BASE + "/users/" + UUID + "/ban").param("banned", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(UUID));

            verify(adminService).setBanned(UUID, true, ADMIN_NAME);
        }

        @Test
        void shouldUnbanUserWhenBannedFalse() throws Exception {
            when(adminService.setBanned(eq(UUID), eq(false), eq(ADMIN_NAME))).thenReturn(userView(UUID));
            mockMvc.perform(post(BASE + "/users/" + UUID + "/ban").param("banned", "false"))
                    .andExpect(status().isOk());
            verify(adminService).setBanned(UUID, false, ADMIN_NAME);
        }

        @Test
        void shouldReturn500WhenBannedParamMissing() throws Exception {
            mockMvc.perform(post(BASE + "/users/" + UUID + "/ban"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(adminService);
        }

        @Test
        void shouldReturn404WhenBanningMissingUser() throws Exception {
            when(adminService.setBanned(any(), eq(true), any()))
                    .thenThrow(new NotFoundException("User not found", "TM_064"));
            mockMvc.perform(post(BASE + "/users/" + UUID + "/ban").param("banned", "true"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_064"));
        }

        @Test
        void shouldVerifyUser() throws Exception {
            when(adminService.setVerified(eq(UUID), eq(true), eq(ADMIN_NAME))).thenReturn(userView(UUID));
            mockMvc.perform(post(BASE + "/users/" + UUID + "/verify").param("verified", "true"))
                    .andExpect(status().isOk());
            verify(adminService).setVerified(UUID, true, ADMIN_NAME);
        }

        @Test
        void shouldSoftDeleteUser() throws Exception {
            when(adminService.setSoftDeleted(eq(UUID), eq(true), eq(ADMIN_NAME))).thenReturn(userView(UUID));
            mockMvc.perform(post(BASE + "/users/" + UUID + "/soft-delete").param("deleted", "true"))
                    .andExpect(status().isOk());
            verify(adminService).setSoftDeleted(UUID, true, ADMIN_NAME);
        }

        @Test
        void shouldGrantRoleForwardingRoleName() throws Exception {
            when(adminService.grantRole(eq(UUID), eq("ROLE_MODERATOR"), eq(ADMIN_NAME)))
                    .thenReturn(userView(UUID));
            mockMvc.perform(post(BASE + "/users/" + UUID + "/roles/grant").param("role", "ROLE_MODERATOR"))
                    .andExpect(status().isOk());
            verify(adminService).grantRole(UUID, "ROLE_MODERATOR", ADMIN_NAME);
        }

        @Test
        void shouldReturn400WhenGrantingUnknownRole() throws Exception {
            when(adminService.grantRole(any(), any(), any()))
                    .thenThrow(new BadRequestException("Unknown role", "TM_072"));
            mockMvc.perform(post(BASE + "/users/" + UUID + "/roles/grant").param("role", "ROLE_BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_072"));
        }

        @Test
        void shouldRevokeRole() throws Exception {
            when(adminService.revokeRole(eq(UUID), eq("ROLE_MODERATOR"), eq(ADMIN_NAME)))
                    .thenReturn(userView(UUID));
            mockMvc.perform(post(BASE + "/users/" + UUID + "/roles/revoke").param("role", "ROLE_MODERATOR"))
                    .andExpect(status().isOk());
            verify(adminService).revokeRole(UUID, "ROLE_MODERATOR", ADMIN_NAME);
        }

        @Test
        void shouldReturn500WhenRoleParamMissing() throws Exception {
            mockMvc.perform(post(BASE + "/users/" + UUID + "/roles/grant"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(adminService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  User CRUD
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("User create / update")
    class UserCrud {

        @Test
        void shouldCreateUserForwardingBodyAndAdmin() throws Exception {
            when(adminService.createUser(any(), eq(ADMIN_NAME))).thenReturn(userView(UUID));

            String body = """
                    {"name":"Bob","email":"bob@e.com","username":"bobby","password":"secret1"}""";
            mockMvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(UUID));

            ArgumentCaptor<AdminCreateUserRequest> req = ArgumentCaptor.forClass(AdminCreateUserRequest.class);
            verify(adminService).createUser(req.capture(), eq(ADMIN_NAME));
            assertThat(req.getValue().getEmail()).isEqualTo("bob@e.com");
            assertThat(req.getValue().getUsername()).isEqualTo("bobby");
        }

        @Test
        void shouldReturn400WhenCreateEmailInvalid() throws Exception {
            String body = """
                    {"name":"Bob","email":"not-email","username":"bobby","password":"secret1"}""";
            mockMvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(adminService);
        }

        @Test
        void shouldReturn400WhenCreateUsernameTooShort() throws Exception {
            String body = """
                    {"name":"Bob","email":"bob@e.com","username":"ab","password":"secret1"}""";
            mockMvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(adminService);
        }

        @Test
        void shouldReturn400WhenCreatePasswordTooShort() throws Exception {
            String body = """
                    {"name":"Bob","email":"bob@e.com","username":"bobby","password":"123"}""";
            mockMvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldReturn400WhenCreateNameBlank() throws Exception {
            String body = """
                    {"name":"","email":"bob@e.com","username":"bobby","password":"secret1"}""";
            mockMvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldReturn409WhenCreateEmailDuplicate() throws Exception {
            when(adminService.createUser(any(), any()))
                    .thenThrow(new ConflictException("Email already exists", "TM_047"));
            String body = """
                    {"name":"Bob","email":"bob@e.com","username":"bobby","password":"secret1"}""";
            mockMvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.messageCode").value("TM_047"));
        }

        @Test
        void shouldReturn500WhenCreateBodyMalformed() throws Exception {
            mockMvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(adminService);
        }

        @Test
        void shouldUpdateUserForwardingBody() throws Exception {
            when(adminService.updateUser(eq(UUID), any(), eq(ADMIN_NAME))).thenReturn(userView(UUID));
            mockMvc.perform(patch(BASE + "/users/" + UUID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Bobby\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(UUID));
            verify(adminService).updateUser(eq(UUID), any(), eq(ADMIN_NAME));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Deletions (message / chat / storage object)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Deletions")
    class Deletions {

        @Test
        void shouldDeleteMessage() throws Exception {
            doNothing().when(adminService).deleteMessage(eq("m-1"), eq(ADMIN_NAME));
            mockMvc.perform(delete(BASE + "/messages/m-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Message deleted"))
                    .andExpect(jsonPath("$.messageCode").value(OK_CODE));
            verify(adminService).deleteMessage("m-1", ADMIN_NAME);
        }

        @Test
        void shouldReturn404WhenDeletingMissingMessage() throws Exception {
            doThrow(new NotFoundException("Message not found", "TM_161"))
                    .when(adminService).deleteMessage(any(), any());
            mockMvc.perform(delete(BASE + "/messages/m-1"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_161"));
        }

        @Test
        void shouldDeleteChat() throws Exception {
            doNothing().when(adminService).deleteChat(eq("c-1"), eq(ADMIN_NAME));
            mockMvc.perform(delete(BASE + "/chats/c-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Chat deleted"));
            verify(adminService).deleteChat("c-1", ADMIN_NAME);
        }

        @Test
        void shouldDeleteStorageObjectForwardingKey() throws Exception {
            doNothing().when(adminService).deleteStorageObject(eq("uploads/x.png"), eq(ADMIN_NAME));
            mockMvc.perform(delete(BASE + "/storage/object").param("key", "uploads/x.png"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_281"));
            verify(adminService).deleteStorageObject("uploads/x.png", ADMIN_NAME);
        }

        @Test
        void shouldReturn500WhenStorageKeyParamMissing() throws Exception {
            mockMvc.perform(delete(BASE + "/storage/object"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(adminService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Content: attachments / storage / posts / reports
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Content & moderation queues")
    class Content {

        @Test
        void shouldListAttachmentsWithDefaults() throws Exception {
            when(adminService.getAttachments(any(), any(), eq(false), anyInt(), anyInt(), eq(ADMIN_NAME)))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminAttachmentView>builder()
                            .items(List.of()).build());
            mockMvc.perform(get(BASE + "/attachments")).andExpect(status().isOk());
            verify(adminService).getAttachments(null, null, false, 0, 30, ADMIN_NAME);
        }

        @Test
        void shouldListStorageObjectsWithDefaults() throws Exception {
            when(adminService.getStorageObjects(any(), any(), any(), eq(false), any(), any(),
                    anyInt(), anyInt(), eq(ADMIN_NAME)))
                    .thenReturn(com.chat.talkMe.dto.response.AdminStorageListResponse.builder().build());
            mockMvc.perform(get(BASE + "/storage/objects")).andExpect(status().isOk());
            verify(adminService).getStorageObjects(null, null, null, false, null, null, 0, 40, ADMIN_NAME);
        }

        @Test
        void shouldListPosts() throws Exception {
            when(adminService.listPosts(anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminPostView>builder()
                            .items(List.of()).build());
            mockMvc.perform(get(BASE + "/posts")).andExpect(status().isOk());
            verify(adminService).listPosts(0, 20);
        }

        @Test
        void shouldListPostLikes() throws Exception {
            when(adminService.getPostLikes(eq("p-1"), anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminPostLikeView>builder()
                            .items(List.of()).build());
            mockMvc.perform(get(BASE + "/posts/p-1/likes")).andExpect(status().isOk());
            verify(adminService).getPostLikes("p-1", 0, 50);
        }

        @Test
        void shouldListPostComments() throws Exception {
            when(adminService.getPostComments(eq("p-1"), anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminPostCommentView>builder()
                            .items(List.of()).build());
            mockMvc.perform(get(BASE + "/posts/p-1/comments")).andExpect(status().isOk());
            verify(adminService).getPostComments("p-1", 0, 50);
        }

        @Test
        void shouldListReportsWithStatusFilter() throws Exception {
            when(adminService.listReports(eq("PENDING"), anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminReportView>builder()
                            .items(List.of()).build());
            mockMvc.perform(get(BASE + "/moderation/reports").param("status", "PENDING"))
                    .andExpect(status().isOk());
            verify(adminService).listReports("PENDING", 0, 20);
        }

        @Test
        void shouldGetSingleReport() throws Exception {
            when(adminService.getReport("r-1"))
                    .thenReturn(com.chat.talkMe.dto.response.AdminReportView.builder().build());
            mockMvc.perform(get(BASE + "/moderation/reports/r-1")).andExpect(status().isOk());
            verify(adminService).getReport("r-1");
        }

        @Test
        void shouldReviewReportForwardingActionAndAdmin() throws Exception {
            when(adminService.reviewReport(eq("r-1"), eq("DISMISS"), any(), eq(ADMIN_NAME)))
                    .thenReturn(com.chat.talkMe.dto.response.AdminReportView.builder().build());
            mockMvc.perform(post(BASE + "/moderation/reports/r-1/review")
                            .param("action", "DISMISS").param("note", "not a violation"))
                    .andExpect(status().isOk());
            verify(adminService).reviewReport("r-1", "DISMISS", "not a violation", ADMIN_NAME);
        }

        @Test
        void shouldReturn500WhenReviewActionParamMissing() throws Exception {
            mockMvc.perform(post(BASE + "/moderation/reports/r-1/review"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(adminService);
        }

        @Test
        void shouldReturn404WhenReportNotFound() throws Exception {
            when(adminService.getReport(any())).thenThrow(new NotFoundException("Report not found", "TM_295"));
            mockMvc.perform(get(BASE + "/moderation/reports/missing"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_295"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Feedback
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Feedback")
    class Feedback {

        @Test
        void shouldListFeedbackForwardingTypeStatusAndDefaults() throws Exception {
            when(adminService.listFeedback(eq("MANUAL"), eq("NEW"), anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminFeedbackView>builder()
                            .items(List.of(com.chat.talkMe.dto.response.AdminFeedbackView.builder()
                                    .id("fb-1").rating(5).type("MANUAL").status("NEW").build()))
                            .build());

            mockMvc.perform(get(BASE + "/feedback").param("type", "MANUAL").param("status", "NEW"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items[0].id").value("fb-1"))
                    .andExpect(jsonPath("$.data.items[0].rating").value(5));

            // Defaults: page=0, size=20 when unspecified.
            verify(adminService).listFeedback("MANUAL", "NEW", 0, 20);
        }

        @Test
        void shouldListFeedbackWithNoFilters() throws Exception {
            when(adminService.listFeedback(any(), any(), anyInt(), anyInt()))
                    .thenReturn(PaginatedResponse.<com.chat.talkMe.dto.response.AdminFeedbackView>builder()
                            .items(List.of()).build());
            mockMvc.perform(get(BASE + "/feedback")).andExpect(status().isOk());
            verify(adminService).listFeedback(null, null, 0, 20);
        }

        @Test
        void shouldUpdateFeedbackStatusForwardingParamAndAdmin() throws Exception {
            when(adminService.updateFeedbackStatus(eq("fb-1"), eq("REVIEWED"), eq(ADMIN_NAME)))
                    .thenReturn(com.chat.talkMe.dto.response.AdminFeedbackView.builder()
                            .id("fb-1").status("REVIEWED").build());

            mockMvc.perform(post(BASE + "/feedback/fb-1/status").param("status", "REVIEWED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_311"))
                    .andExpect(jsonPath("$.data.status").value("REVIEWED"));

            verify(adminService).updateFeedbackStatus("fb-1", "REVIEWED", ADMIN_NAME);
        }

        @Test
        void shouldReturn500WhenStatusParamMissing() throws Exception {
            mockMvc.perform(post(BASE + "/feedback/fb-1/status"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(adminService);
        }

        @Test
        void shouldReturn404WhenFeedbackNotFound() throws Exception {
            when(adminService.updateFeedbackStatus(any(), any(), any()))
                    .thenThrow(new NotFoundException("Feedback not found", "TM_312"));
            mockMvc.perform(post(BASE + "/feedback/missing/status").param("status", "REVIEWED"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_312"));
        }
    }
}
