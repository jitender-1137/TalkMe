package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.NightOwlDashboardResponse;
import com.neo.chat.dto.response.TrendingRoomCard;

import java.util.List;

/**
 * Aggregates the live Night Owl Lobby dashboard (feature #2) from presence + recent joins.
 */
public interface NightOwlService {
    NightOwlDashboardResponse getDashboard(User currentUser);

    /**
     * Trending/curated interest rooms for the Night Owl rail (feature #23).
     */
    List<TrendingRoomCard> trendingRooms(int limit);
}
