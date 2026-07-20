package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Counts for the profile-views badge. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileViewCountResponse {
    /** Distinct viewers all-time. */
    private long total;
    /** Distinct viewers not yet seen in the list (drives the badge). */
    private long unseen;
}
