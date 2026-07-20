package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/** A user who liked a post, for the admin news view. */
@Data
@Builder
public class AdminPostLikeView {
    private String id;            // like uuid
    private String userId;        // uuid
    private String username;
    private String name;
    private String avatar;
    private String createdAt;
}
