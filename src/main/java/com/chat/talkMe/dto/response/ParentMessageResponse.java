package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParentMessageResponse {
    private String id;
    private String content;
    private String senderId;
    // Parent's type + first-attachment preview so a reply quote can render the right
    // label ("Photo"/"Video"/…) and a thumbnail for ANY parent message type.
    private String messageType;
    private String fileUrl;
    private String fileName;
}
