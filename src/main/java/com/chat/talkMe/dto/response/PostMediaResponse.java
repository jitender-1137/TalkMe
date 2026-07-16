package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostMediaResponse {
    private String id; // maps uuid
    private String mediaUrl;
    private String mediaType;

    // ── Video edit metadata (null for images / un-edited videos) ──
    private Double trimStartSec;
    private Double trimEndSec;
    private Boolean muted;
    private String coverImageUrl;
    private String filterName;
}
