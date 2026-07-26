package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.UnlockableCosmetic;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserCosmetic;
import com.chat.talkMe.domain.UserReputation;
import com.chat.talkMe.dto.response.CosmeticResponse;
import com.chat.talkMe.enums.CosmeticType;
import com.chat.talkMe.enums.StarRank;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.UnlockableCosmeticRepository;
import com.chat.talkMe.repository.UserCosmeticRepository;
import com.chat.talkMe.repository.UserReputationRepository;
import com.chat.talkMe.service.CosmeticService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cosmetic rewards implementation (Phase 4 gamification surface).
 *
 * <p>Unlock evaluation reads the caller's {@link UserReputation} snapshot (auto-creating a
 * BRONZE / level-1 baseline is NOT this service's job — a missing snapshot is treated as
 * level 1 / no prestige, i.e. everything above the floor stays locked). Owning a cosmetic is
 * lazy: a user "auto-owns" any cosmetic whose unlock condition they currently satisfy, and an
 * explicit {@link UserCosmetic} row is created on first equip. Nothing here gates features.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CosmeticServiceImpl implements CosmeticService {

    private final UnlockableCosmeticRepository catalogRepo;
    private final UserCosmeticRepository userCosmeticRepo;
    private final UserReputationRepository reputationRepo;

    // ---- read paths ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<CosmeticResponse> catalog(User user) {
        UserReputation rep = reputationRepo.findByUser(user).orElse(null);
        Map<String, UserCosmetic> owned = ownedByCode(user);
        Set<String> ownedBadgeCodes = ownedBadgeCodes(user);

        return catalogRepo.findAll().stream()
                .filter(c -> !c.isDeleted())
                .sorted(Comparator.comparing(UnlockableCosmetic::getUnlockThreshold)
                        .thenComparing(UnlockableCosmetic::getCode))
                .map(c -> toResponse(c, rep, owned, ownedBadgeCodes))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CosmeticResponse> myCosmetics(User user) {
        UserReputation rep = reputationRepo.findByUser(user).orElse(null);
        Map<String, UserCosmetic> owned = ownedByCode(user);
        Set<String> ownedBadgeCodes = ownedBadgeCodes(user);

        return catalogRepo.findAll().stream()
                .filter(c -> !c.isDeleted())
                .filter(c -> isUnlocked(c, rep, ownedBadgeCodes) || owned.containsKey(c.getCode()))
                .sorted(Comparator.comparing(UnlockableCosmetic::getUnlockThreshold)
                        .thenComparing(UnlockableCosmetic::getCode))
                .map(c -> toResponse(c, rep, owned, ownedBadgeCodes))
                .toList();
    }

    // ---- write paths ---------------------------------------------------------------

    @Override
    public List<CosmeticResponse> equip(User user, String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Cosmetic code is required", "TM_930");
        }
        UnlockableCosmetic cosmetic = catalogRepo.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Unknown cosmetic: " + code, "TM_931"));
        // A retired (soft-deleted) cosmetic is hidden from the read paths, so it must not be
        // equippable either — otherwise a stale/guessed code could re-equip a removed item.
        if (cosmetic.isDeleted()) {
            throw new NotFoundException("Unknown cosmetic: " + code, "TM_931");
        }

        UserReputation rep = reputationRepo.findByUser(user).orElse(null);
        UserCosmetic existing = userCosmeticRepo.findByUserAndCosmeticCode(user, code).orElse(null);

        // Ownership: an explicit owned row, OR the unlock condition is currently satisfied.
        boolean unlocked = isUnlocked(cosmetic, rep, ownedBadgeCodes(user));
        if (existing == null && !unlocked) {
            throw new BadRequestException("You have not unlocked this cosmetic yet", "TM_932");
        }

        // Unequip everything else in the same slot.
        for (UserCosmetic uc : userCosmeticRepo.findByUserAndEquippedTrue(user)) {
            if (uc.getSlot() == cosmetic.getType() && !uc.getCosmeticCode().equals(code)) {
                uc.setEquipped(false);
                userCosmeticRepo.save(uc);
            }
        }

        if (existing == null) {
            existing = UserCosmetic.builder()
                    .user(user)
                    .cosmeticCode(code)
                    .slot(cosmetic.getType())
                    .equipped(true)
                    .build();
        } else {
            existing.setSlot(cosmetic.getType());
            existing.setEquipped(true);
        }
        userCosmeticRepo.save(existing);

        return myCosmetics(user);
    }

    @Override
    public List<CosmeticResponse> unequip(User user, CosmeticType slot) {
        if (slot == null) {
            throw new BadRequestException("Slot is required", "TM_933");
        }
        for (UserCosmetic uc : userCosmeticRepo.findByUserAndEquippedTrue(user)) {
            if (uc.getSlot() == slot) {
                uc.setEquipped(false);
                userCosmeticRepo.save(uc);
            }
        }
        return myCosmetics(user);
    }

    // ---- helpers -------------------------------------------------------------------

    private Map<String, UserCosmetic> ownedByCode(User user) {
        Map<String, UserCosmetic> map = new HashMap<>();
        for (UserCosmetic uc : userCosmeticRepo.findByUser(user)) {
            if (!uc.isDeleted()) {
                map.put(uc.getCosmeticCode(), uc);
            }
        }
        return map;
    }

    /**
     * Codes of badges the user owns, for {@code BADGE} unlocks. There is no badge inventory
     * yet, so this is always empty — BADGE cosmetics stay locked until one is introduced.
     */
    private Set<String> ownedBadgeCodes(User user) {
        return new HashSet<>();
    }

    /** Whether the user currently satisfies a cosmetic's unlock condition. */
    private boolean isUnlocked(UnlockableCosmetic c, UserReputation rep, Set<String> ownedBadgeCodes) {
        int level = rep != null ? rep.getLevel() : 1;
        int prestige = rep != null ? rep.getPrestigeCount() : 0;
        StarRank star = rep != null && rep.getStarRank() != null ? rep.getStarRank() : StarRank.BRONZE_STAR;

        return switch (c.getUnlockType()) {
            case LEVEL -> level >= c.getUnlockThreshold();
            // Threshold is the required rank's minLevel (intrinsic), NOT its enum ordinal —
            // ordinals silently shift if StarRank is ever reordered/extended.
            case STAR -> star.getMinLevel() >= c.getUnlockThreshold();
            case PRESTIGE -> prestige >= c.getUnlockThreshold();
            case BADGE -> ownedBadgeCodes.contains(c.getCode());
            // Seasonal & event cosmetics are only granted explicitly (an owned row); never auto.
            case SEASONAL -> false;
        };
    }

    private CosmeticResponse toResponse(UnlockableCosmetic c, UserReputation rep,
                                        Map<String, UserCosmetic> owned, Set<String> ownedBadgeCodes) {
        UserCosmetic uc = owned.get(c.getCode());
        boolean unlocked = isUnlocked(c, rep, ownedBadgeCodes);
        boolean isOwned = uc != null || unlocked;
        boolean equipped = uc != null && uc.isEquipped();
        return CosmeticResponse.builder()
                .code(c.getCode())
                .type(c.getType())
                .name(c.getName())
                .rarity(c.getRarity())
                .unlockType(c.getUnlockType())
                .unlockThreshold(c.getUnlockThreshold())
                .assetRef(c.getAssetRef())
                .owned(isOwned)
                .equipped(equipped)
                .locked(!isOwned)
                .build();
    }
}
