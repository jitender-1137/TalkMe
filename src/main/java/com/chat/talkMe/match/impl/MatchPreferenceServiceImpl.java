package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.MatchPreferenceService;
import com.chat.talkMe.match.MatchPreferenceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchPreferenceServiceImpl implements MatchPreferenceService {

    private static final String KEY_PREFIX = "matchmaking:prefs:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static String key(String username) {
        return KEY_PREFIX + username;
    }

    @Override
    public void save(String username, MatchPreferenceSnapshot snapshot) {
        try {
            redis.opsForValue().set(key(username), objectMapper.writeValueAsString(snapshot), TTL);
        } catch (Exception e) {
            log.warn("Failed to save match prefs for {}: {}", username, e.getMessage());
        }
    }

    @Override
    public Optional<MatchPreferenceSnapshot> load(String username) {
        try {
            String json = redis.opsForValue().get(key(username));
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, MatchPreferenceSnapshot.class));
        } catch (Exception e) {
            log.debug("Failed to load match prefs for {}: {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String username) {
        try {
            redis.delete(key(username));
        } catch (Exception e) {
            log.debug("Failed to delete match prefs for {}: {}", username, e.getMessage());
        }
    }
}
