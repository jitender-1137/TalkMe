package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.GlobalExceptionHandler;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link ChannelRoomController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link GroupService} and the real
 * {@link GlobalExceptionHandler}. Both endpoints reuse the unified group-creation path but force the
 * subtype ("channel" / "room") — the key behavior verified here is that the correct subtype reaches
 * the service and that {@code CreateGroupRequest} validation (name @NotBlank @Size(max=100)) is honored.
 *
 * <p><b>Scope boundary:</b> {@code @PreAuthorize("hasRole('USER')")} is inactive in standalone
 * MockMvc; covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelRoomController (unit)")
class ChannelRoomControllerUnitTest {

    private static final String CHANNEL = "/chats/channel";
    private static final String ROOM = "/chats/room";
    private static final String CID = "chat-uuid-1";
    private static final String VALIDATION_CODE = "VE_101";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private GroupService groupService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ChannelRoomController controller = new ChannelRoomController(groupService);

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

        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static ChatResponse group(String id, String type) {
        return ChatResponse.builder().id(id).name("My " + type).chatType(type).build();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/channel
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/channel")
    class CreateChannel {

        @Test
        void shouldReturn200AndForceChannelSubtype() throws Exception {
            when(groupService.createGroup(any(), any())).thenReturn(group(CID, "CHANNEL"));

            String body = """
                    {"name":"Announcements","description":"news","visibility":"PUBLIC"}""";
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_280"))
                    .andExpect(jsonPath("$.message").value("Channel created successfully"))
                    .andExpect(jsonPath("$.data.id").value(CID));

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(testUser));
            // Controller overrides whatever subtype the client sent.
            assertThat(req.getValue().getSubtype()).isEqualTo("channel");
            assertThat(req.getValue().getName()).isEqualTo("Announcements");
        }

        @Test
        void shouldOverrideClientSuppliedSubtype() throws Exception {
            when(groupService.createGroup(any(), any())).thenReturn(group(CID, "CHANNEL"));

            String body = """
                    {"name":"Announcements","subtype":"room"}""";
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), any());
            assertThat(req.getValue().getSubtype()).isEqualTo("channel");
        }

        @Test
        void shouldAcceptUnicodeAndEmojiName() throws Exception {
            when(groupService.createGroup(any(), any())).thenReturn(group(CID, "CHANNEL"));

            String body = """
                    {"name":"频道 🎉 room"}""";
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), any());
            assertThat(req.getValue().getName()).isEqualTo("频道 🎉 room");
        }

        @Test
        void shouldReturn400AndSkipServiceWhenNameBlank() throws Exception {
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn400WhenNameMissing() throws Exception {
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn400WhenNameTooLong() throws Exception {
            String body = """
                    {"name":"%s"}""".formatted(repeat('c', 101));
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldAcceptBoundaryNameLength100() throws Exception {
            when(groupService.createGroup(any(), any())).thenReturn(group(CID, "CHANNEL"));
            String body = """
                    {"name":"%s"}""".formatted(repeat('c', 100));
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn403WhenServiceForbids() throws Exception {
            when(groupService.createGroup(any(), any()))
                    .thenThrow(new ForbiddenException("Not allowed to create channels", "TM_296"));
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"X\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_296"));
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            when(groupService.createGroup(any(), any())).thenThrow(new RuntimeException("boom"));
            mockMvc.perform(post(CHANNEL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"X\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  POST /chats/room
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /chats/room")
    class CreateRoom {

        @Test
        void shouldReturn200AndForceRoomSubtype() throws Exception {
            when(groupService.createGroup(any(), any())).thenReturn(group(CID, "ROOM"));

            String body = """
                    {"name":"Lounge","subtype":"channel"}""";
            mockMvc.perform(post(ROOM).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messageCode").value("TM_280"))
                    .andExpect(jsonPath("$.message").value("Room created successfully"))
                    .andExpect(jsonPath("$.data.id").value(CID));

            ArgumentCaptor<CreateGroupRequest> req = ArgumentCaptor.forClass(CreateGroupRequest.class);
            verify(groupService).createGroup(req.capture(), eq(testUser));
            assertThat(req.getValue().getSubtype()).isEqualTo("room");
            assertThat(req.getValue().getName()).isEqualTo("Lounge");
        }

        @Test
        void shouldReturn400AndSkipServiceWhenNameBlank() throws Exception {
            mockMvc.perform(post(ROOM).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn400WhenNameTooLong() throws Exception {
            String body = """
                    {"name":"%s"}""".formatted(repeat('r', 101));
            mockMvc.perform(post(ROOM).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value(VALIDATION_CODE));
        }

        @Test
        void shouldReturn500WhenBodyMalformed() throws Exception {
            mockMvc.perform(post(ROOM).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(groupService);
        }

        @Test
        void shouldReturn403WhenServiceForbids() throws Exception {
            when(groupService.createGroup(any(), any()))
                    .thenThrow(new ForbiddenException("Not allowed to create rooms", "TM_296"));
            mockMvc.perform(post(ROOM).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"X\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.messageCode").value("TM_296"));
        }
    }
}
