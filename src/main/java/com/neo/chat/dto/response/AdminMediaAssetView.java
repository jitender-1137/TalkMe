package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@code media_assets} row for the per-user / per-conversation admin media lists.
 * Carries the owner so the conversation view can show who sent each file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMediaAssetView {
    private String id;              // media asset uuid
    private String key;             // storage key
    private String reference;
    private String url;             // serve URL
    private String kind;            // image / video / audio / file
    private String context;         // STRANGER / LOBBY / CONVERSATION / …
    private String contextId;       // conversation uuid for CONVERSATION uploads
    private String uploadType;
    private String contentType;
    private long fileSize;
    private String originalFileName;
    private boolean strangerMode;   // uploader was anonymous to the chat peer
    private String uploadedAt;

    // Owner (present on the conversation view; on the user view it's implied)
    private String ownerId;
    private String ownerUsername;
    private String ownerName;
    private String ownerAvatar;
}
