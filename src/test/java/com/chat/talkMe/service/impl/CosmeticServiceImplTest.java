package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.UnlockableCosmetic;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserCosmetic;
import com.chat.talkMe.domain.UserReputation;
import com.chat.talkMe.dto.response.CosmeticResponse;
import com.chat.talkMe.enums.CosmeticRarity;
import com.chat.talkMe.enums.CosmeticType;
import com.chat.talkMe.enums.CosmeticUnlockType;
import com.chat.talkMe.enums.StarRank;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.UnlockableCosmeticRepository;
import com.chat.talkMe.repository.UserCosmeticRepository;
import com.chat.talkMe.repository.UserReputationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link CosmeticServiceImpl} — the Phase-4 cosmetic reward surface.
 *
 * <p>Unlock evaluation reads the caller's {@link UserReputation} snapshot; a missing snapshot is
 * treated as level-1 / no-prestige (everything above the floor stays locked). Ownership is lazy:
 * a user "auto-owns" anything they currently unlock, and an explicit {@link UserCosmetic} row is
 * written on first equip. The tests cover the unlock matrix (LEVEL/STAR/PRESTIGE/BADGE/SEASONAL),
 * the read-path filtering/sorting, and the equip/unequip write paths including the same-slot
 * exclusivity invariant and the validation codes TM_930/931/932/933.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CosmeticServiceImpl (unit)")
class CosmeticServiceImplTest {

    @Mock
    private UnlockableCosmeticRepository catalogRepo;
    @Mock
    private UserCosmeticRepository userCosmeticRepo;
    @Mock
    private UserReputationRepository reputationRepo;

    private CosmeticServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new CosmeticServiceImpl(catalogRepo, userCosmeticRepo, reputationRepo);
        user = User.builder().username("alice").build();
        user.setId(1L);
    }

    private UnlockableCosmetic cosmetic(String code, CosmeticType type,
                                        CosmeticUnlockType unlockType, int threshold) {
        return UnlockableCosmetic.builder()
                .code(code)
                .type(type)
                .name(code + " name")
                .rarity(CosmeticRarity.COMMON)
                .unlockType(unlockType)
                .unlockThreshold(threshold)
                .assetRef("asset:" + code)
                .seasonal(false)
                .build();
    }

    private UserCosmetic ownedRow(String code, CosmeticType slot, boolean equipped) {
        return UserCosmetic.builder()
                .user(user)
                .cosmeticCode(code)
                .slot(slot)
                .equipped(equipped)
                .build();
    }

    private UserReputation reputation(int level, int prestige, StarRank star) {
        return UserReputation.builder()
                .level(level)
                .prestigeCount(prestige)
                .starRank(star)
                .build();
    }

    @Nested
    @DisplayName("catalog")
    class Catalog {

        @Test
        @DisplayName("no reputation snapshot → floor level 1; LEVEL-1 unlocked, higher locked, sorted by threshold")
        void nominalNoReputation() {
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());
            when(catalogRepo.findAll()).thenReturn(List.of(
                    cosmetic("lvl50", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 50),
                    cosmetic("lvl1", CosmeticType.BORDER, CosmeticUnlockType.LEVEL, 1)));

            List<CosmeticResponse> out = service.catalog(user);

            assertThat(out).extracting(CosmeticResponse::getCode).containsExactly("lvl1", "lvl50");
            CosmeticResponse lvl1 = out.get(0);
            assertThat(lvl1.isOwned()).isTrue();
            assertThat(lvl1.isLocked()).isFalse();
            assertThat(lvl1.isEquipped()).isFalse();
            CosmeticResponse lvl50 = out.get(1);
            assertThat(lvl50.isOwned()).isFalse();
            assertThat(lvl50.isLocked()).isTrue();
        }

        @Test
        @DisplayName("soft-deleted catalog rows are excluded")
        void excludesDeleted() {
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());
            UnlockableCosmetic retired = cosmetic("retired", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 1);
            retired.setDeleted(true);
            when(catalogRepo.findAll()).thenReturn(List.of(
                    retired,
                    cosmetic("live", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 1)));

            List<CosmeticResponse> out = service.catalog(user);

            assertThat(out).extracting(CosmeticResponse::getCode).containsExactly("live");
        }

        @Test
        @DisplayName("equal thresholds → tie-broken alphabetically by code")
        void sortsByCodeOnThresholdTie() {
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());
            when(catalogRepo.findAll()).thenReturn(List.of(
                    cosmetic("beta", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 5),
                    cosmetic("alpha", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 5)));

            List<CosmeticResponse> out = service.catalog(user);

            assertThat(out).extracting(CosmeticResponse::getCode).containsExactly("alpha", "beta");
        }

        /**
         * A GOLD_STAR / prestige-2 snapshot unlocks the STAR cosmetic (its rank's minLevel of 20 is
         * met) and the PRESTIGE-2 cosmetic, while BADGE (no badge inventory) and SEASONAL (not in an
         * active season) remain locked.
         */
        @Test
        @DisplayName("reputation snapshot drives STAR/PRESTIGE unlocks; BADGE + SEASONAL stay locked")
        void reputationBasedUnlocks() {
            when(reputationRepo.findByUser(user))
                    .thenReturn(Optional.of(reputation(25, 2, StarRank.GOLD_STAR)));
            when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());
            when(catalogRepo.findAll()).thenReturn(List.of(
                    cosmetic("star", CosmeticType.FRAME, CosmeticUnlockType.STAR, 20),      // GOLD minLevel 20 >= 20
                    cosmetic("prestige", CosmeticType.BORDER, CosmeticUnlockType.PRESTIGE, 2),
                    cosmetic("badge", CosmeticType.BADGE, CosmeticUnlockType.BADGE, 0),     // no badge inventory
                    cosmetic("seasonal", CosmeticType.EMOJI_PACK, CosmeticUnlockType.SEASONAL, 0)));

            List<CosmeticResponse> out = service.catalog(user);

            assertThat(out).filteredOn(c -> c.getCode().equals("star")).allMatch(c -> !c.isLocked());
            assertThat(out).filteredOn(c -> c.getCode().equals("prestige")).allMatch(c -> !c.isLocked());
            assertThat(out).filteredOn(c -> c.getCode().equals("badge")).allMatch(CosmeticResponse::isLocked);
            assertThat(out).filteredOn(c -> c.getCode().equals("seasonal")).allMatch(CosmeticResponse::isLocked);
        }

        @Test
        @DisplayName("owned-but-locked cosmetic → owned + equipped reflect the explicit row")
        void ownedRowOverridesLock() {
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUser(user))
                    .thenReturn(List.of(ownedRow("lvl50", CosmeticType.FRAME, true)));
            when(catalogRepo.findAll()).thenReturn(List.of(
                    cosmetic("lvl50", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 50)));

            List<CosmeticResponse> out = service.catalog(user);

            CosmeticResponse c = out.get(0);
            assertThat(c.isOwned()).isTrue();     // explicit row => owned even though locked by level
            assertThat(c.isEquipped()).isTrue();
            assertThat(c.isLocked()).isFalse();
        }

        @Test
        @DisplayName("empty catalog → empty list, no NPE")
        void emptyCatalog() {
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());
            when(catalogRepo.findAll()).thenReturn(List.of());

            assertThat(service.catalog(user)).isEmpty();
        }
    }

    @Nested
    @DisplayName("myCosmetics")
    class MyCosmetics {

        @Test
        @DisplayName("returns only cosmetics the user has unlocked OR explicitly owns")
        void onlyUnlockedOrOwned() {
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUser(user))
                    .thenReturn(List.of(ownedRow("lvl50owned", CosmeticType.FRAME, false)));
            when(catalogRepo.findAll()).thenReturn(List.of(
                    cosmetic("lvl1", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 1),        // unlocked
                    cosmetic("lvl50", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 50),      // locked, not owned
                    cosmetic("lvl50owned", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 50)));// locked, owned

            List<CosmeticResponse> out = service.myCosmetics(user);

            assertThat(out).extracting(CosmeticResponse::getCode)
                    .containsExactly("lvl1", "lvl50owned");
        }
    }

    @Nested
    @DisplayName("equip")
    class Equip {

        @Test
        @DisplayName("null code → BadRequestException TM_930, no catalog lookup")
        void nullCode() {
            assertThatThrownBy(() -> service.equip(user, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_930"));
            verify(catalogRepo, never()).findByCode(anyString());
        }

        @Test
        @DisplayName("blank code → BadRequestException TM_930")
        void blankCode() {
            assertThatThrownBy(() -> service.equip(user, "   "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_930"));
        }

        @Test
        @DisplayName("unknown code → NotFoundException TM_931")
        void unknownCode() {
            when(catalogRepo.findByCode("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.equip(user, "ghost"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_931"));
            verify(userCosmeticRepo, never()).save(any());
        }

        @Test
        @DisplayName("soft-deleted cosmetic → NotFoundException TM_931 (not equippable)")
        void deletedCosmetic() {
            UnlockableCosmetic retired = cosmetic("retired", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 1);
            retired.setDeleted(true);
            when(catalogRepo.findByCode("retired")).thenReturn(Optional.of(retired));

            assertThatThrownBy(() -> service.equip(user, "retired"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_931"));
            verify(userCosmeticRepo, never()).save(any());
        }

        @Test
        @DisplayName("not owned and unlock condition not met → BadRequestException TM_932")
        void notUnlocked() {
            when(catalogRepo.findByCode("lvl50"))
                    .thenReturn(Optional.of(cosmetic("lvl50", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 50)));
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty()); // level 1
            when(userCosmeticRepo.findByUserAndCosmeticCode(user, "lvl50")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.equip(user, "lvl50"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_932"));
            verify(userCosmeticRepo, never()).save(any());
        }

        @Test
        @DisplayName("unlocked, no existing row → creates an equipped row")
        void createsEquippedRowWhenUnlocked() {
            when(catalogRepo.findByCode("lvl1"))
                    .thenReturn(Optional.of(cosmetic("lvl1", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 1)));
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty()); // level 1 → unlocked
            when(userCosmeticRepo.findByUserAndCosmeticCode(user, "lvl1")).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUserAndEquippedTrue(user)).thenReturn(List.of());
            lenient().when(catalogRepo.findAll()).thenReturn(List.of());
            lenient().when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());

            service.equip(user, "lvl1");

            ArgumentCaptor<UserCosmetic> saved = ArgumentCaptor.forClass(UserCosmetic.class);
            verify(userCosmeticRepo).save(saved.capture());
            UserCosmetic row = saved.getValue();
            assertThat(row.getCosmeticCode()).isEqualTo("lvl1");
            assertThat(row.getSlot()).isEqualTo(CosmeticType.FRAME);
            assertThat(row.isEquipped()).isTrue();
            assertThat(row.getUser()).isSameAs(user);
        }

        @Test
        @DisplayName("existing owned row that is currently locked → still re-equippable (updates the row)")
        void reEquipsExistingEvenWhenLocked() {
            UnlockableCosmetic c = cosmetic("lvl50", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 50);
            UserCosmetic existing = ownedRow("lvl50", CosmeticType.BORDER, false);
            when(catalogRepo.findByCode("lvl50")).thenReturn(Optional.of(c));
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty()); // level 1 → not unlocked
            when(userCosmeticRepo.findByUserAndCosmeticCode(user, "lvl50")).thenReturn(Optional.of(existing));
            when(userCosmeticRepo.findByUserAndEquippedTrue(user)).thenReturn(List.of());
            lenient().when(catalogRepo.findAll()).thenReturn(List.of());
            lenient().when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());

            service.equip(user, "lvl50");

            assertThat(existing.getSlot()).isEqualTo(CosmeticType.FRAME); // slot re-synced to catalog type
            assertThat(existing.isEquipped()).isTrue();
            verify(userCosmeticRepo).save(existing);
        }

        /**
         * Same-slot exclusivity: equipping a FRAME clears any other equipped FRAME row (saved) but
         * leaves an equipped BORDER row in a different slot untouched.
         */
        @Test
        @DisplayName("equipping unequips other cosmetics in the SAME slot only")
        void unequipsSameSlotSiblings() {
            UnlockableCosmetic c = cosmetic("frameNew", CosmeticType.FRAME, CosmeticUnlockType.LEVEL, 1);
            UserCosmetic sameSlot = ownedRow("frameOld", CosmeticType.FRAME, true);
            UserCosmetic otherSlot = ownedRow("borderOld", CosmeticType.BORDER, true);
            when(catalogRepo.findByCode("frameNew")).thenReturn(Optional.of(c));
            when(reputationRepo.findByUser(user)).thenReturn(Optional.empty()); // level 1 → unlocked
            when(userCosmeticRepo.findByUserAndCosmeticCode(user, "frameNew")).thenReturn(Optional.empty());
            when(userCosmeticRepo.findByUserAndEquippedTrue(user)).thenReturn(List.of(sameSlot, otherSlot));
            lenient().when(catalogRepo.findAll()).thenReturn(List.of());
            lenient().when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());

            service.equip(user, "frameNew");

            assertThat(sameSlot.isEquipped()).isFalse();
            assertThat(otherSlot.isEquipped()).isTrue();
            verify(userCosmeticRepo).save(sameSlot);
            verify(userCosmeticRepo, never()).save(otherSlot);
        }
    }

    @Nested
    @DisplayName("unequip")
    class Unequip {

        @Test
        @DisplayName("null slot → BadRequestException TM_933, no lookups")
        void nullSlot() {
            assertThatThrownBy(() -> service.unequip(user, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_933"));
            verify(userCosmeticRepo, never()).findByUserAndEquippedTrue(any());
        }

        @Test
        @DisplayName("unequips every equipped cosmetic in the given slot only")
        void unequipsMatchingSlot() {
            UserCosmetic frame = ownedRow("frameOld", CosmeticType.FRAME, true);
            UserCosmetic border = ownedRow("borderOld", CosmeticType.BORDER, true);
            when(userCosmeticRepo.findByUserAndEquippedTrue(user)).thenReturn(List.of(frame, border));
            lenient().when(reputationRepo.findByUser(user)).thenReturn(Optional.empty());
            lenient().when(catalogRepo.findAll()).thenReturn(List.of());
            lenient().when(userCosmeticRepo.findByUser(user)).thenReturn(List.of());

            service.unequip(user, CosmeticType.FRAME);

            assertThat(frame.isEquipped()).isFalse();
            assertThat(border.isEquipped()).isTrue();
            verify(userCosmeticRepo).save(frame);
            verify(userCosmeticRepo, never()).save(border);
        }
    }
}
