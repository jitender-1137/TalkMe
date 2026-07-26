package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A room tile on the Night Owl "Trending / Night" rail (feature #23). Lean, discovery-only:
 * enough to render a card and open the room. {@code curated} marks an editorially-featured room.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendingRoomCard {
    private String id;          // chat uuid
    private String name;
    private String description;
    private String avatar;
    private String category;
    private List<String> tags;  // Interest enum names
    private int memberCount;
    private boolean curated;
    private String cityLocation; // slug of the district this room sits in, or null
}
