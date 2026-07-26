package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {
    // Ciphertext is larger than plaintext; 8192 comfortably holds a 4096-char
    // message encrypted + base64'd while still bounding abusive payloads. Empty
    // is allowed — media-only messages carry no text.
    @Size(max = 8192, message = "Message is too long")
    private String content;
    @Size(max = 100)
    private String clientId; // client-generated idempotency key (UUID) for safe retries
    @Size(max = 32)
    private String messageType; // maps to MessageType (default TEXT)
    @Size(max = 64)
    private String parentMessageId; // parent message uuid (for quotes/replies)
    private boolean forwarded;      // true when this message is a WhatsApp-style forward

    // Attachment details
    @Size(max = 512)
    private String fileName;
    private Long fileSize;
    @Size(max = 4096)
    private String fileUrl;
    @Size(max = 128)
    private String mimeType;
    private Double duration;

    // Self-destruct / view-once (media only): null = normal, 0 = view-once,
    // 5/10/30 = destroy N seconds after the receiver opens it.
    private Integer selfDestructSeconds;

    // Media download permission (media only): false (default) = receiver cannot
    // download/save; true = sender opted to let the receiver download THIS media.
    private boolean allowDownload;

    // UUIDs of members @mentioned (group/room/channel messages).
    private List<String> mentionedUserIds;
}
