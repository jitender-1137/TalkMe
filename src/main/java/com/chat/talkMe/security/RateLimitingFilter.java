package com.chat.talkMe.security;
 
import com.chat.talkMe.dto.response.ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
 
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
 
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {
 
    private final StringRedisTemplate redisTemplate;
    private final Environment env;
 
    // Rate Limit Config
    private static final int LIMIT = 100; // Requests per minute
    private static final int WINDOW_SECONDS = 60;
 
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        if (env.acceptsProfiles(Profiles.of("local", "default")) || env.getActiveProfiles().length == 0) {
            filterChain.doFilter(request, response);
            return;
        }
 
        String ip = request.getRemoteAddr();
        String key = "rate:limit:" + ip;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            if (count != null && count > LIMIT) {
                log.warn("Rate limit exceeded for IP: {}", ip);
                sendRateLimitError(response);
                return;
            }
        } catch (Exception e) {
            // Fallback in case Redis is down - log and pass request through to avoid lockouts
            log.error("Redis error in RateLimitingFilter", e);
        }

        filterChain.doFilter(request, response);
    }

    private void sendRateLimitError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");

        ResponseDto<Void> responseDto = ResponseDto.error(
                "Too many requests. Please slow down.", 
                "TM_007"
        );

        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(responseDto));
    }
}
