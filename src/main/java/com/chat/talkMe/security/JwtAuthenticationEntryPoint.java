package com.chat.talkMe.security;

import com.chat.talkMe.dto.response.ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Authentication entry point for unauthenticated access to protected endpoints. Writes a JSON
 * {@code ResponseDto} error with HTTP 401 and app code {@code TM_105} instead of the default
 * HTML/redirect, so the SPA can detect the expired/unauthorized session and prompt re-login.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /**
     * Sends a 401 JSON error response ({@code TM_105}) when authentication is missing or invalid.
     *
     * @param request       the request that failed authentication
     * @param response      the response to write the JSON error into
     * @param authException the triggering authentication exception
     * @throws IOException if writing the response body fails
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");

        ResponseDto<Void> responseDto = ResponseDto.error(
                "Session expired or unauthorized. Please sign in again.",
                "TM_105"
        );

        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(responseDto));
    }
}
