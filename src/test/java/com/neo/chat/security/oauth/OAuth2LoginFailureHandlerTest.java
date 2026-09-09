package com.neo.chat.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.RedirectStrategy;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link OAuth2LoginFailureHandler} — on a failed/cancelled Google sign-in it
 * bounces the browser back to the SPA login page with an {@code error=oauth} marker instead
 * of Spring's default error page. Covers the redirect target and the base-url trailing-slash
 * trimming.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2LoginFailureHandler (unit)")
class OAuth2LoginFailureHandlerTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private RedirectStrategy redirectStrategy;
    @Mock
    private AuthenticationException exception;

    private OAuth2LoginFailureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginFailureHandler("http://localhost:3000");
        handler.setRedirectStrategy(redirectStrategy);
    }

    @Test
    @DisplayName("failure → redirects to the SPA login page with the oauth error marker")
    void redirectsToLoginWithError() throws Exception {
        when(exception.getMessage()).thenReturn("access_denied");

        handler.onAuthenticationFailure(request, response, exception);

        verify(redirectStrategy).sendRedirect(request, response,
                "http://localhost:3000/#login?error=oauth");
    }

    @Test
    @DisplayName("base url with trailing slashes → trimmed before appending the login hash")
    void trimsTrailingSlashes() throws Exception {
        handler = new OAuth2LoginFailureHandler("https://app.talkme.fun//");
        handler.setRedirectStrategy(redirectStrategy);
        when(exception.getMessage()).thenReturn("boom");

        handler.onAuthenticationFailure(request, response, exception);

        verify(redirectStrategy).sendRedirect(request, response,
                "https://app.talkme.fun/#login?error=oauth");
    }

    @Test
    @DisplayName("null exception message is tolerated (still redirects)")
    void nullMessageTolerated() throws Exception {
        when(exception.getMessage()).thenReturn(null);

        handler.onAuthenticationFailure(request, response, exception);

        verify(redirectStrategy).sendRedirect(request, response,
                "http://localhost:3000/#login?error=oauth");
    }
}
