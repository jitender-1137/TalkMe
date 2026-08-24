package com.neo.chat.dto.request;

import com.neo.chat.enums.AdviceCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /advice/questions (feature ADVICE_ROOMS). The asker's identity is taken from the
 * authenticated principal and never echoed back — questions are anonymous.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AskQuestionRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 4000)
    private String body;

    /**
     * Optional topic bucket; defaults to {@link AdviceCategory#OTHER} when absent.
     */
    private AdviceCategory category;
}
