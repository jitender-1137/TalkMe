package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A search result card for the Human Knowledge Network: a person who has an experience
 * matching the caller's query, plus the specific experience tag that matched so the UI can
 * show "ask them about &lt;tag&gt;".
 *
 * <p>Only PII-safe public profile fields are exposed — never the {@code User} entity. The
 * caller opens a normal 1:1 chat with this person via {@code POST /knowledge/{userUuid}/ask}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgePersonResponse {

    // ── Person card ──────────────────────────────────────────────────────────
    private String userUuid;
    private String name;
    private String username;
    private String avatar;
    private String country;
    private String city;
    private String mood;

    /**
     * Apparent presence: "ONLINE", "AWAY", or "OFFLINE" (Invisible-masked at source).
     */
    private String presence;

    /**
     * The caller's-query experience tag this person matched on.
     */
    private ExperienceResponse matchedExperience;
}
