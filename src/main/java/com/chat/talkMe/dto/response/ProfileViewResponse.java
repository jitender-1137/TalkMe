package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single entry in the "who viewed my profile" list. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileViewResponse {
    /** The viewer (anonymous identity is never used here — these are identified users). */
    private AuthUserResponse viewer;
    private String lastViewedAt;
    private int viewCount;
    /** PROFILE | PROFILE_IMAGE — what they last opened. */
    private String viewType;
    private boolean seen;
}
