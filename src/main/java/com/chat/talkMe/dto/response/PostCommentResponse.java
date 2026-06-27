package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostCommentResponse {
    private String id; // maps uuid
    private String userId; // comment author's user uuid
    private String username;
    private String name;
    private String profileImage;
    private String content;
    private String createdAt;
    private long likesCount;
    private boolean likedByMe;
    private String parentId;   // null for top-level comments; thread-root uuid for replies
    private long replyCount;    // number of (non-deleted) replies under this comment
}
