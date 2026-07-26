package com.chat.talkMe.dto.response;

import com.chat.talkMe.enums.CosmeticRarity;
import com.chat.talkMe.enums.CosmeticType;
import com.chat.talkMe.enums.CosmeticUnlockType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Catalog/inventory view of a cosmetic for the caller (Phase 4 gamification surface). Purely
 * cosmetic display data — carries no reputation weights, formulas or authorization signal.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CosmeticResponse {

    private String code;
    private CosmeticType type;
    private String name;
    private CosmeticRarity rarity;
    private CosmeticUnlockType unlockType;
    private int unlockThreshold;
    private String assetRef;

    /** True if the caller owns this cosmetic. */
    private boolean owned;

    /** True if the caller has this cosmetic equipped. */
    private boolean equipped;

    /** True if the caller has NOT yet met the unlock requirement. */
    private boolean locked;
}
