package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostRequest {
    private String content;
    // Optional rich-text JSON for formatted text posts (see Post.richContent).
    private String richContent;
    // Optional separate caption for text posts (see Post.caption).
    private String caption;
    private List<PostMediaRequest> media;

    // Optional: when present, this post is created as a poll.
    @jakarta.validation.Valid
    private PollRequest poll;

    // Optional soundtrack.
    private com.chat.talkMe.dto.response.AudioTrackDto audio;

    // Who can see the post: "EVERYONE" (default) or "FRIENDS".
    private String audience;

    // Optional TTL for a temporary post (feature #22): seconds until it expires and is
    // reaped. null/<=0 = permanent. Server clamps to [MIN, MAX]. Requires TEMPORARY_POSTS.
    private Integer expiresInSeconds;
}
