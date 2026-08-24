package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Combined "why you two click" payload for the Connect surface: the full
 * {@link CompatibilityScore} (overall %, breakdown, highlights and the itemized
 * {@code commonalities}) plus a handful of ready-to-send {@code icebreakers}, so the UI can
 * show "You have 7 things in common → 'What's the best photo you've ever taken?'" in one call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionInsightResponse {
    private CompatibilityScore compatibility;
    private List<String> icebreakers;
}
