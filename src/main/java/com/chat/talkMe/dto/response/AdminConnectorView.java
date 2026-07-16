package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

/** A user node in the friends-hierarchy report: identity + their friend count. */
@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class AdminConnectorView {
    private String id;
    private String username;
    private String name;
    private String avatar;
    private String country;
    private long friendCount;
}
