package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryResponse {
    private String id; // maps uuid
    private AuthUserResponse user;
    private String mediaUrl;
    private String caption;
    private String expiresAt;
    private String createdAt;
    private boolean viewedByMe;
    private long viewCount; // total distinct viewers (surfaced to the owner's UI)
    private boolean owner;  // true when the current user owns this story
    private boolean expired; // true when past expiresAt (archive stories)
    private String audience; // "EVERYONE" or "FRIENDS" (followers & following)
    private AudioTrackDto audio; // non-null only when a soundtrack is attached
    private String kind; // "VISUAL" (default) or "VOICE" (feature #21)
}
