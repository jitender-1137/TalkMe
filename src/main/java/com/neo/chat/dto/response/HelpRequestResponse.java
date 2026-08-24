package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single Community Help request as surfaced in the feed / on post / on resolve (feature #9).
 * Carries the asker's public info ({@link HelpUserInfo}) — this feed is not anonymous.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpRequestResponse {
    private String uuid;
    private String city;
    private String category;
    private String body;
    private String status;
    private int answerCount;
    private String expiresAt;
    private String createdAt;
    private HelpUserInfo asker;
}
