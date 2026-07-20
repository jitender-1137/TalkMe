package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoryRequest {

    @NotBlank(message = "Story media URL is required")
    private String mediaUrl;

    private String caption;

    // Audience: "EVERYONE" (default) or "FRIENDS" (followers & following only).
    private String audience;

    // Optional soundtrack.
    private com.chat.talkMe.dto.response.AudioTrackDto audio;
}
