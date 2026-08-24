package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A Local Discovery result card: a person in the same city as the caller (case-insensitive
 * exact match) who optionally shares an interest. Used by "photographers near Pune" /
 * "people learning Java in Delhi".
 *
 * <p>Only PII-safe public profile fields are exposed — never the {@code User} entity, and
 * <b>never any coordinates</b> (none exist; location is a city/country string only). The
 * caller opens a normal 1:1 chat with this person via the client draft flow.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalPersonResponse {

    // ── Person card ──────────────────────────────────────────────────────────
    private String userUuid;
    private String name;
    private String username;
    private String avatar;

    /**
     * City/country strings only — coordinates are never exposed because none exist.
     */
    private String country;
    private String city;
    private String mood;

    /**
     * Apparent presence: "ONLINE", "AWAY", or "OFFLINE" (Invisible-masked at source).
     */
    private String presence;

    /**
     * Interests this person shares with the caller (uppercase enum names), for the UI to show
     * "also into PHOTOGRAPHY, TRAVEL". Empty when there is no overlap.
     */
    private List<String> sharedInterests;
}
