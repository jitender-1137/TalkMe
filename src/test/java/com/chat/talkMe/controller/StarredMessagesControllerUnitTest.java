package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.exception.GlobalExceptionHandler;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link StarredMessagesController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link MessageService} and the real
 * {@link GlobalExceptionHandler}. Only {@link AuthenticationPrincipalArgumentResolver} is
 * registered (for {@code @AuthenticationPrincipal}); the endpoint takes no request body and no
 * {@code Pageable}, so neither a tolerant Jackson converter nor a
 * {@code PageableHandlerMethodArgumentResolver} is needed.
 *
 * <p><b>Scope boundary:</b> filter-chain authentication/authorization (JWT, roles, CSRF) is
 * enforced by the security filter chain, which is NOT active in a standalone MockMvc setup —
 * those are covered by the integration tests. Here we verify the controller's request/response
 * wiring (the {@code limit} query param and its default) and its delegation to the service, which
 * owns the actual data-access logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StarredMessagesController (unit)")
class StarredMessagesControllerUnitTest {

    private static final String BASE = "/messages/starred";
    private static final String MSG = "msg-uuid-1";
    private static final String CHAT = "chat-uuid-1";
    private static final String INTERNAL_ERROR_CODE = "TM_002";

    @Mock
    private MessageService messageService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        StarredMessagesController controller = new StarredMessagesController(messageService);

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

    private static MessageResponse msg(String id, String content) {
        return MessageResponse.builder()
                .id(id).chatId(CHAT).content(content)
                .messageType("TEXT").status("SENT").build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GET /messages/starred
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /messages/starred")
    class GetStarred {

        @Test
        void shouldReturn200WithStarredList() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt()))
                    .thenReturn(List.of(msg(MSG, "saved")));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // SuccessResponseDto.success(data) → inherited ResponseDto.success(data) → TM_000/"Success".
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.data[0].id").value(MSG))
                    .andExpect(jsonPath("$.data[0].content").value("saved"))
                    .andExpect(jsonPath("$.data[0].chatId").value(CHAT));
        }

        @Test
        void shouldReturn200WithEmptyList() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void shouldForwardAuthenticatedUserToService() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt()))
                    .thenReturn(List.of(msg(MSG, "saved")));

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            verify(messageService).getStarredMessages(user.capture(), anyInt());
            assertThat(user.getValue()).isSameAs(testUser);
        }

        @Test
        void shouldUseDefaultLimitWhenParamAbsent() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE)).andExpect(status().isOk());

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getStarredMessages(eq(testUser), limit.capture());
            // Controller declares @RequestParam(defaultValue = "100").
            assertThat(limit.getValue()).isEqualTo(100);
        }

        @Test
        void shouldForwardCustomLimitWhenParamPresent() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("limit", "25")).andExpect(status().isOk());

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getStarredMessages(eq(testUser), limit.capture());
            assertThat(limit.getValue()).isEqualTo(25);
        }

        @Test
        void shouldForwardZeroLimitBoundaryUnchanged() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("limit", "0")).andExpect(status().isOk());

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getStarredMessages(eq(testUser), limit.capture());
            // Controller does no clamping/validation — the raw value passes straight through.
            assertThat(limit.getValue()).isEqualTo(0);
        }

        @Test
        void shouldForwardLimitOfOneBoundaryUnchanged() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("limit", "1")).andExpect(status().isOk());

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getStarredMessages(eq(testUser), limit.capture());
            assertThat(limit.getValue()).isEqualTo(1);
        }

        @Test
        void shouldForwardNegativeLimitUnchanged() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("limit", "-5")).andExpect(status().isOk());

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getStarredMessages(eq(testUser), limit.capture());
            // No @Min guard on the controller — a negative limit reaches the service verbatim.
            assertThat(limit.getValue()).isEqualTo(-5);
        }

        @Test
        void shouldForwardVeryLargeLimitUnchanged() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt())).thenReturn(List.of());

            mockMvc.perform(get(BASE).param("limit", "1000000")).andExpect(status().isOk());

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(messageService).getStarredMessages(eq(testUser), limit.capture());
            assertThat(limit.getValue()).isEqualTo(1_000_000);
        }

        @Test
        void shouldReturn500WhenLimitNotNumeric() throws Exception {
            authenticate();
            // Type-mismatch on the numeric limit param (MethodArgumentTypeMismatchException) has no
            // dedicated handler → catch-all 500 (pins current behaviour).
            mockMvc.perform(get(BASE).param("limit", "abc"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
            verifyNoInteractions(messageService);
        }

        @Test
        void shouldReturn500OnUnexpectedServiceError() throws Exception {
            authenticate();
            when(messageService.getStarredMessages(eq(testUser), anyInt()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value(INTERNAL_ERROR_CODE));
        }

        @Test
        void shouldMapServiceExceptionStatusAndCode() throws Exception {
            authenticate();
            // ServiceException → GlobalExceptionHandler maps status + messageCode straight through.
            when(messageService.getStarredMessages(eq(testUser), anyInt()))
                    .thenThrow(new ServiceException(503, "Starred store unavailable", "TM_002"));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }
}
