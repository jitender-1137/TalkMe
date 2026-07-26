package com.chat.talkMe.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link JwtAuthenticationFilter#doFilterInternal}. Invokes the filter directly with
 * mocked {@link JwtTokenProvider} / {@link CustomUserDetailsService}, a real MockHttpServletRequest,
 * and a mock chain — asserting when the SecurityContext gets an Authentication and that the chain is
 * ALWAYS continued (the filter never blocks; it just leaves the request anonymous on failure).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter (unit)")
class JwtAuthenticationFilterUnitTest {

    @Mock private JwtTokenProvider tokenProvider;
    @Mock private CustomUserDetailsService userDetailsService;
    @Mock private FilterChain chain;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserDetails enabledUser() {
        UserDetails ud = org.mockito.Mockito.mock(UserDetails.class);
        when(ud.isEnabled()).thenReturn(true);
        doReturn(List.of()).when(ud).getAuthorities();
        return ud;
    }

    @Test
    void shouldAuthenticateWhenTokenValidAndUserEnabled() throws Exception {
        UserDetails ud = enabledUser();
        request.addHeader("Authorization", "Bearer good.token");
        when(tokenProvider.validateToken("good.token")).thenReturn(true);
        when(tokenProvider.getUsernameFromToken("good.token")).thenReturn("alice");
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(ud);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(ud);
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldStayAnonymousWhenNoAuthorizationHeader() throws Exception {
        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(tokenProvider, never()).validateToken(any());
    }

    @Test
    void shouldStayAnonymousForNonBearerHeader() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(tokenProvider, never()).validateToken(any());
    }

    @Test
    void shouldStayAnonymousWhenBearerTokenBlank() throws Exception {
        request.addHeader("Authorization", "Bearer ");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(tokenProvider, never()).validateToken(any());
    }

    @Test
    void shouldStayAnonymousWhenTokenInvalid() throws Exception {
        request.addHeader("Authorization", "Bearer bad.token");
        when(tokenProvider.validateToken("bad.token")).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldRejectDisabledAccountEvenWithValidToken() throws Exception {
        UserDetails disabled = org.mockito.Mockito.mock(UserDetails.class);
        when(disabled.isEnabled()).thenReturn(false);
        request.addHeader("Authorization", "Bearer good.token");
        when(tokenProvider.validateToken("good.token")).thenReturn(true);
        when(tokenProvider.getUsernameFromToken("good.token")).thenReturn("banned");
        when(userDetailsService.loadUserByUsername("banned")).thenReturn(disabled);

        filter.doFilterInternal(request, response, chain);

        // Disabled (soft-deleted / banned) → request stays anonymous, protected endpoints 401.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSwallowProviderExceptionAndContinueChain() throws Exception {
        request.addHeader("Authorization", "Bearer boom.token");
        when(tokenProvider.validateToken("boom.token")).thenReturn(true);
        when(tokenProvider.getUsernameFromToken("boom.token"))
                .thenThrow(new RuntimeException("parse blew up"));

        filter.doFilterInternal(request, response, chain);

        // Exception is logged and swallowed; the request proceeds anonymously.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }
}
