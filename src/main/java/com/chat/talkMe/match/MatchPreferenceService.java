package com.chat.talkMe.match;

import java.util.Optional;

/** Stores each waiting user's server-only preference snapshot in Redis (TTL-bounded). */
public interface MatchPreferenceService {
    void save(String username, MatchPreferenceSnapshot snapshot);
    Optional<MatchPreferenceSnapshot> load(String username);
    void delete(String username);
}
