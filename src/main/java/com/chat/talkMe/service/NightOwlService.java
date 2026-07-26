package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NightOwlDashboardResponse;
import com.chat.talkMe.dto.response.TrendingRoomCard;

import java.util.List;

/** Aggregates the live Night Owl Lobby dashboard (feature #2) from presence + recent joins. */
public interface NightOwlService {
    NightOwlDashboardResponse getDashboard(User currentUser);

    /** Trending/curated interest rooms for the Night Owl rail (feature #23). */
    List<TrendingRoomCard> trendingRooms(int limit);
}
