package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single answer to a Community Help request (feature #9). Carries the answerer's public info —
 * this feed is not anonymous — plus the uuid of the request it threads off.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpAnswerResponse {
    private String uuid;
    private String requestUuid;
    private String body;
    private String createdAt;
    private HelpUserInfo answerer;
}
