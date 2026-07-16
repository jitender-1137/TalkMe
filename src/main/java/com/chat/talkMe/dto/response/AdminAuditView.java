package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/** One admin audit-trail entry for the dashboard. */
@Data
@Builder
public class AdminAuditView {
    private String id;
    private String adminUsername;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String createdAt;
}
