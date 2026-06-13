package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    private String id; // maps to uuid
    private String senderId; // maps to sender.uuid
    private String content;
    private String messageType; // MessageType enum representation
    private String createdAt;
    private boolean isEdited;
    private List<MessageReactionResponse> reactions;
    private List<MessageAttachmentResponse> attachments;
    private String status; // SENT, DELIVERED, READ
    private ParentMessageResponse parentMessage;
}
