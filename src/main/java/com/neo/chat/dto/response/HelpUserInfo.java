package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public identity card for a Community Help participant (asker or answerer). This feed is
 * <strong>not anonymous</strong>, so surfacing name/username/avatar is intentional. {@code presence}
 * is a coarse ONLINE / AWAY / OFFLINE label (Invisible-masked at source).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpUserInfo {
    private String userUuid;
    private String name;
    private String username;
    private String avatar;
    private String city;
    private String presence;
}
