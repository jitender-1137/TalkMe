package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The caller's own skill profile (feature SKILL_EXCHANGE): what they offer to teach and
 * what they want to learn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillProfileResponse {

    /**
     * Skills the caller can teach.
     */
    private List<SkillItem> offers;

    /**
     * Skills the caller wants to learn.
     */
    private List<SkillItem> wants;

    /**
     * A single skill entry with its optional self-declared level.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillItem {
        private String name;
        /**
         * BEGINNER | INTERMEDIATE | ADVANCED, or null if unspecified.
         */
        private String level;
    }
}
