package com.neo.chat.service.impl;

import com.neo.chat.domain.UnlockableCosmetic;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserCosmetic;
import com.neo.chat.domain.UserReputation;
import com.neo.chat.dto.response.CosmeticResponse;
import com.neo.chat.enums.CosmeticType;
import com.neo.chat.enums.StarRank;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.UnlockableCosmeticRepository;
import com.neo.chat.repository.UserCosmeticRepository;
import com.neo.chat.repository.UserReputationRepository;
import com.neo.chat.service.CosmeticService;
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

    /**
     * Returns the full (non-retired) cosmetic catalog, each mapped to the caller's owned/locked/
     * equipped state derived from their reputation snapshot. Sorted by unlock threshold then code.
     * Read-only.
     *
     * @param user the requesting user
     * @return catalog entries with per-user state
     */
    @Override
    @Transactional(readOnly = true)
    public List<CosmeticResponse> catalog(User user) {
        UserReputation rep = reputationRepo.findByUser(user).orElse(null);
        Map<String, UserCosmetic> owned = ownedByCode(user);
        Set<String> ownedBadgeCodes = ownedBadgeCodes();

        return catalogRepo.findAll().stream()
                .filter(c -> !c.isDeleted())
                .sorted(Comparator.comparing(UnlockableCosmetic::getUnlockThreshold)
                        .thenComparing(UnlockableCosmetic::getCode))
                .map(c -> toResponse(c, rep, owned, ownedBadgeCodes))
                .toList();
    }

    /**
     * Returns only the cosmetics the user owns — those whose unlock condition is currently
     * satisfied or that have an explicit owned row — with their equipped state. Read-only.
     *
     * @param user the requesting user
     * @return the user's owned cosmetics
     */
    @Override
    @Transactional(readOnly = true)
    public List<CosmeticResponse> myCosmetics(User user) {
        UserReputation rep = reputationRepo.findByUser(user).orElse(null);
        Map<String, UserCosmetic> owned = ownedByCode(user);
        Set<String> ownedBadgeCodes = ownedBadgeCodes();

        return catalogRepo.findAll().stream()
                .filter(c -> !c.isDeleted())
                .filter(c -> isUnlocked(c, rep, ownedBadgeCodes) || owned.containsKey(c.getCode()))
                .sorted(Comparator.comparing(UnlockableCosmetic::getUnlockThreshold)
                        .thenComparing(UnlockableCosmetic::getCode))
                .map(c -> toResponse(c, rep, owned, ownedBadgeCodes))
                .toList();
    }

    // ---- write paths ---------------------------------------------------------------

    /**
     * Equips a cosmetic the user owns, unequipping any other cosmetic in the same slot and
     * creating an owned row on first equip. Runs in the class-level write transaction.
     *
     * @param user the user
     * @param code the cosmetic code
     * @return the user's refreshed owned cosmetics
     * @throws com.neo.chat.exception.BadRequestException (TM_930) blank code, or (TM_932)
     *                                                       the cosmetic is not yet unlocked
     * @throws com.neo.chat.exception.NotFoundException   (TM_931) unknown or retired cosmetic
     */
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
        boolean unlocked = isUnlocked(cosmetic, rep, ownedBadgeCodes());
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

    /**
     * Unequips whatever cosmetic the user has equipped in the given slot. Runs in the
     * class-level write transaction.
     *
     * @param user the user
     * @param slot the cosmetic slot to clear
     * @return the user's refreshed owned cosmetics
     * @throws com.neo.chat.exception.BadRequestException (TM_933) if the slot is null
     */
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

    /**
     * The user's non-deleted explicit cosmetic rows, keyed by cosmetic code.
     *
     * @param user the user
     * @return map of code → owned cosmetic
     */
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
    private Set<String> ownedBadgeCodes() {
        return new HashSet<>();
    }

    /**
     * @param c               the cosmetic
     * @param rep             the user's reputation snapshot (null → level 1 / no prestige / bronze)
     * @param ownedBadgeCodes badge codes owned by the user (for BADGE unlocks)
     * @return true when the user meets the cosmetic's unlock condition (LEVEL/STAR/PRESTIGE/
     * BADGE); SEASONAL is never auto-unlocked
     */
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

    /**
     * Maps a catalog cosmetic to a response DTO, computing owned (explicit row or unlocked),
     * equipped and locked flags for the user.
     *
     * @param c               the cosmetic
     * @param rep             the user's reputation snapshot (nullable)
     * @param owned           the user's explicit owned rows by code
     * @param ownedBadgeCodes badge codes owned by the user
     * @return the response DTO
     */
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
