package com.chat.talkMe.controller;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ReferralSummaryResponse;
import com.chat.talkMe.dto.response.ReferredUserResponse;
import com.chat.talkMe.exception.GlobalExceptionHandler;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ReferralService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralController (unit)")
class ReferralControllerUnitTest {

    @Mock
    private ReferralService referralService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        ReferralController controller = new ReferralController(referralService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder().username("alice").name("Alice").isGuest(false).roles(Set.of(role)).build();
        CustomUserDetails principal = new CustomUserDetails(testUser);
        Authentication auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnMyReferralSummary() throws Exception {
        ReferralSummaryResponse summary = ReferralSummaryResponse.builder()
                .username("alice")
                .referralCount(2)
                .referrals(List.of(
                        ReferredUserResponse.builder().id("u-2").name("Bob").username("bob").build(),
                        ReferredUserResponse.builder().id("u-3").name("Cara").username("cara").build()))
                .build();
        when(referralService.getMySummary(testUser)).thenReturn(summary);

        mockMvc.perform(get("/referrals/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.referralCount").value(2))
                .andExpect(jsonPath("$.data.referrals[0].username").value("bob"));

        verify(referralService).getMySummary(testUser);
    }

    @Test
    void shouldReturnZeroWhenNoReferrals() throws Exception {
        when(referralService.getMySummary(any()))
                .thenReturn(ReferralSummaryResponse.builder()
                        .username("alice").referralCount(0).referrals(List.of()).build());

        mockMvc.perform(get("/referrals/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referralCount").value(0))
                .andExpect(jsonPath("$.data.referrals").isEmpty());
    }
}
