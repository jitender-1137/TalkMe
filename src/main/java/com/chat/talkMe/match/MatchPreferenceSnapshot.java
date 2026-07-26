package com.chat.talkMe.match;

import com.chat.talkMe.enums.GenderPreference;
import com.chat.talkMe.enums.MatchMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * The server-only preference/attribute snapshot for a user waiting in the matchmaking
 * queue. Serialized to Redis on enqueue and read during compatibility-aware pairing —
 * it never leaves the server, preserving the anonymity invariant. Holds BOTH the user's
 * own attributes (so a candidate can test eligibility against them) and their filters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchPreferenceSnapshot {

    // ── Own attributes (from the User entity) ──
    private String ownGender;              // normalized upper, e.g. "MALE"
    private Integer ownAge;
    private String ownCountry;
    @Builder.Default
    private Set<String> ownLanguages = new HashSet<>();
    private boolean ownVerified;
    private String mood;
    private String energy;

    // ── Filters (from the client) ──
    @Builder.Default
    private GenderPreference genderPref = GenderPreference.ANY;
    private Integer ageMin;
    private Integer ageMax;
    private String countryFilter;         // null = any
    private String languageFilter;        // required language (enum name) or null
    private boolean verifiedOnly;
    private boolean moodCompatibleOnly;

    @Builder.Default
    private MatchMode mode = MatchMode.QUICK;

    /** Timed-session duration in minutes for COFFEE/CHEMISTRY (5/10/15). */
    private Integer durationMin;

    private long enqueuedAtEpochMs;

    /** True when this snapshot carries no meaningful filters (legacy blind-match path). */
    public boolean hasNoFilters() {
        return (genderPref == null || genderPref == GenderPreference.ANY)
                && ageMin == null && ageMax == null
                && (countryFilter == null || countryFilter.isBlank())
                && (languageFilter == null || languageFilter.isBlank())
                && !verifiedOnly && !moodCompatibleOnly;
    }
}
