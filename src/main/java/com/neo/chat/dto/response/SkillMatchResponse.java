package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A peer-to-peer study match card (feature SKILL_EXCHANGE): another user with whom the caller
 * can exchange skills. {@code theyCanTeachYou} are the caller's WANTs the partner OFFERs;
 * {@code youCanTeachThem} are the caller's OFFERs the partner WANTs. A {@code reciprocal} match
 * (both lists non-empty) is ranked above one-way ones.
 *
 * <p>This card is also the payload of {@code POST /skills/{userUuid}/study} — it carries the
 * partner's public info so the client can open a 1:1 chat.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMatchResponse {

    private String userUuid;
    private String name;
    private String username;
    private String avatar;
    private String country;

    /**
     * Skills the partner offers that the caller wants to learn.
     */
    private List<String> theyCanTeachYou;

    /**
     * Skills the caller offers that the partner wants to learn.
     */
    private List<String> youCanTeachThem;

    /**
     * True when the exchange goes both ways (both skill lists are non-empty).
     */
    private boolean reciprocal;

    /**
     * Compatibility with the partner (used as a ranking tie-break); may be null.
     */
    private CompatibilityScore compatibility;

    /**
     * Whether the partner is currently online (presence-derived, best-effort).
     */
    private boolean online;
}
