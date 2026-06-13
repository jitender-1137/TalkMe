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
    private String messageType; // maps to MessageType (default TEXT)
    private String parentMessageId; // parent message uuid (for quotes/replies)
    
    // Attachment details
    private String fileName;
    private Long fileSize;
    private String fileUrl;
    private String mimeType;
    private Double duration;
}
