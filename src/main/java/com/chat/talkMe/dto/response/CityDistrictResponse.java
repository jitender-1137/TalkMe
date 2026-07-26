package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single Virtual Night City district card (feature #25). Pure discovery/ambience
 * copy from {@link com.chat.talkMe.enums.CityLocation} plus two live counters:
 * {@code liveCount} = people currently present in the district's Redis presence set
 * (best-effort, 0 when Redis is unavailable) and {@code roomCount} = curated ROOM
 * chats seeded into the district.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CityDistrictResponse {
    private String slug;
    private String label;
    private String emoji;
    private String tagline;
    private int liveCount;
    private int roomCount;
}
