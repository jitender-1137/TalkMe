package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A user node in the friends-hierarchy report: identity + their friend count. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminConnectorView {
    private String id;
    private String username;
    private String name;
    private String avatar;
    private String country;
    private long friendCount;
}
