package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.FeatureAccessCache;
import com.chat.talkMe.config.FeatureFlags;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserFeatureGrant;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.enums.GrantDecision;
import com.chat.talkMe.enums.GrantScope;
import com.chat.talkMe.repository.UserFeatureGrantRepository;
import com.chat.talkMe.service.AgeVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link FeatureAccessServiceImpl}. Drives the full precedence
 * ladder resolve():
 * <ol>
 *   <li>global kill-switch off → NO</li>
 *   <li>parent feature not accessible → NO (roll-up)</li>
 *   <li>ADMIN DENY → NO</li>
 *   <li>not entitled (rule OR allow-grant) → NO</li>
 *   <li>SELF DENY → NO</li>
 *   <li>otherwise → YES</li>
 * </ol>
 * plus the write paths (self-preference, grant upsert, revoke) and the after-commit cache
 * eviction seam.
 *
 * <p>{@code effectiveWireNames} caches via {@link FeatureAccessCache#getOrCompute}; the shared
 * stub invokes the supplied loader so the real entitlement computation is exercised end-to-end.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureAccessServiceImpl (unit)")
class FeatureAccessServiceImplTest {

    @Mock private FeatureFlags featureFlags;
    @Mock private UserFeatureGrantRepository grantRepository;
    @Mock private FeatureAccessCache cache;
    @Mock private AgeVerificationService ageVerificationService;

    private FeatureAccessServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new FeatureAccessServiceImpl(featureFlags, grantRepository, cache, ageVerificationService);
        user = User.builder()
                .username("neo").name("Neo")
                .isVerified(true)
                .roles(Set.of(Role.builder().name("ROLE_USER").build()))
                .build();
        user.setId(42L);

        // Shared lenient defaults — individual tests override the specific key/flag they probe.
        lenient().when(featureFlags.isGloballyEnabled(any())).thenReturn(true);
        lenient().when(ageVerificationService.isAgeVerified(any())).thenReturn(true);
        // getOrCompute delegates to the loader so the real computation runs. Null-guard the
        // supplier: re-stubbing getOrCompute later (e.g. with thenReturn) re-invokes this answer
        // with a null loader during recording, which would otherwise NPE.
        lenient().when(cache.getOrCompute(anyLong(), any())).thenAnswer(inv -> {
            Supplier<?> loader = inv.getArgument(1, Supplier.class);
            return loader == null ? Set.of() : loader.get();
        });
    }

    private UserFeatureGrant grant(FeatureKey key, GrantDecision decision, GrantScope scope) {
        return UserFeatureGrant.builder().user(user).featureKey(key).decision(decision).scope(scope).build();
    }

    @Nested
    @DisplayName("hasAccess")
    class HasAccess {

        @Test
        @DisplayName("null user → false, no repository/cache lookup")
        void nullUser() {
            assertThat(service.hasAccess(null, FeatureKey.NIGHT_OWL)).isFalse();
            verifyNoInteractions(grantRepository);
            verify(cache, never()).getOrCompute(anyLong(), any());
        }

        @Test
        @DisplayName("null key → false")
        void nullKey() {
            assertThat(service.hasAccess(user, null)).isFalse();
            verifyNoInteractions(grantRepository);
            verify(cache, never()).getOrCompute(anyLong(), any());
        }

        @Test
        @DisplayName("default-entitled feature with no grants → true")
        void entitledByDefault() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL)).isTrue();
        }

        @Test
        @DisplayName("non-ad-free user (default) sees ads → ADS true")
        void nonAdFreeUserSeesAds() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            assertThat(user.isAdsFree()).isFalse();
            assertThat(service.hasAccess(user, FeatureKey.ADS)).isTrue();
        }

        @Test
        @DisplayName("ad-free user (e.g. Premium) sees no ads → ADS false even when globally on")
        void adFreeUserSeesNoAds() {
            user.setAdsFree(true);
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            assertThat(service.hasAccess(user, FeatureKey.ADS)).isFalse();
        }

        @Test
        @DisplayName("ad-free exemption is a HARD gate — beats an ADMIN ALLOW grant on ads")
        void adFreeBeatsAllowGrant() {
            user.setAdsFree(true);
            when(grantRepository.findByUser(user))
                    .thenReturn(List.of(grant(FeatureKey.ADS, GrantDecision.ALLOW, GrantScope.ADMIN)));

            assertThat(service.hasAccess(user, FeatureKey.ADS)).isFalse();
        }

        @Test
        @DisplayName("globally disabled key → false")
        void globallyDisabled() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());
            when(featureFlags.isGloballyEnabled(FeatureKey.NIGHT_OWL)).thenReturn(false);

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL)).isFalse();
        }

        @Test
        @DisplayName("ADMIN DENY grant → false even though rule-entitled")
        void adminDenyBlocks() {
            when(grantRepository.findByUser(user))
                    .thenReturn(List.of(grant(FeatureKey.NIGHT_OWL, GrantDecision.DENY, GrantScope.ADMIN)));

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL)).isFalse();
        }

        @Test
        @DisplayName("SELF DENY grant → false (user opted out)")
        void selfDenyBlocks() {
            when(grantRepository.findByUser(user))
                    .thenReturn(List.of(grant(FeatureKey.NIGHT_OWL, GrantDecision.DENY, GrantScope.SELF)));

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL)).isFalse();
        }

        @Test
        @DisplayName("expired grant is ignored → still entitled")
        void expiredGrantIgnored() {
            UserFeatureGrant expired = grant(FeatureKey.NIGHT_OWL, GrantDecision.DENY, GrantScope.SELF);
            expired.setExpiresAt(Instant.now().minusSeconds(60));
            when(grantRepository.findByUser(user)).thenReturn(List.of(expired));

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL)).isTrue();
        }

        @Test
        @DisplayName("requiresVerified feature + unverified user → false")
        void requiresVerifiedFailsWhenUnverified() {
            user.setVerified(false);
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            assertThat(service.hasAccess(user, FeatureKey.FLIRT_LOBBY)).isFalse();
        }

        @Test
        @DisplayName("requiresAgeVerified feature + not age-verified → false")
        void requiresAgeVerifiedFailsWhenNotVerified() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());
            when(ageVerificationService.isAgeVerified(user)).thenReturn(false);

            assertThat(service.hasAccess(user, FeatureKey.FLIRT_LOBBY)).isFalse();
        }

        @Test
        @DisplayName("verified + age-verified user → adult feature accessible")
        void adultFeatureAccessibleWhenGatesCleared() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            assertThat(service.hasAccess(user, FeatureKey.FLIRT_LOBBY)).isTrue();
        }

        @Test
        @DisplayName("child feature is off when its parent is globally off (roll-up)")
        void childRollsUpUnderParent() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());
            when(featureFlags.isGloballyEnabled(FeatureKey.NIGHT_OWL)).thenReturn(false);

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL_LOBBY)).isFalse();
        }

        @Test
        @DisplayName("child off when parent is admin-DENYed for the user (roll-up)")
        void childOffWhenParentAdminDenied() {
            when(grantRepository.findByUser(user))
                    .thenReturn(List.of(grant(FeatureKey.NIGHT_OWL, GrantDecision.DENY, GrantScope.ADMIN)));

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL_LOBBY)).isFalse();
        }

        @Test
        @DisplayName("minRole feature denied for a normal user without the role")
        void minRoleDeniedForNonAdmin() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            assertThat(service.hasAccess(user, FeatureKey.ADMIN_FEATURE_MGMT)).isFalse();
        }

        @Test
        @DisplayName("COHORT ALLOW grant bypasses the rule (defaultEntitled=false) → accessible")
        void cohortAllowOverridesRule() {
            when(grantRepository.findByUser(user))
                    .thenReturn(List.of(grant(FeatureKey.ADMIN_FEATURE_MGMT, GrantDecision.ALLOW, GrantScope.COHORT)));

            assertThat(service.hasAccess(user, FeatureKey.ADMIN_FEATURE_MGMT)).isTrue();
        }

        @Test
        @DisplayName("ADMIN DENY outranks a coexisting SELF/COHORT ALLOW")
        void adminDenyOutranksAllow() {
            when(grantRepository.findByUser(user)).thenReturn(List.of(
                    grant(FeatureKey.NIGHT_OWL, GrantDecision.ALLOW, GrantScope.COHORT),
                    grant(FeatureKey.NIGHT_OWL, GrantDecision.DENY, GrantScope.ADMIN)));

            assertThat(service.hasAccess(user, FeatureKey.NIGHT_OWL)).isFalse();
        }
    }

    @Nested
    @DisplayName("effectiveKeys")
    class EffectiveKeys {

        @Test
        @DisplayName("verified + age-verified user → contains default features, excludes admin-only")
        void nominalSet() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            Set<FeatureKey> keys = service.effectiveKeys(user);

            assertThat(keys)
                    .contains(FeatureKey.NIGHT_OWL, FeatureKey.MOOD_ENERGY, FeatureKey.FLIRT_LOBBY)
                    .doesNotContain(FeatureKey.ADMIN_FEATURE_MGMT);
        }

        @Test
        @DisplayName("unverified user → adult features dropped, non-gated ones retained")
        void unverifiedDropsAdultFeatures() {
            user.setVerified(false);
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            Set<FeatureKey> keys = service.effectiveKeys(user);

            assertThat(keys)
                    .contains(FeatureKey.NIGHT_OWL, FeatureKey.MOOD_ENERGY)
                    .doesNotContain(FeatureKey.FLIRT_LOBBY, FeatureKey.FLIRT_MODE, FeatureKey.SPEED_DATING);
        }

        @Test
        @DisplayName("one globally-disabled key is excluded; the rest remain")
        void globallyDisabledKeyExcluded() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());
            when(featureFlags.isGloballyEnabled(FeatureKey.MOOD_ENERGY)).thenReturn(false);

            Set<FeatureKey> keys = service.effectiveKeys(user);

            assertThat(keys).doesNotContain(FeatureKey.MOOD_ENERGY).contains(FeatureKey.NIGHT_OWL);
        }

        @Test
        @DisplayName("SELF DENY removes just that key from the effective set")
        void selfDenyRemovesKey() {
            when(grantRepository.findByUser(user))
                    .thenReturn(List.of(grant(FeatureKey.MOOD_ENERGY, GrantDecision.DENY, GrantScope.SELF)));

            Set<FeatureKey> keys = service.effectiveKeys(user);

            assertThat(keys).doesNotContain(FeatureKey.MOOD_ENERGY).contains(FeatureKey.NIGHT_OWL);
        }
    }

    @Nested
    @DisplayName("effectiveWireNames")
    class EffectiveWireNames {

        @Test
        @DisplayName("delegates to the cache keyed by userId and returns its value")
        void delegatesToCache() {
            when(cache.getOrCompute(eq(42L), any())).thenReturn(Set.of("night_owl", "mood_energy"));

            Set<String> names = service.effectiveWireNames(user);

            assertThat(names).containsExactlyInAnyOrder("night_owl", "mood_energy");
            verify(cache).getOrCompute(eq(42L), any());
        }

        @Test
        @DisplayName("cache miss → loader computes lowercase wire names via effectiveKeys")
        void loaderComputesWireNames() {
            when(grantRepository.findByUser(user)).thenReturn(List.of());

            Set<String> names = service.effectiveWireNames(user);

            assertThat(names).contains("night_owl", "flirt_lobby").doesNotContain("admin_feature_mgmt");
        }
    }

    @Nested
    @DisplayName("setSelfPreference")
    class SetSelfPreference {

        @Test
        @DisplayName("disable with no existing grant → inserts a SELF DENY row, evicts cache")
        void disableInsertsSelfDeny() {
            when(grantRepository.findByUserAndFeatureKeyAndScope(user, FeatureKey.MOOD_ENERGY, GrantScope.SELF))
                    .thenReturn(Optional.empty());

            service.setSelfPreference(user, FeatureKey.MOOD_ENERGY, false);

            ArgumentCaptor<UserFeatureGrant> saved = ArgumentCaptor.forClass(UserFeatureGrant.class);
            verify(grantRepository).save(saved.capture());
            assertThat(saved.getValue().getDecision()).isEqualTo(GrantDecision.DENY);
            assertThat(saved.getValue().getScope()).isEqualTo(GrantScope.SELF);
            assertThat(saved.getValue().getFeatureKey()).isEqualTo(FeatureKey.MOOD_ENERGY);
            assertThat(saved.getValue().getUser()).isSameAs(user);
            verify(cache).evict(42L);
        }

        @Test
        @DisplayName("enable with no existing grant → no-op (no save/delete), still evicts")
        void enableWithoutExistingIsNoop() {
            when(grantRepository.findByUserAndFeatureKeyAndScope(user, FeatureKey.MOOD_ENERGY, GrantScope.SELF))
                    .thenReturn(Optional.empty());

            service.setSelfPreference(user, FeatureKey.MOOD_ENERGY, true);

            verify(grantRepository, never()).save(any());
            verify(grantRepository, never()).delete(any());
            verify(cache).evict(42L);
        }

        @Test
        @DisplayName("enable with an existing opt-out → deletes the opt-out row, evicts")
        void enableClearsExistingOptOut() {
            UserFeatureGrant existing = grant(FeatureKey.MOOD_ENERGY, GrantDecision.DENY, GrantScope.SELF);
            when(grantRepository.findByUserAndFeatureKeyAndScope(user, FeatureKey.MOOD_ENERGY, GrantScope.SELF))
                    .thenReturn(Optional.of(existing));

            service.setSelfPreference(user, FeatureKey.MOOD_ENERGY, true);

            verify(grantRepository).delete(existing);
            verify(grantRepository, never()).save(any());
            verify(cache).evict(42L);
        }

        @Test
        @DisplayName("disable with an existing grant → flips it to DENY and saves")
        void disableUpdatesExistingToDeny() {
            UserFeatureGrant existing = grant(FeatureKey.MOOD_ENERGY, GrantDecision.ALLOW, GrantScope.SELF);
            when(grantRepository.findByUserAndFeatureKeyAndScope(user, FeatureKey.MOOD_ENERGY, GrantScope.SELF))
                    .thenReturn(Optional.of(existing));

            service.setSelfPreference(user, FeatureKey.MOOD_ENERGY, false);

            ArgumentCaptor<UserFeatureGrant> saved = ArgumentCaptor.forClass(UserFeatureGrant.class);
            verify(grantRepository).save(saved.capture());
            assertThat(saved.getValue()).isSameAs(existing);
            assertThat(saved.getValue().getDecision()).isEqualTo(GrantDecision.DENY);
            verify(grantRepository, never()).delete(any());
            verify(cache).evict(42L);
        }
    }

    @Nested
    @DisplayName("grant")
    class Grant {

        @Test
        @DisplayName("no existing grant → inserts a new one with all fields set, evicts")
        void insertsNewGrant() {
            Instant expiry = Instant.now().plusSeconds(3600);
            when(grantRepository.findByUserAndFeatureKeyAndScope(user, FeatureKey.LIVE_AUDIO, GrantScope.ADMIN))
                    .thenReturn(Optional.empty());

            service.grant(user, FeatureKey.LIVE_AUDIO, GrantDecision.ALLOW, GrantScope.ADMIN,
                    "beta", expiry, "manual override");

            ArgumentCaptor<UserFeatureGrant> saved = ArgumentCaptor.forClass(UserFeatureGrant.class);
            verify(grantRepository).save(saved.capture());
            UserFeatureGrant g = saved.getValue();
            assertThat(g.getUser()).isSameAs(user);
            assertThat(g.getFeatureKey()).isEqualTo(FeatureKey.LIVE_AUDIO);
            assertThat(g.getScope()).isEqualTo(GrantScope.ADMIN);
            assertThat(g.getDecision()).isEqualTo(GrantDecision.ALLOW);
            assertThat(g.getCohort()).isEqualTo("beta");
            assertThat(g.getExpiresAt()).isEqualTo(expiry);
            assertThat(g.getNote()).isEqualTo("manual override");
            verify(cache).evict(42L);
        }

        @Test
        @DisplayName("existing grant → updates it in place (same row) with the new decision/fields")
        void updatesExistingGrant() {
            UserFeatureGrant existing = grant(FeatureKey.LIVE_AUDIO, GrantDecision.ALLOW, GrantScope.ADMIN);
            existing.setNote("old");
            when(grantRepository.findByUserAndFeatureKeyAndScope(user, FeatureKey.LIVE_AUDIO, GrantScope.ADMIN))
                    .thenReturn(Optional.of(existing));

            service.grant(user, FeatureKey.LIVE_AUDIO, GrantDecision.DENY, GrantScope.ADMIN,
                    null, null, "revoked by mod");

            ArgumentCaptor<UserFeatureGrant> saved = ArgumentCaptor.forClass(UserFeatureGrant.class);
            verify(grantRepository).save(saved.capture());
            assertThat(saved.getValue()).isSameAs(existing);
            assertThat(saved.getValue().getDecision()).isEqualTo(GrantDecision.DENY);
            assertThat(saved.getValue().getNote()).isEqualTo("revoked by mod");
            assertThat(saved.getValue().getCohort()).isNull();
            assertThat(saved.getValue().getExpiresAt()).isNull();
            verify(cache).evict(42L);
        }
    }

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("deletes only ADMIN + COHORT grants (preserving SELF), evicts cache")
        void deletesAdminAndCohortOnly() {
            service.revoke(user, FeatureKey.NIGHT_OWL);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<GrantScope>> scopes = ArgumentCaptor.forClass(Collection.class);
            verify(grantRepository).deleteByUserAndFeatureKeyAndScopeIn(
                    eq(user), eq(FeatureKey.NIGHT_OWL), scopes.capture());
            assertThat(scopes.getValue())
                    .containsExactlyInAnyOrder(GrantScope.ADMIN, GrantScope.COHORT)
                    .doesNotContain(GrantScope.SELF);
            verify(cache).evict(42L);
        }
    }

    @Nested
    @DisplayName("evictAfterCommit (transaction seam)")
    class EvictAfterCommit {

        @Test
        @DisplayName("with an active transaction → eviction is deferred to afterCommit")
        void defersEvictionUntilCommit() {
            TransactionSynchronizationManager.initSynchronization();
            try {
                when(grantRepository.findByUserAndFeatureKeyAndScope(user, FeatureKey.MOOD_ENERGY, GrantScope.SELF))
                        .thenReturn(Optional.empty());

                // enabled=true + no existing → the only side effect is the deferred evict.
                service.setSelfPreference(user, FeatureKey.MOOD_ENERGY, true);

                // Not evicted yet — a synchronization was registered instead.
                verify(cache, never()).evict(anyLong());
                List<TransactionSynchronization> syncs =
                        TransactionSynchronizationManager.getSynchronizations();
                assertThat(syncs).hasSize(1);

                // Simulate the commit.
                syncs.get(0).afterCommit();
                verify(cache).evict(42L);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }
}
