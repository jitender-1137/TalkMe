package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single experience tag as returned to its owner ({@code GET /knowledge/mine}) or embedded
 * in a search card as the tag that matched.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperienceResponse {

    /**
     * The experience row's UUID (owner view only; null when embedded as a matched tag).
     */
    private String uuid;

    private String tag;

    /**
     * Category name (STRING enum), e.g. "RELOCATION".
     */
    private String category;

    private String note;

    private boolean openToQuestions;
}
