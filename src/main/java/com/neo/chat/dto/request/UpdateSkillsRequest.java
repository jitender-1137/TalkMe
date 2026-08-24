package com.neo.chat.dto.request;

import com.neo.chat.enums.SkillLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for {@code PUT /skills/mine} (feature SKILL_EXCHANGE). Replaces the caller's entire
 * skill set. Each list is a set of skill items ({@code name} + optional {@code level}).
 *
 * <p>Normalisation and limits (trim, blank-rejection, 60-char cap, dedupe, max-count) are
 * enforced in the service so the rules live in one place; the DTO carries the raw input only.
 * {@code level} is optional and advisory — {@code null} means unspecified.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSkillsRequest {

    /**
     * Skills the caller can teach. Null is treated as empty (clears offers).
     */
    private List<SkillItem> offers;

    /**
     * Skills the caller wants to learn. Null is treated as empty (clears wants).
     */
    private List<SkillItem> wants;

    /**
     * A single skill entry: a free-text name plus an optional self-declared proficiency.
     * Level is nullable; when omitted the skill is stored with no level.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillItem {
        private String name;
        private SkillLevel level;
    }
}
