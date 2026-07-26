package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Detail view of one Virtual Night City district (feature #25): the district card,
 * its curated ROOM chats, and the live roster (usernames currently present in the
 * district's Redis presence set, pruned to those actually online). Both lists are
 * best-effort and may be empty when Redis is unavailable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CityDistrictDetailResponse {
    private CityDistrictResponse district;
    private List<ChatResponse> rooms;
    private List<String> onlineUsernames;
}
