package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Everything-in-one analytics payload for the SuperAdmin Reports page. */
@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class AdminAnalyticsResponse {
    // Headline totals
    private long totalUsers;
    private long verifiedUsers;
    private long guestUsers;
    private long bannedUsers;
    private long onlineNow;
    private long lobbyNow;          // live snapshot (ephemeral — no history)
    private long totalChats;
    private long totalMessages;
    private long totalAttachments;
    private long totalAttachmentBytes;
    private long totalPosts;
    private long totalStories;
    private long totalProfileViews;
    private long totalReports;       // moderation / match reports
    private long totalFollows;

    // Active-user counts (by last-seen recency, snapshot)
    private long activeLast1h;
    private long activeLast24h;
    private long activeLast7d;

    // Breakdowns (for pie/bar + tables)
    private List<LabelCount> messagesByType;   // TEXT / IMAGE / VIDEO / …
    private List<LabelCount> chatsByType;       // PRIVATE / GROUP / CHANNEL / ROOM / STRANGER
    private List<LabelCount> usersByGender;
    private List<LabelCount> usersByCountry;    // top 12
    private List<LabelCount> usersByStatus;     // Online / Idle / Offline (live snapshot)

    // Full list of accounts pending permanent purge (soft-deleted, in grace window).
    private List<AdminUserView> pendingDeletion;

    // ── Friends hierarchy / social graph ──────────────────────────────────────
    private long friendLinks;                       // directed Friend rows (≈ 2× friendships)
    private long friendships;                       // undirected (friendLinks / 2)
    private List<LabelCount> friendRequestsByStatus; // PENDING / ACCEPTED / REJECTED / BLOCKED
    private List<LabelCount> friendCountDistribution; // 0 / 1-5 / 6-10 / 11-25 / 26-50 / 50+
    private List<AdminConnectorView> topConnectors;  // most-connected users (roots of the tree)

    // Time series over the selected range. Granularity is "hour" or "day"; each
    // point's `date` is an ISO-8601 bucket-start instant so the client can format.
    private String range;                // echo of the requested range key (e.g. "24h")
    private String timeseriesGranularity; // "hour" | "day"
    private List<AdminTimeseriesPoint> signupsSeries;
    private List<AdminTimeseriesPoint> messagesSeries;
}
