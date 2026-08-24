package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A cursor-paginated page of advice questions (feature ADVICE_ROOMS). {@code nextCursor} is an
 * opaque token to pass back as {@code cursor} for the next page; it is {@code null} when no more
 * results remain ({@code hasMore == false}). All items are fully anonymised.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdviceQuestionPageResponse {
    private List<AdviceQuestionResponse> items;
    private String nextCursor;
    private boolean hasMore;
}
