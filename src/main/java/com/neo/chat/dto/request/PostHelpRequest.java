package com.neo.chat.dto.request;

import com.neo.chat.enums.HelpCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /community-help} (feature #9, COMMUNITY_HELP). Posts a short-lived,
 * city-scoped question.
 *
 * <p>{@code city} is optional — when blank it defaults to the caller's own city. {@code category}
 * is optional and defaults to {@link HelpCategory#OTHER}. Trimming, length caps and moderation are
 * enforced in the service so the rules live in one place.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostHelpRequest {

    /**
     * City to scope the question to; null/blank defaults to the caller's own city.
     */
    @Size(max = 120, message = "City must be at most 120 characters")
    private String city;

    /**
     * Bucket for the question; null defaults to {@link HelpCategory#OTHER}.
     */
    private HelpCategory category;

    @NotBlank(message = "Question must not be blank")
    @Size(max = 500, message = "Question must be at most 500 characters")
    private String body;
}
