package com.chat.talkMe.dto.request;

import lombok.Data;

/**
 * Client filters for starting a preference-aware match (features #1/#3/#4/#5). All
 * fields optional — an empty request is the legacy blind quick-match. mood/energy, when
 * present, also update the user's current mood/energy.
 */
@Data
public class MatchStartRequest {
    private String genderPref;          // ANY | MALE | FEMALE | NONBINARY | COUPLE
    private Integer ageMin;
    private Integer ageMax;
    private String country;             // required country filter
    private String language;            // required language (enum name)
    private Boolean verifiedOnly;
    private Boolean moodCompatibleOnly;
    private String mood;                // enum name — also updates the user's mood
    private String energy;              // ConversationEnergy enum name
    private String mode;                // MatchMode: QUICK | FLIRT | MASK | COFFEE | CHEMISTRY
    private Integer durationMin;        // for COFFEE/CHEMISTRY (5/10/15)
}
