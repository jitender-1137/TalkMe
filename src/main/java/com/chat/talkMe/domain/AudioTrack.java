package com.chat.talkMe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

/**
 * Optional soundtrack attached to a {@link Post} or {@link Story}. Embedded, so
 * the columns live directly on the owning table. All columns are nullable — a
 * post/story without music simply leaves them null.
 */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioTrack {

    @Column(name = "audio_url", length = 1024)
    private String audioUrl;

    @Column(name = "audio_title", length = 255)
    private String audioTitle;

    @Column(name = "audio_artist", length = 255)
    private String audioArtist;

    @Column(name = "audio_artwork_url", length = 1024)
    private String audioArtworkUrl;

    // Offset (seconds) into the track where playback should start.
    @Column(name = "audio_start_sec")
    private Integer audioStartSec;

    // Length (seconds) of the clip to play from audioStartSec (e.g. 15 or 20).
    @Column(name = "audio_clip_sec")
    private Integer audioClipSeconds;

    public boolean isPresent() {
        return audioUrl != null && !audioUrl.isBlank();
    }
}
