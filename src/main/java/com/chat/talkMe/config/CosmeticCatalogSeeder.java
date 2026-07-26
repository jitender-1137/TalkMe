package com.chat.talkMe.config;

import com.chat.talkMe.domain.UnlockableCosmetic;
import com.chat.talkMe.enums.CosmeticRarity;
import com.chat.talkMe.enums.CosmeticType;
import com.chat.talkMe.enums.CosmeticUnlockType;
import com.chat.talkMe.repository.UnlockableCosmeticRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds/upserts the cosmetic catalog on boot (Phase 4 gamification surface). Idempotent:
 * matched by {@code code}, existing rows are updated in place, new ones inserted. Follows the
 * {@link SuperAdminSeeder} ApplicationRunner pattern. All entries are decoration only.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class CosmeticCatalogSeeder implements ApplicationRunner {

    private final UnlockableCosmeticRepository catalogRepo;

    /** {code, type, name, rarity, unlockType, threshold, assetRef, seasonal}. */
    private static final List<Object[]> SEED = List.of(
            // --- LEVEL unlocks (threshold = min level) ---
            new Object[]{"frame_starter", CosmeticType.FRAME, "Starter Frame", CosmeticRarity.COMMON, CosmeticUnlockType.LEVEL, 1, "frame/starter", false},
            new Object[]{"border_dawn", CosmeticType.BORDER, "Dawn Border", CosmeticRarity.COMMON, CosmeticUnlockType.LEVEL, 5, "border/dawn", false},
            new Object[]{"bubble_aqua", CosmeticType.CHAT_BUBBLE, "Aqua Bubble", CosmeticRarity.RARE, CosmeticUnlockType.LEVEL, 10, "bubble/aqua", false},
            new Object[]{"theme_midnight", CosmeticType.PROFILE_THEME, "Midnight Theme", CosmeticRarity.RARE, CosmeticUnlockType.LEVEL, 20, "theme/midnight", false},
            new Object[]{"glow_ember", CosmeticType.NAME_GLOW, "Ember Glow", CosmeticRarity.EPIC, CosmeticUnlockType.LEVEL, 30, "glow/ember", false},
            new Object[]{"emoji_party", CosmeticType.EMOJI_PACK, "Party Emoji Pack", CosmeticRarity.RARE, CosmeticUnlockType.LEVEL, 15, "emoji/party", false},

            // --- STAR unlocks (threshold = required StarRank's minLevel: GOLD=20, DIAMOND=40) ---
            new Object[]{"frame_gold_star", CosmeticType.FRAME, "Gold Star Frame", CosmeticRarity.EPIC, CosmeticUnlockType.STAR, 20, "frame/gold-star", false},
            new Object[]{"glow_diamond", CosmeticType.NAME_GLOW, "Diamond Glow", CosmeticRarity.LEGENDARY, CosmeticUnlockType.STAR, 40, "glow/diamond", false},

            // --- PRESTIGE unlocks (threshold = prestige count) ---
            new Object[]{"frame_prestige_i", CosmeticType.FRAME, "Prestige I Frame", CosmeticRarity.LEGENDARY, CosmeticUnlockType.PRESTIGE, 1, "frame/prestige-1", false},
            new Object[]{"theme_cosmic", CosmeticType.PROFILE_THEME, "Cosmic Theme", CosmeticRarity.LEGENDARY, CosmeticUnlockType.PRESTIGE, 2, "theme/cosmic", false},

            // --- BADGE unlock (locked until a badge inventory exists) ---
            new Object[]{"badge_founder", CosmeticType.BADGE, "Founder Badge", CosmeticRarity.EPIC, CosmeticUnlockType.BADGE, 0, "badge/founder", false},

            // --- SEASONAL (event/admin-granted only) ---
            new Object[]{"border_winter_2026", CosmeticType.BORDER, "Winter 2026 Border", CosmeticRarity.EPIC, CosmeticUnlockType.SEASONAL, 0, "border/winter-2026", true}
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0, updated = 0;
        for (Object[] row : SEED) {
            String code = (String) row[0];
            UnlockableCosmetic existing = catalogRepo.findByCode(code).orElse(null);
            if (existing == null) {
                catalogRepo.save(UnlockableCosmetic.builder()
                        .code(code)
                        .type((CosmeticType) row[1])
                        .name((String) row[2])
                        .rarity((CosmeticRarity) row[3])
                        .unlockType((CosmeticUnlockType) row[4])
                        .unlockThreshold((int) row[5])
                        .assetRef((String) row[6])
                        .seasonal((boolean) row[7])
                        .build());
                inserted++;
            } else {
                existing.setType((CosmeticType) row[1]);
                existing.setName((String) row[2]);
                existing.setRarity((CosmeticRarity) row[3]);
                existing.setUnlockType((CosmeticUnlockType) row[4]);
                existing.setUnlockThreshold((int) row[5]);
                existing.setAssetRef((String) row[6]);
                existing.setSeasonal((boolean) row[7]);
                catalogRepo.save(existing);
                updated++;
            }
        }
        log.info("[CosmeticCatalog] seeded cosmetics — {} inserted, {} updated", inserted, updated);
    }
}
