package com.neo.chat.service.impl;

import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.service.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed brute-force guard for the login flow. Tracks failed attempts by
 * username AND by IP so that IP-rotation attacks still trip the account limit,
 * and single-IP attacks trip the IP limit. Fails open if Redis is unavailable
 * (the general rate limiter remains as a backstop).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptServiceImpl implements LoginAttemptService {

    private final StringRedisTemplate redisTemplate;

    /**
     * Lock an account after this many failures within the window.
     */
    private static final int USER_MAX_ATTEMPTS = 5;
    /**
     * Lock an IP after this many failures within the window (looser — shared NAT).
     */
    private static final int IP_MAX_ATTEMPTS = 20;
    /**
     * Sliding lockout window in seconds.
     */
    private static final long WINDOW_SECONDS = 15 * 60;

    /**
     * Reject the login when either the per-username or per-IP failure count has hit its limit within
     * the sliding window. Fails open (never blocks) if Redis is unavailable.
     *
     * @param username the login username (skipped if null/blank)
     * @param ip       the client IP (skipped if null/blank)
     * @throws com.neo.chat.exception.TooManyRequestsException if the account or IP limit is reached
     */
    @Override
    public void assertNotBlocked(String username, String ip) {
        if (username != null && !username.isBlank() && count(userKey(username)) >= USER_MAX_ATTEMPTS) {
            throw new TooManyRequestsException(
                    "Too many failed attempts for this account. Please try again later.", "TM_429");
        }
        if (ip != null && !ip.isBlank() && count(ipKey(ip)) >= IP_MAX_ATTEMPTS) {
            throw new TooManyRequestsException(
                    "Too many failed login attempts. Please try again later.", "TM_429");
        }
    }

    /**
     * Increment the per-username and per-IP failure counters (first hit sets the window TTL).
     *
     * @param username the login username (skipped if null/blank)
     * @param ip       the client IP (skipped if null/blank)
     */
    @Override
    public void recordFailure(String username, String ip) {
        if (username != null && !username.isBlank()) increment(userKey(username));
        if (ip != null && !ip.isBlank()) increment(ipKey(ip));
    }

    /**
     * Clear the per-username and per-IP failure counters after a successful login (best-effort).
     *
     * @param username the login username (skipped if null/blank)
     * @param ip       the client IP (skipped if null/blank)
     */
    @Override
    public void recordSuccess(String username, String ip) {
        try {
            if (username != null && !username.isBlank()) redisTemplate.delete(userKey(username));
            if (ip != null && !ip.isBlank()) redisTemplate.delete(ipKey(ip));
        } catch (Exception e) {
            log.error("[LoginAttempt] Redis error clearing counters", e);
        }
    }

    /**
     * Read the current counter value for a key, returning 0 (fail-open) on any Redis error.
     */
    private long count(String key) {
        try {
            String v = redisTemplate.opsForValue().get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            log.error("[LoginAttempt] Redis error reading {}, failing open", key, e);
            return 0; // fail open
        }
    }

    /**
     * Increment a counter, setting the sliding-window TTL on its first increment (best-effort).
     */
    private void increment(String key) {
        try {
            Long c = redisTemplate.opsForValue().increment(key);
            if (c != null && c == 1L) {
                redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("[LoginAttempt] Redis error incrementing {}", key, e);
        }
    }

    private String userKey(String username) {
        return "login:fail:user:" + username.toLowerCase();
    }

    private String ipKey(String ip) {
        return "login:fail:ip:" + ip;
    }
}
