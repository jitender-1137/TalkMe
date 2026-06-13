package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryResponse {
    private String id; // maps uuid
    private AuthUserResponse user;
    private String mediaUrl;
    private String caption;
    private String expiresAt;
    private String createdAt;
    private boolean viewedByMe;
}
