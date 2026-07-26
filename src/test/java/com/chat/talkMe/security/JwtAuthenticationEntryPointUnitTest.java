package com.chat.talkMe.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link JwtAuthenticationEntryPoint}: unauthenticated access to a protected endpoint
 * must yield a 401 JSON body carrying the {@code TM_105} code.
 */
@DisplayName("JwtAuthenticationEntryPoint (unit)")
class JwtAuthenticationEntryPointUnitTest {

    private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint();

    @Test
    void shouldWrite401JsonBodyWithCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException ex = new BadCredentialsException("no token");

        entryPoint.commence(request, response, ex);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
        String body = response.getContentAsString();
        assertThat(body)
                .contains("\"success\":false")
                .contains("\"messageCode\":\"TM_105\"")
                .contains("Please sign in again");
    }

    @Test
    void shouldProduceParseableErrorEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("x"));

        // Body is a ResponseDto error envelope → data is null, success false.
        String body = response.getContentAsString();
        assertThat(body).contains("\"data\":null");
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
