package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin dashboard analytics for the {@code media_assets} upload-ownership ledger — the
 * per-upload record that lets the storage gallery attribute every file (including
 * anonymous stranger &amp; ephemeral lobby media) to its uploader. Powers the "Storage &amp;
 * Media" page and the media cards on the overview.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMediaOwnershipResponse {

    private long totalAssets;          // rows in the ledger
    private long totalBytes;
    private long attributedAssets;     // owner resolved
    private long unattributedAssets;   // owner null (defensive — should be ~0)
    private long uploaderCount;        // distinct owners

    private long strangerAssets;       // uploaded in an anonymous stranger chat
    private long strangerBytes;
    private long lobbyAssets;          // ephemeral lobby DM media
    private long conversationAssets;   // persisted 1:1 / group media

    private List<Bucket> byContext;    // split by where it was uploaded
    private List<Bucket> byType;       // split by media type (image/video/…)

    private List<UploaderStat> topUploaders;
    private List<RecentUpload> recent; // newest uploads feed

    // Uploads-over-time series (same range/bucketing engine as the other analytics).
    private String range;
    private String granularity;        // "hour" | "day"
    private List<AdminTimeseriesPoint> uploadsSeries;

    /** A labelled count + byte total (context or media-type breakdown row). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        private String label;
        private long count;
        private long bytes;
    }

    /** A user ranked by how much media they've uploaded. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploaderStat {
        private String id;         // user uuid
        private String username;
        private String name;
        private String avatar;
        private long count;
        private long bytes;
        private long strangerCount; // how many were anonymous stranger uploads
    }

    /** One recent upload with its owner + context, for the activity feed. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentUpload {
        private String key;
        private String reference;
        private String url;         // serve URL
        private String kind;        // image / video / audio / file
        private String context;     // STRANGER / LOBBY / CONVERSATION / …
        private String uploadType;
        private String contentType;
        private long fileSize;
        private boolean strangerMode;
        private String uploadedAt;

        private String ownerId;     // user uuid (nullable)
        private String ownerUsername;
        private String ownerName;
        private String ownerAvatar;
    }
}
