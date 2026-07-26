package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * A feedback entry for the admin dashboard: the rating/reason/comment, where it
 * came from, its triage state, and the author at a glance.
 */
@Data
@Builder
public class AdminFeedbackView {
    private String id;          // feedback uuid
    private int rating;         // 0–5
    private String reason;
    private String comment;
    private String type;        // LOGOUT / ACCOUNT_DELETION / LEAVE_GROUP / LEAVE_ROOM / MANUAL / OTHER
    private String contextRef;  // e.g. the group/room name (nullable)
    private String platform;    // client hint (nullable)
    private String status;      // NEW / REVIEWED / ARCHIVED
    private String createdAt;

    private Author author;

    @Data
    @Builder
    public static class Author {
        private String id;      // user uuid
        private String username;
        private String name;
        private String avatar;
        private String email;
        private String country;
        private boolean verified;
        private boolean guest;
    }
}
