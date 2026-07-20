package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * A moderation report for the admin review portal: who reported whom, why, the
 * match-session context it came from, review state, and analysis signals.
 */
@Data
@Builder
public class AdminReportView {
    private String id;            // report uuid
    private String reason;
    private String details;
    private String status;        // PENDING / ACTION_TAKEN / DISMISSED
    private String actionTaken;   // NONE / REVIEWED / WARNED / BANNED_REPORTED
    private String reviewedBy;
    private String reviewedAt;
    private String resolutionNote;
    private String createdAt;

    private Party reporter;
    private Party reported;
    private Session session;      // the "area" the report came from (may be null)

    // Analysis signals
    private long reportsAgainstReported; // total reports filed against the reported user
    private long reportsByReporter;      // total reports this reporter has filed
    private long duplicateCount;         // times THIS reporter reported THIS user (>1 = duplicate)

    // ── Deep review context (populated only on the single-report fetch) ────────
    private ReportedSummary reportedSummary;      // reported user's account at a glance
    private java.util.List<HistoryItem> history;  // all reports against the reported user
    private String relatedChatId;                 // persisted conversation between the two (evidence), nullable

    @Data
    @Builder
    public static class ReportedSummary {
        private String joined;        // account created-at
        private boolean verified;
        private boolean guest;
        private boolean banned;
        private long messageCount;
        private long chatCount;
    }

    @Data
    @Builder
    public static class HistoryItem {
        private String id;
        private String reason;
        private String reporterUsername;
        private String status;
        private String createdAt;
    }

    @Data
    @Builder
    public static class Party {
        private String id;        // uuid
        private String username;
        private String name;
        private String avatar;
        private String country;
        private boolean banned;
    }

    @Data
    @Builder
    public static class Session {
        private String id;        // uuid (nullable)
        private String hostUsername;
        private String peerUsername;
        private boolean active;
        private String endedAt;
    }
}
