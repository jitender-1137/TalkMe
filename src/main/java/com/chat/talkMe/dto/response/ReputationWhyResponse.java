package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * "Why is my reputation what it is?" explainer (features #30/#31). Deliberately opaque about
 * mechanics — it exposes contributor LABELS and a coarse magnitude bucket only, never raw
 * points, weights, caps, or formulas, so the scoring can't be reverse-engineered and gamed.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReputationWhyResponse {

    private List<Contributor> contributors;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Contributor {
        /** Human-readable contributor name, e.g. "Lasting friendships". */
        private String contributorLabel;
        /** Coarse bucket: "LOW" | "MED" | "HIGH". No numbers. */
        private String magnitude;
        /** Direction hint: "UP" | "FLAT" | "DOWN". */
        private String trend;
    }
}
