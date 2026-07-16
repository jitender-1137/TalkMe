package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/** Platform overview metrics for the SuperAdmin dashboard. */
@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class AdminStatsResponse {
    private long totalUsers;
    private long verifiedUsers;
    private long guestUsers;
    private long newUsersLast7d;
    private long newUsersLast24h;
    private long onlineNow;
    private long totalChats;
    private long totalMessages;
}
