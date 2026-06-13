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
    private String username;
    private String name;
    private String content;
    private String createdAt;
}
