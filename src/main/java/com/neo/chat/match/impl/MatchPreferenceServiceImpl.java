package com.neo.chat.match.impl;

import com.neo.chat.match.MatchPreferenceService;
import com.neo.chat.match.MatchPreferenceSnapshot;
import com.neo.chat.util.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed store for a waiting user's server-only match preference snapshot,
 * serialized as JSON under a per-user key with a 15-minute TTL. All operations fail open
 * (log and swallow) so a Redis hiccup never breaks matchmaking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchPreferenceServiceImpl implements MatchPreferenceService {

    private static final String KEY_PREFIX = "matchmaking:prefs:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Builds the Redis key for a user's preference snapshot.
     *
     * @param username the user's username
     * @return the namespaced Redis key
     */
    private static String key(String username) {
        return KEY_PREFIX + username;
    }

    /**
     * Serializes and stores the user's snapshot with the configured TTL; failures are
     * logged and swallowed.
     *
     * @param username the user's username
     * @param snapshot the preference snapshot to persist
     */
    @Override
    public void save(String username, MatchPreferenceSnapshot snapshot) {
        try {
            redis.opsForValue().set(key(username), objectMapper.writeValueAsString(snapshot), TTL);
        } catch (Exception e) {
            log.warn("Failed to save match prefs for {}: {}", LogSanitizer.mask(username), e.getMessage());
        }
    }

    /**
     * Loads and deserializes the user's snapshot; returns empty when absent or on any
     * read/parse failure.
     *
     * @param username the user's username
     * @return the snapshot, or empty if none stored or on failure
     */
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

    /**
     * Deletes the user's stored snapshot; failures are logged and swallowed.
     *
     * @param username the user's username
     */
    @Override
    public void delete(String username) {
        try {
            redis.delete(key(username));
        } catch (Exception e) {
            log.debug("Failed to delete match prefs for {}: {}", username, e.getMessage());
        }
    }
}
