package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Privacy-safe representation of a matched stranger for anonymous matchmaking.
 *
 * <p>Strangers must remain anonymous to each other: we never expose the
 * partner's identity (username, real name, avatar, age, gender, location,
 * mobile number, interests) — nor even their real user id, which could be used
 * to look up their full profile via the user APIs. Only a non-identifying guest
 * flag is shared, which the client uses for lightweight UI behaviour.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousPartnerResponse {
    private boolean isGuest;
}
