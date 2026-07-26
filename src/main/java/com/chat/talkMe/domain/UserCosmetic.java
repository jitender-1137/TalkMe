package com.chat.talkMe.domain;

import com.chat.talkMe.enums.CosmeticType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

/**
 * A cosmetic a user has unlocked (owns), and whether it is currently equipped (Phase 4
 * gamification surface). At most one equipped row per {@link #slot} per user is enforced in
 * {@link com.chat.talkMe.service.CosmeticService#equip}. Ownership/equip state is decoration
 * only — never an authorization or limit signal.
 */
@Entity
@Table(name = "user_cosmetics",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_cosmetic_user_code", columnNames = {"user_id", "cosmetic_code"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCosmetic extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** References {@link UnlockableCosmetic#getCode()}. */
    @Column(name = "cosmetic_code", nullable = false, length = 80)
    private String cosmeticCode;

    @Column(name = "equipped", nullable = false)
    @ColumnDefault("false")
    private boolean equipped;

    /** Equip slot, mirrors the catalog cosmetic's {@link CosmeticType}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, length = 30)
    private CosmeticType slot;
}
