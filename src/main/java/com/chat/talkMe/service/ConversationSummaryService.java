package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConversationSummaryResponse;

/**
 * "Our Story" conversation summary (feature #3.3). Read-only; derived from existing
 * message/attachment counts and the pair's shared profile signal. No LLM, no persistence.
 */
public interface ConversationSummaryService {

    /**
     * Build a summary of the 1:1 chat identified by {@code chatUuid} from the caller's
     * perspective. The caller must be a member of the chat (else Forbidden/NotFound).
     */
    ConversationSummaryResponse summarize(User me, String chatUuid);
}
