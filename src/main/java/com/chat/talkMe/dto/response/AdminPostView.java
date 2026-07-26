package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** A feed post as seen by an admin — author, content, media and engagement counts. */
@Data
@Builder
public class AdminPostView {
    private String id;            // post uuid
    private String shortCode;
    private String authorId;      // uuid
    private String authorUsername;
    private String authorName;
    private String authorAvatar;
    private String content;
    private String audience;      // EVERYONE / FRIENDS
    private long likeCount;
    private long commentCount;
    private boolean hasPoll;
    private boolean hasAudio;
    private List<Media> media;
    private String createdAt;
    private boolean deleted;       // soft-deleted post (admin sees it flagged)

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Media {
        private String url;
        private String type;      // IMAGE / VIDEO
    }
}
