package com.chat.talkMe.config;

import com.chat.talkMe.security.CsrfTokenFilter;
import com.chat.talkMe.security.JwtAuthenticationEntryPoint;
import com.chat.talkMe.security.JwtAuthenticationFilter;
import com.chat.talkMe.security.RateLimitingFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationEntryPoint unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CsrfTokenFilter csrfTokenFilter;
    private final RateLimitingFilter rateLimitingFilter;


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Custom CsrfTokenFilter handles CSRF check
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(unSecured()).permitAll()
                        .anyRequest().authenticated()
                );

        // Filter sequence: Rate Limiting -> CSRF -> JWT -> UsernamePasswordAuth
        http.addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(csrfTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Returns an array of endpoints that are not secured.
     * <p>
     * This method provides a list of endpoint patterns that are allowed without authentication,
     * including authentication-related endpoints, Swagger documentation, and health checks.
     *
     * @return an array of unsecured endpoint patterns.
     */
    private String[] unSecured() {
        return new String[]{
                "/auth/login",
                "/auth/signup",
                "/auth/refresh",
                "/auth/forgot-password",
                "/auth/reset-password",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/ws/**",
                "/uploads/media",
                "/users/lobby"
        };
    }
}
