package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Read-only "Our Story" snapshot of a 1:1 conversation (feature #3.3). Computed on demand
 * from existing message/attachment counts — nothing is persisted. Safe to screenshot/share.
 */
@Data
@Builder
public class ConversationSummaryResponse {

    private String chatUuid;

    // Other participant (for the card header).
    private String otherName;
    private String otherUsername;
    private String otherAvatar;

    // Volume.
    private long totalMessages;
    private long myMessages;
    private long theirMessages;
    private long photosShared;

    // Time.
    private Instant firstMessageAt;
    private long daysKnown;
    private long activeDays;

    // Shared signal.
    private List<String> sharedInterests;

    /** A friendly, generated one-liner headline for the card. */
    private String headline;
}
