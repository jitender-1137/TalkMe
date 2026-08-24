package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Result of the compatibility engine. {@code overall} is 0–100; {@code breakdown} maps
 * each factor name → its 0–100 contribution-normalized score; {@code highlights} are the
 * top shared signals; {@code explanation} is human-readable copy. Never contains identity
 * — callers decide how much to expose (anonymous surfaces show only a coarse bucket).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompatibilityScore {
    private int overall;
    private Map<String, Integer> breakdown;
    private List<String> highlights;
    private String explanation;
    /**
     * Coarse label for anonymous surfaces: "HIGH" | "MEDIUM" | "LOW".
     */
    private String bucket;
    /**
     * Concrete shared things between the two users — shared interest names, shared languages,
     * shared "looking for" tags, the shared country, and a same/adjacent mood or energy label.
     * Distinct from {@code highlights} (which is short human copy): this is the itemized list a
     * UI can count ("You have 7 things in common"). Possibly empty, never null.
     */
    private List<String> commonalities;
}
