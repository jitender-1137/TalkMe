package com.chat.talkMe.dto.response;

import com.chat.talkMe.domain.AudioTrack;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Soundtrack payload — used both in create requests and in responses. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioTrackDto {
    private String audioUrl;
    private String audioTitle;
    private String audioArtist;
    private String audioArtworkUrl;
    private Integer audioStartSec;
    private Integer audioClipSeconds;

    /** Response mapping: returns null when no track is attached. */
    public static AudioTrackDto from(AudioTrack a) {
        if (a == null || !a.isPresent()) {
            return null;
        }
        return AudioTrackDto.builder()
                .audioUrl(a.getAudioUrl())
                .audioTitle(a.getAudioTitle())
                .audioArtist(a.getAudioArtist())
                .audioArtworkUrl(a.getAudioArtworkUrl())
                .audioStartSec(a.getAudioStartSec())
                .audioClipSeconds(a.getAudioClipSeconds())
                .build();
    }

    /** Request mapping: returns null when this payload carries no usable track. */
    public AudioTrack toEntity() {
        if (audioUrl == null || audioUrl.isBlank()) {
            return null;
        }
        return AudioTrack.builder()
                .audioUrl(audioUrl)
                .audioTitle(audioTitle)
                .audioArtist(audioArtist)
                .audioArtworkUrl(audioArtworkUrl)
                .audioStartSec(audioStartSec == null ? 0 : audioStartSec)
                .audioClipSeconds(audioClipSeconds == null ? 15 : audioClipSeconds)
                .build();
    }
}
