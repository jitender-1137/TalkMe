package com.neo.chat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-request filter that authenticates a Bearer access token. Extracts the JWT from the
 * {@code Authorization} header, validates it, loads the user, and — only for enabled (not
 * soft-deleted / banned) accounts — populates the {@link SecurityContextHolder}. Any failure
 * is swallowed so the request simply continues anonymously and downstream security returns 401.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;

    /**
     * Authenticates the request from its Bearer token when present and valid, setting the security
     * context for enabled accounts only; always continues the filter chain regardless of outcome.
     *
     * @param request     the incoming HTTP request
     * @param response    the HTTP response
     * @param filterChain the remaining filter chain
     * @throws ServletException if chain processing fails
     * @throws IOException      if chain processing fails
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                // Resolve the principal by the IMMUTABLE uuid claim when present (uuid-bound tokens);
                // fall back to the username subject only for legacy tokens issued before uuid-binding.
                UserDetails userDetails = resolvePrincipal(jwt);

                // Reject disabled accounts (soft-deleted / pending deletion) even if their
                // access token is still within its lifetime — the request stays anonymous
                // and protected endpoints return 401.
                if (userDetails.isEnabled()) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException ex) {
            // A syntactically valid token whose subject no longer resolves (purged / renamed
            // account) is routine, and attacker-triggerable at will — never a stack trace.
            log.debug("Bearer token subject not found; request continues anonymously");
        } catch (Exception ex) {
            log.warn("Could not set user authentication in security context: {}", ex.toString());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the raw JWT from the {@code Authorization: Bearer <token>} header.
     *
     * @param request the incoming HTTP request
     * @return the token without the {@code "Bearer "} prefix, or {@code null} if absent/malformed
     */
    /**
     * Resolves the token's principal by its {@code uid} claim (immutable), falling back to the
     * username subject for a legacy token that has no {@code uid}.
     */
    private UserDetails resolvePrincipal(String jwt) {
        String uuid = tokenProvider.getUserUuidFromToken(jwt);
        if (StringUtils.hasText(uuid)) {
            try {
                return userDetailsService.loadUserByUuid(java.util.UUID.fromString(uuid));
            } catch (IllegalArgumentException badUuid) {
                // Malformed uid → treat as unresolved (request continues anonymously).
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException("bad uid");
            }
        }
        return userDetailsService.loadUserByUsername(tokenProvider.getUsernameFromToken(jwt));
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
