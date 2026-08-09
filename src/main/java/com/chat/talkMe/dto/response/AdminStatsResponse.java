package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Platform overview metrics for the SuperAdmin dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminStatsResponse {
    private long totalUsers;      // all accounts (active + soft-deleted)
    private long activeUsers;     // not soft-deleted
    private long deletedUsers;    // soft-deleted (is_deleted = true)
    private long verifiedUsers;
    private long guestUsers;
    private long newUsersLast7d;
    private long newUsersLast24h;
    private long onlineNow;
    private long totalChats;
    private long totalMessages;
}
