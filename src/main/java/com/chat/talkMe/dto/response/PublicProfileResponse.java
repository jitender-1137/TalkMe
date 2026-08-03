package com.chat.talkMe.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Deliberately-trimmed public projection of a user, served UNAUTHENTICATED by
 * {@code GET /users/by-username/{username}} so a shared profile link ({@code /@username})
 * renders for logged-out visitors and crawlers.
 * <p>
 * PII is intentionally omitted — NO phone, email, roles, age, gender, or location. This mirrors
 * the {@code /users/lobby} PII-removal lesson (see SecurityConfig#unSecured): an anonymous
 * endpoint must never leak contact details. Everything here is already public on the profile
 * card. A logged-in viewer re-fetches the full authenticated {@code /users/{id}} for
 * friend/messaging state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicProfileResponse {
    private String id; // maps to user.uuid
    private String name;
    private String username;
    private String avatar;
    private String bio;

    @JsonProperty("isVerified")
    private boolean isVerified;

    private String presence; // "online", "idle", "offline"
    private String createdAt; // "member since"

    private long followersCount;
    private long followingCount;
    private long postsCount;

    // ── Cosmetic reputation summary (features #30/#31) — no raw weights ──
    private int level;
    private String starRank;
    private int prestigeCount;
}
