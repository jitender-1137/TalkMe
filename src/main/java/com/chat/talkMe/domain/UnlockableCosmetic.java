package com.chat.talkMe.domain;

import com.chat.talkMe.enums.CosmeticRarity;
import com.chat.talkMe.enums.CosmeticType;
import com.chat.talkMe.enums.CosmeticUnlockType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

/**
 * Catalog row describing a cosmetic reward that a user can unlock and equip (Phase 4
 * gamification surface). Seeded/upserted by {@link com.chat.talkMe.config.CosmeticCatalogSeeder}.
 * Everything here is decoration — a cosmetic must never gate a feature or a limit.
 */
@Entity
@Table(name = "unlockable_cosmetics",
        uniqueConstraints = @UniqueConstraint(name = "uk_unlockable_cosmetic_code", columnNames = {"code"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnlockableCosmetic extends BaseEntity {

    /** Stable machine code, unique. The join key used by . */
    @Column(name = "code", nullable = false, unique = true, length = 80)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private CosmeticType type;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rarity", nullable = false, length = 30)
    private CosmeticRarity rarity;

    @Enumerated(EnumType.STRING)
    @Column(name = "unlock_type", nullable = false, length = 30)
    private CosmeticUnlockType unlockType;

    /** Interpreted per {@link CosmeticUnlockType} (level / star ordinal / prestige count). */
    @Column(name = "unlock_threshold", nullable = false)
    @ColumnDefault("0")
    private int unlockThreshold;

    /** Opaque asset reference (CSS class, gradient token, sprite id, etc.). */
    @Column(name = "asset_ref")
    private String assetRef;

    @Column(name = "seasonal", nullable = false)
    @ColumnDefault("false")
    private boolean seasonal;
}
