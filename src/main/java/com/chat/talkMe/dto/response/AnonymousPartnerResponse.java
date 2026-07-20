package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Privacy-safe representation of a matched stranger for anonymous matchmaking.
 *
 * <p>Strangers must remain anonymous to each other: we never expose the
 * partner's identity (username, real name, avatar, age, gender, mobile number,
 * interests) — nor even their real user id, which could be used to look up their
 * full profile via the user APIs.</p>
 *
 * <p>The one deliberate exception is <b>country</b>: a coarse, country-level flag
 * is shown in the match UI. This is a conscious product trade-off — no finer
 * location (city/region) is ever shared, and country alone can't be used to look
 * up the partner. Everything else stays stripped.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousPartnerResponse {
    private boolean isGuest;

    /** Country display name (e.g. "India"). Coarse location only — no city/region. */
    private String country;
}
