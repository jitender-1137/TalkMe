package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.MessageType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {
    private String content;
    private String clientId; // client-generated idempotency key (UUID) for safe retries
    private String messageType; // maps to MessageType (default TEXT)
    private String parentMessageId; // parent message uuid (for quotes/replies)
    
    // Attachment details
    private String fileName;
    private Long fileSize;
    private String fileUrl;
    private String mimeType;
    private Double duration;

    // Self-destruct / view-once (media only): null = normal, 0 = view-once,
    // 5/10/30 = destroy N seconds after the receiver opens it.
    private Integer selfDestructSeconds;
}
