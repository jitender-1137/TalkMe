package com.chat.talkMe.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostMediaRequest {
    private String mediaUrl;
    private String mediaType; // IMAGE, VIDEO

    // ── Optional video edit metadata (null for images / un-edited videos) ──
    private Double trimStartSec;
    private Double trimEndSec;
    private Boolean muted;
    private String coverImageUrl;
    private String filterName;

    @JsonCreator
    public static PostMediaRequest fromString(String mediaUrl) {
        String mediaType = "IMAGE";
        if (mediaUrl != null) {
            String lower = mediaUrl.toLowerCase();
            if (lower.contains(".mp4") || lower.contains(".mov") || lower.contains(".avi") || lower.contains(".webm") || lower.contains("video")) {
                mediaType = "VIDEO";
            }
        }
        PostMediaRequest req = new PostMediaRequest();
        req.mediaUrl = mediaUrl;
        req.mediaType = mediaType;
        return req;
    }
}

