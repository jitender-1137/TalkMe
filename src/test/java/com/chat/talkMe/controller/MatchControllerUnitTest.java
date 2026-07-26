package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MatchSessionResponse;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.match.MatchmakingService;
import com.chat.talkMe.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller unit test for {@link MatchController}.
 *
 * <p>Standalone {@link MockMvc} with a mocked {@link MatchmakingService} and the real
 * {@link GlobalExceptionHandler}. {@code /match/session} is authenticated; {@code /match/online}
 * takes no principal.
 *
 * <p><b>Scope boundary:</b> filter-chain auth is out of scope for a controller unit test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchController (unit)")
class MatchControllerUnitTest {

    private static final String SESSION = "/match/session";
    private static final String ONLINE = "/match/online";

    @Mock
    private MatchmakingService matchmakingService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        MatchController controller = new MatchController(matchmakingService);

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

    @Nested
    @DisplayName("GET /match/session")
    class CheckMatch {

        @Test
        void shouldReturnActiveSession() throws Exception {
            when(matchmakingService.checkMatch(testUser)).thenReturn(
                    MatchSessionResponse.builder()
                            .id("sess-1").chatId("chat-1").isActive(true).mode("FLIRT").build());

            mockMvc.perform(get(SESSION))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.messageCode").value("TM_000"))
                    .andExpect(jsonPath("$.data.id").value("sess-1"))
                    .andExpect(jsonPath("$.data.chatId").value("chat-1"))
                    // Lombok boolean `isActive` serializes as JSON `active`.
                    .andExpect(jsonPath("$.data.active").value(true))
                    .andExpect(jsonPath("$.data.mode").value("FLIRT"));

            verify(matchmakingService).checkMatch(testUser);
        }

        @Test
        void shouldReturnInactiveSessionWhenNoMatch() throws Exception {
            when(matchmakingService.checkMatch(any()))
                    .thenReturn(MatchSessionResponse.builder().isActive(false).build());

            mockMvc.perform(get(SESSION))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.active").value(false));
        }

        @Test
        void shouldReturn500WhenServiceFails() throws Exception {
            when(matchmakingService.checkMatch(any())).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(SESSION))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }

    @Nested
    @DisplayName("GET /match/online")
    class OnlineCount {

        @Test
        void shouldReturnOnlineCount() throws Exception {
            when(matchmakingService.getOnlineCount()).thenReturn(42L);

            mockMvc.perform(get(ONLINE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.count").value(42));

            verify(matchmakingService).getOnlineCount();
        }

        @Test
        void shouldReturnZeroWhenNobodyOnline() throws Exception {
            when(matchmakingService.getOnlineCount()).thenReturn(0L);

            mockMvc.perform(get(ONLINE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.count").value(0));
        }

        @Test
        void shouldReturn500WhenServiceFails() throws Exception {
            when(matchmakingService.getOnlineCount()).thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(ONLINE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.messageCode").value("TM_002"));
        }
    }
}
