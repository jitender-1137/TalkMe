package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/** A comment (or reply) on a post, for the admin news view. */
@Data
@Builder
public class AdminPostCommentView {
    private String id;            // comment uuid
    private String userId;        // uuid
    private String username;
    private String name;
    private String avatar;
    private String content;
    private String parentId;      // uuid of parent comment (null = top-level)
    private String createdAt;
}
