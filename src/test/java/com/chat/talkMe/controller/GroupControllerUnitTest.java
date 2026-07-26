package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.GroupMemberResponse;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.GroupService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link GroupController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link GroupService} and the real
 * {@link GlobalExceptionHandler}.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize("hasRole('USER')")} on createGroup is enforced
 * by method-security (inactive in standalone) — see the integration test. Group role/ownership
 * authorization is enforced in the service and driven here by stubbed exceptions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GroupController (unit)")
class GroupControllerUnitTest {

    private static final String BASE = "/chats/group";
    private static final String GID = "group-uuid-1";
    private static final String UID = "member-uuid-1";
    private static final String INTERNAL_ERROR_CODE = "TM_002";
    private static final String VALIDATION_CODE = "VE_101";

    @Mock
    private GroupService groupService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        GroupController controller = new GroupController(groupService);

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

    private static ChatResponse group(String id) {
        return ChatResponse.builder().id(id).name("My Group").chatType("GROUP").build();
    }

    private static GroupMemberResponse member(String userId, String role) {
        return GroupMemberResponse.builder().userId(userId).username("mem").role(role).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/group  (create)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/group")
    class CreateGroup {

        @Test
        void shouldReturn200AndForwardRequestWhenValid() throws Exception {
            authenticate();
            when(groupService.createGroup(any(), any())).thenReturn(group(GID));

            String body = """
                    {"name":"My Group","description":"hi","memberIds":["u1","u2"],"subtype":"group"}""";
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_280"))
                    .andExpect(jsonPath("$.data.id").value(GID))
                    .andExpect(jsonPath("$.data.chatType").value("GROUP"));

            verify(groupService).createGroup(any(), eq(testUser));
        }

        @Test
        void shouldReturn400AndSkipServiceWhenNameBlank() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn400WhenNameMissing() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn400WhenNameTooLong() throws Exception {
            authenticate();
            String body = """
                    {"name":"%s"}""".formatted("g".repeat(101));
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            authenticate();
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(groupService);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PATCH /chats/group/{id}  (update)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /chats/group/{id}")
    class UpdateGroup {

        @Test
        void shouldReturn200WhenUpdated() throws Exception {
            authenticate();
            when(groupService.updateGroup(eq(GID), any(), any())).thenReturn(group(GID));

            mockMvc.perform(patch(BASE + "/" + GID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Renamed\",\"visibility\":\"PUBLIC\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_281"))
                    .andExpect(jsonPath("$.data.id").value(GID));

            verify(groupService).updateGroup(eq(GID), any(), eq(testUser));
        }

        @Test
        void shouldReturn400WhenNameTooLong() throws Exception {
            authenticate();
            String body = """
                    {"name":"%s"}""".formatted("x".repeat(101));
            mockMvc.perform(patch(BASE + "/" + GID).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn403WhenNotPermittedToEdit() throws Exception {
            authenticate();
            when(groupService.updateGroup(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Not allowed to edit this group", "TM_290"));
            mockMvc.perform(patch(BASE + "/" + GID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_290"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Members: list / add / remove
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Members: list / add / remove")
    class Members {

        @Test
        void shouldReturn200WithMemberList() throws Exception {
            authenticate();
            when(groupService.getMembers(eq(GID), any()))
                    .thenReturn(List.of(member(UID, "OWNER")));

            mockMvc.perform(get(BASE + "/" + GID + "/members"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].userId").value(UID))
                    .andExpect(jsonPath("$.data[0].role").value("OWNER"));

            verify(groupService).getMembers(GID, testUser);
        }

        @Test
        void shouldAddMembersFromBody() throws Exception {
            authenticate();
            when(groupService.addMembers(eq(GID), any(), any())).thenReturn(group(GID));

            mockMvc.perform(post(BASE + "/" + GID + "/members").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"memberIds\":[\"u1\",\"u2\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_282"));

            ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
            verify(groupService).addMembers(eq(GID), ids.capture(), eq(testUser));
            assertThat(ids.getValue()).containsExactly("u1", "u2");
        }

        @Test
        void shouldAddMembersWithEmptyListWhenKeyMissing() throws Exception {
            authenticate();
            when(groupService.addMembers(eq(GID), any(), any())).thenReturn(group(GID));

            mockMvc.perform(post(BASE + "/" + GID + "/members").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
            verify(groupService).addMembers(eq(GID), ids.capture(), eq(testUser));
            assertThat(ids.getValue()).isEmpty();
        }

        @Test
        void shouldReturn403WhenAddingMembersNotPermitted() throws Exception {
            authenticate();
            when(groupService.addMembers(any(), any(), any()))
                    .thenThrow(new ForbiddenException("Not allowed to add members", "TM_291"));
            mockMvc.perform(post(BASE + "/" + GID + "/members").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"memberIds\":[\"u1\"]}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_291"));
        }

        @Test
        void shouldRemoveMember() throws Exception {
            authenticate();
            doNothing().when(groupService).removeMember(any(), any(), any());

            mockMvc.perform(delete(BASE + "/" + GID + "/members/" + UID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_283"));

            verify(groupService).removeMember(GID, UID, testUser);
        }

        @Test
        void shouldReturn404WhenRemovingMissingMember() throws Exception {
            authenticate();
            doThrow(new NotFoundException("Member not found", "TM_292"))
                    .when(groupService).removeMember(any(), any(), any());
            mockMvc.perform(delete(BASE + "/" + GID + "/members/" + UID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_292"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PUT /members/{userId}/role  (enum parsing branch)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT role")
    class SetRole {

        @Test
        void shouldSetRoleFromLowercaseBody() throws Exception {
            authenticate();
            doNothing().when(groupService).setRole(any(), any(), any(), any());

            mockMvc.perform(put(BASE + "/" + GID + "/members/" + UID + "/role")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_284"));

            verify(groupService).setRole(GID, UID, MemberRole.ADMIN, testUser);
        }

        @Test
        void shouldDefaultToMemberWhenRoleKeyMissing() throws Exception {
            authenticate();
            doNothing().when(groupService).setRole(any(), any(), any(), any());

            mockMvc.perform(put(BASE + "/" + GID + "/members/" + UID + "/role")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());

            verify(groupService).setRole(GID, UID, MemberRole.MEMBER, testUser);
        }

        @Test
        void shouldReturn400AndSkipServiceWhenRoleInvalid() throws Exception {
            authenticate();
            // MemberRole.valueOf("KING") throws IllegalArgumentException → TM_071 / 400.
            mockMvc.perform(put(BASE + "/" + GID + "/members/" + UID + "/role")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"king\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("TM_071"));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn403WhenSettingRoleNotPermitted() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Only the owner can set roles", "TM_293"))
                    .when(groupService).setRole(any(), any(), any(), any());
            mockMvc.perform(put(BASE + "/" + GID + "/members/" + UID + "/role")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_293"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Leave / transfer ownership
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Leave / transfer ownership")
    class LeaveTransfer {

        @Test
        void shouldLeaveGroup() throws Exception {
            authenticate();
            doNothing().when(groupService).leaveGroup(any(), any());

            mockMvc.perform(post(BASE + "/" + GID + "/leave"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_285"));

            verify(groupService).leaveGroup(GID, testUser);
        }

        @Test
        void shouldTransferOwnershipForwardingNewOwnerId() throws Exception {
            authenticate();
            doNothing().when(groupService).transferOwnership(any(), any(), any());

            mockMvc.perform(post(BASE + "/" + GID + "/transfer-ownership")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"newOwnerId\":\"u9\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_286"));

            verify(groupService).transferOwnership(GID, "u9", testUser);
        }

        @Test
        void shouldForwardNullNewOwnerIdWhenKeyMissing() throws Exception {
            authenticate();
            doNothing().when(groupService).transferOwnership(any(), isNull(), any());

            mockMvc.perform(post(BASE + "/" + GID + "/transfer-ownership")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());

            verify(groupService).transferOwnership(eq(GID), isNull(), eq(testUser));
        }

        @Test
        void shouldReturn403WhenNonOwnerTransfers() throws Exception {
            authenticate();
            doThrow(new ForbiddenException("Only the owner can transfer ownership", "TM_293"))
                    .when(groupService).transferOwnership(any(), any(), any());
            mockMvc.perform(post(BASE + "/" + GID + "/transfer-ownership")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"newOwnerId\":\"u9\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_293"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Discover / join / invites / report
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Discover / join / invites / report")
    class DiscoverJoinInvites {

        @Test
        void shouldDiscoverWithAllFiltersForwarded() throws Exception {
            authenticate();
            when(groupService.discover(eq("channel"), eq("news"), eq("tech"), any()))
                    .thenReturn(List.of(group(GID)));

            mockMvc.perform(get(BASE + "/discover")
                            .param("type", "channel").param("q", "news").param("tag", "tech"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(GID));

            verify(groupService).discover("channel", "news", "tech", testUser);
        }

        @Test
        void shouldDiscoverWithNullFiltersWhenParamsAbsent() throws Exception {
            authenticate();
            when(groupService.discover(isNull(), isNull(), isNull(), any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/discover"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(groupService).discover(isNull(), isNull(), isNull(), eq(testUser));
        }

        @Test
        void shouldJoinPublicGroup() throws Exception {
            authenticate();
            when(groupService.joinChat(eq(GID), any())).thenReturn(group(GID));

            mockMvc.perform(post(BASE + "/" + GID + "/join"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_282"))
                    .andExpect(jsonPath("$.data.id").value(GID));

            verify(groupService).joinChat(GID, testUser);
        }

        @Test
        void shouldReturn403WhenJoiningPrivateGroup() throws Exception {
            authenticate();
            when(groupService.joinChat(any(), any()))
                    .thenThrow(new ForbiddenException("This group is invite-only", "TM_294"));
            mockMvc.perform(post(BASE + "/" + GID + "/join"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_294"));
        }

        @Test
        void shouldAcceptInvite() throws Exception {
            authenticate();
            when(groupService.acceptGroupInvite(eq(GID), any())).thenReturn(group(GID));

            mockMvc.perform(post(BASE + "/" + GID + "/invite/accept"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_283"));

            verify(groupService).acceptGroupInvite(GID, testUser);
        }

        @Test
        void shouldDeclineInvite() throws Exception {
            authenticate();
            doNothing().when(groupService).declineGroupInvite(any(), any());

            mockMvc.perform(post(BASE + "/" + GID + "/invite/decline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_284"));

            verify(groupService).declineGroupInvite(GID, testUser);
            verify(groupService, never()).acceptGroupInvite(any(), any());
        }

        @Test
        void shouldReturn404WhenAcceptingMissingInvite() throws Exception {
            authenticate();
            when(groupService.acceptGroupInvite(any(), any()))
                    .thenThrow(new NotFoundException("No pending invite", "TM_295"));
            mockMvc.perform(post(BASE + "/" + GID + "/invite/accept"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("TM_295"));
        }

        @Test
        void shouldReportWithReasonAndDetails() throws Exception {
            authenticate();
            doNothing().when(groupService).reportChat(any(), any(), any(), any());

            mockMvc.perform(post(BASE + "/" + GID + "/report").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"spam\",\"details\":\"lots of ads\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_307"));

            verify(groupService).reportChat(GID, "spam", "lots of ads", testUser);
        }

        @Test
        void shouldReportWithDefaultReasonWhenBodyAbsent() throws Exception {
            authenticate();
            doNothing().when(groupService).reportChat(any(), any(), isNull(), any());

            // @RequestBody(required = false) → a missing body defaults reason to "other".
            mockMvc.perform(post(BASE + "/" + GID + "/report"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_307"));

            verify(groupService).reportChat(GID, "other", null, testUser);
        }
    }
}
