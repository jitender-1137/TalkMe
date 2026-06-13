package com.chat.talkMe.config;

import com.chat.talkMe.security.CsrfTokenFilter;
import com.chat.talkMe.security.JwtAuthenticationEntryPoint;
import com.chat.talkMe.security.JwtAuthenticationFilter;
import com.chat.talkMe.security.RateLimitingFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationEntryPoint unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CsrfTokenFilter csrfTokenFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final Environment env;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.cors.allowed-methods}")
    private String allowedMethods;

    @Value("${app.cors.allowed-headers}")
    private String allowedHeaders;

    @Value("${app.cors.exposed-headers}")
    private String exposedHeaders;

    @Value("${app.cors.allow-credentials}")
    private boolean allowCredentials;

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
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable) // Custom CsrfTokenFilter handles CSRF check
            .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedHandler))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
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
                ).permitAll()
                .anyRequest().authenticated()
            );

        // Filter sequence: Rate Limiting -> CSRF -> JWT -> UsernamePasswordAuth
        http.addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(csrfTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        if (env.acceptsProfiles(Profiles.of("local", "default")) || env.getActiveProfiles().length == 0) {
            configuration.setAllowedOriginPatterns(Collections.singletonList("*"));
            configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(Collections.singletonList("*"));
            configuration.setExposedHeaders(Collections.singletonList("*"));
            configuration.setAllowCredentials(true);
        } else {
            // Parse comma-separated list of origins
            if (allowedOrigins != null && !allowedOrigins.isBlank()) {
                configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
            } else {
                configuration.setAllowedOrigins(Collections.singletonList("*"));
            }
            
            if (allowedMethods != null && !allowedMethods.isBlank()) {
                configuration.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
            }
            
            if (allowedHeaders != null && !allowedHeaders.isBlank()) {
                configuration.setAllowedHeaders(Arrays.asList(allowedHeaders.split(",")));
            }
            
            if (exposedHeaders != null && !exposedHeaders.isBlank()) {
                configuration.setExposedHeaders(Arrays.asList(exposedHeaders.split(",")));
            }
            
            configuration.setAllowCredentials(allowCredentials);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
