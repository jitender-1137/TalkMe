package com.neo.chat.dto.response;

import com.neo.chat.enums.TalkNowIntent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A light "available now" card for another user (Talk Now). Carries just enough public info to
 * render a pick-list and open a normal 1:1 chat — never the {@code User} entity.
 *
 * <p>{@link #compatibilityBucket} is the coarse HIGH/MEDIUM/LOW label from the compatibility
 * engine; {@link #compatibilityScore} is the 0–100 overall (both are viewer-relative and may be
 * {@code null}/0 if scoring could not be computed).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TalkNowCardResponse {

    private String username;
    private String name;
    private String avatar;

    /**
     * Why this user is available to talk.
     */
    private TalkNowIntent intent;

    private String country;
    private String language;

    /**
     * Coarse compatibility label with the viewer: "HIGH" | "MEDIUM" | "LOW" (nullable).
     */
    private String compatibilityBucket;

    /**
     * Overall 0–100 compatibility with the viewer (0 when not computed).
     */
    private int compatibilityScore;
}
