package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CosmeticResponse;
import com.chat.talkMe.enums.CosmeticType;

import java.util.List;

/**
 * Cosmetic rewards surface (Phase 4 gamification). Serves the unlockable catalog with
 * per-user owned/locked/equipped state and lets a user equip/unequip cosmetics they own.
 *
 * <p><b>Cosmetic-only invariant:</b> unlock thresholds are evaluated against a user's
 * {@link com.chat.talkMe.domain.UserReputation} (level / star rank / prestige), but nothing
 * here may ever be used to gate a feature, a limit, or any authorization decision. Cosmetics
 * are decoration.
 */
public interface CosmeticService {

    /** Full catalog with owned/locked/equipped flags resolved for the given user. */
    List<CosmeticResponse> catalog(User user);

    /** Only the cosmetics the user owns, with equipped flags. */
    List<CosmeticResponse> myCosmetics(User user);

    /**
     * Equip a cosmetic the user owns. Unequips any other cosmetic in the same slot first.
     * Throws if the user does not own the cosmetic.
     */
    List<CosmeticResponse> equip(User user, String code);

    /** Unequip whatever the user currently has equipped in the given slot (no-op if none). */
    List<CosmeticResponse> unequip(User user, CosmeticType slot);
}
