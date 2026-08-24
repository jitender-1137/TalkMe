package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A single advice question together with its (anonymised) replies (feature ADVICE_ROOMS). Both the
 * question and every reply carry NO author fields — the whole thread is anonymous.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdviceThreadResponse {
    private AdviceQuestionResponse question;
    private List<AdviceReplyResponse> replies;
}
