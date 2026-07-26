package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The Night Owl Lobby live dashboard (feature #2): who's around right now, who just
 * joined, and what's trending among night owls.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NightOwlDashboardResponse {
    private int nightUsersOnline;
    private List<NightUserCard> onlineNow;
    private List<NightUserCard> recentlyJoined;
    private List<String> trendingTopics;
}
