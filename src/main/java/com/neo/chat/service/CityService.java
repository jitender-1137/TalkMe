package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ChatResponse;
import com.neo.chat.dto.response.CityDistrictDetailResponse;
import com.neo.chat.dto.response.CityDistrictResponse;

import java.util.List;

/**
 * Virtual Night City (feature #25). A curated, read-mostly map of themed districts
 * ({@link com.neo.chat.enums.CityLocation}) layered over the ROOM chat model.
 * Each district exposes its seeded curated rooms and a live presence set (usernames
 * keyed on the district slug), broadcast on {@code /topic/city/{slug}}.
 *
 * <p>Presence is best-effort (Redis, fail-open): a Redis outage degrades live
 * counts/rosters to empty but never breaks a request.
 */
public interface CityService {

    /**
     * All districts as cards, each with live presence count + curated room count.
     */
    List<CityDistrictResponse> listDistricts();

    /**
     * One district's detail: card + curated rooms + live roster.
     */
    CityDistrictDetailResponse getDistrict(String slug, User user);

    /**
     * Add the user to the district's presence set, broadcast a join, return detail.
     */
    CityDistrictDetailResponse enterDistrict(User user, String slug);

    /**
     * Remove the user from the district's presence set, broadcast a leave, return detail.
     */
    void leaveDistrict(User user, String slug);

    /**
     * Curated ROOM chats seeded into the district.
     */
    List<ChatResponse> getRooms(String slug, User user);
}
