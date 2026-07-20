package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "post_media")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostMedia extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(name = "media_url", nullable = false, length = 512)
    private String mediaUrl;

    @Column(name = "media_type", nullable = false, length = 30)
    private String mediaType; // IMAGE, VIDEO

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private int orderIndex = 0;

    // ── Video edit metadata (all null/false for images and un-edited videos) ──
    // Playback-time instructions applied by the client player — no re-encoding is
    // ever done server-side, so posting an edited video costs nothing extra.
    @Column(name = "trim_start_sec")
    private Double trimStartSec;

    @Column(name = "trim_end_sec")
    private Double trimEndSec;

    @Column(name = "muted")
    private Boolean muted;

    /** URL of a captured frame used as the video's cover/poster. */
    @Column(name = "cover_image_url", length = 512)
    private String coverImageUrl;

    /** Id of a CSS filter preset (see UI video-filters); applied at playback. */
    @Column(name = "filter_name", length = 60)
    private String filterName;
}
