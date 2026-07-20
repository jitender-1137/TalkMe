package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {
    private String id;
    private String title;
    private String content;
    private String type;
    private boolean isRead;
    private String referenceId;
    // Instagram-style rich fields (who triggered it + target thumbnail).
    private String actorId;
    private String actorName;
    private String actorAvatar;
    private String imageUrl;
    private String createdAt;
}
