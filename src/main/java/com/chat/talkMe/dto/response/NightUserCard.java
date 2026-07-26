package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Lightweight user tile for the Night Owl Lobby (feature #2). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NightUserCard {
    private String id;
    private String name;
    private String username;
    private String avatar;
    private String mood;
    private String country;
    private String presence;
}
