package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostResponse {
    private String id; // maps uuid
    private String shortCode; // for shareable /post/{shortCode} links
    private AuthUserResponse user;
    private String content;
    private List<PostMediaResponse> media;
    private int likesCount;
    private int commentsCount;
    private boolean likedByMe;
    private boolean bookmarkedByMe;
    private String createdAt;
    private List<PostCommentResponse> comments;
    private PollResponse poll; // non-null only for poll posts
    private AudioTrackDto audio; // non-null only when a soundtrack is attached
    private String audience; // EVERYONE | FRIENDS
}
