package com.neo.chat.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * One username-change entry for the SuperAdmin dashboard.
 */
@Data
@Builder
public class UsernameHistoryView {
    private String id;
    private String oldUsername;
    private String newUsername;
    private String changedBy;
    private String changedByType; // SELF | ADMIN
    private String changedAt;
}
