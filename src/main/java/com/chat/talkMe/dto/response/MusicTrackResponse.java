package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A searchable music track (from the free iTunes Search preview catalog). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MusicTrackResponse {
    private String id;
    private String title;
    private String artist;
    private String artworkUrl;
    private String previewUrl; // ~30s preview clip (playable cross-origin)
    private Integer durationSec;
}
