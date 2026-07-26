package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One viewer of a story, returned in the owner's "seen by" list.
 * Mirrors ProfileViewResponse (user + when).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryViewerResponse {
    private AuthUserResponse user;
    private String viewedAt; // ISO instant the viewer opened the story
}
