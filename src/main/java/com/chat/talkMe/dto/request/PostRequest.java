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
    private List<PostMediaRequest> media;

    // Optional: when present, this post is created as a poll.
    @jakarta.validation.Valid
    private PollRequest poll;

    // Optional soundtrack.
    private com.chat.talkMe.dto.response.AudioTrackDto audio;

    // Who can see the post: "EVERYONE" (default) or "FRIENDS".
    private String audience;
}
