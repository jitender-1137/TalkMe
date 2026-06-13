package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageAttachmentResponse {
    private String id; // maps to uuid
    private String fileName;
    private Long fileSize;
    private String fileUrl;
    private String thumbnailUrl;
    private String mimeType;
    private Double duration;
}
