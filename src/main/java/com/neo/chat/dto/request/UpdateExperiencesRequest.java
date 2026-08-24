package com.neo.chat.dto.request;

import com.neo.chat.enums.ExperienceCategory;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Replace-the-whole-set payload for {@code PUT /knowledge/mine}: the caller's full list of
 * experience tags for the Human Knowledge Network. Fine-grained rules (non-blank tag, ~30
 * cap, trim, dedupe) are enforced in the service and surface as {@code TM_9xx} errors — this
 * DTO only carries a coarse upper bound so a runaway payload is rejected early.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateExperiencesRequest {

    @Size(max = 100, message = "Too many experiences")
    private List<ExperienceItem> experiences;

    /**
     * A single experience the caller claims. {@code category} defaults to
     * {@link ExperienceCategory#OTHER} when omitted; {@code openToQuestions} defaults to true.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExperienceItem {
        @Size(max = 80, message = "Experience tag must not exceed 80 characters")
        private String tag;

        private ExperienceCategory category;

        @Size(max = 280, message = "Note must not exceed 280 characters")
        private String note;

        private Boolean openToQuestions;
    }
}
