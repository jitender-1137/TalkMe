package com.neo.chat.service.impl;

import com.neo.chat.cache.FeatureAccessCache;
import com.neo.chat.config.FeatureFlags;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserFeatureGrant;
import com.neo.chat.enums.FeatureKey;
import com.neo.chat.enums.GrantDecision;
import com.neo.chat.enums.GrantScope;
import com.neo.chat.repository.UserFeatureGrantRepository;
import com.neo.chat.service.AgeVerificationService;
import com.neo.chat.service.FeatureAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Reference resolution for feature access. Precedence per (user, key):
 * <ol>
 *   <li>global kill-switch off → NO (config)</li>
 *   <li>parent feature not accessible → NO (sub-category roll-up)</li>
 *   <li>ADMIN DENY grant → NO (moderation override)</li>
 *   <li>not entitled (rules) AND no ALLOW grant → NO</li>
 *   <li>SELF DENY grant → NO (user opted out)</li>
 *   <li>otherwise → YES</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureAccessServiceImpl implements FeatureAccessService {

    private final FeatureFlags featureFlags;
    private final UserFeatureGrantRepository grantRepository;
    private final FeatureAccessCache cache;
    private final AgeVerificationService ageVerificationService;

    /**
     * True when the user may use the feature right now (via the cached wire-name set).
     *
     * @param user the user (null → false)
     * @param key  the feature key (null → false)
     * @return whether access is currently granted
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasAccess(User user, FeatureKey key) {
        if (user == null || key == null) return false;
        return effectiveWireNames(user).contains(key.wireName());
    }

    /**
     * Resolve the full set of feature keys the user may use (uncached; evaluates every key).
     *
     * @param user the user
     * @return the accessible feature keys
     */
    @Override
    @Transactional(readOnly = true)
    public Set<FeatureKey> effectiveKeys(User user) {
        List<UserFeatureGrant> grants = grantRepository.findByUser(user);
        Instant now = Instant.now();
        EnumSet<FeatureKey> result = EnumSet.noneOf(FeatureKey.class);
        for (FeatureKey key : FeatureKey.values()) {
            if (resolve(user, key, grants, now)) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * Cache-backed wire names of all accessible features (computes {@link #effectiveKeys} on a miss).
     *
     * @param user the user
     * @return the ordered set of accessible feature wire names
     */
    @Override
    @Transactional(readOnly = true)
    public Set<String> effectiveWireNames(User user) {
        return cache.getOrCompute(user.getId(), () -> effectiveKeys(user).stream()
                .map(FeatureKey::wireName)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * Apply the user's own opt-in/opt-out: enabling clears any SELF DENY grant, disabling upserts
     * a SELF DENY grant. Evicts the user's access cache after commit.
     *
     * @param user    the user setting their own preference
     * @param key     the feature key
     * @param enabled true to opt in (remove opt-out), false to opt out
     */
    @Override
    @Transactional
    public void setSelfPreference(User user, FeatureKey key, boolean enabled) {
        grantRepository.findByUserAndFeatureKeyAndScope(user, key, GrantScope.SELF)
                .ifPresentOrElse(existing -> {
                    if (enabled) {
                        // Re-enable: clear the opt-out.
                        grantRepository.delete(existing);
                    } else {
                        existing.setDecision(GrantDecision.DENY);
                        grantRepository.save(existing);
                    }
                }, () -> {
                    if (!enabled) {
                        grantRepository.save(UserFeatureGrant.builder()
                                .user(user)
                                .featureKey(key)
                                .decision(GrantDecision.DENY)
                                .scope(GrantScope.SELF)
                                .build());
                    }
                });
        evictAfterCommit(user.getId());
    }

    /**
     * Upsert an admin/cohort feature grant for a user, then evict their access cache after commit.
     *
     * @param target    the user the grant applies to
     * @param key       the feature key
     * @param decision  ALLOW or DENY
     * @param scope     the grant scope (e.g. ADMIN, COHORT, SELF)
     * @param cohort    optional cohort tag
     * @param expiresAt optional expiry instant (null = never)
     * @param note      optional audit note
     */
    @Override
    @Transactional
    public void grant(User target, FeatureKey key, GrantDecision decision, GrantScope scope,
                      String cohort, Instant expiresAt, String note) {
        UserFeatureGrant grant = grantRepository.findByUserAndFeatureKeyAndScope(target, key, scope)
                .orElseGet(() -> UserFeatureGrant.builder()
                        .user(target).featureKey(key).scope(scope).build());
        grant.setDecision(decision);
        grant.setCohort(cohort);
        grant.setExpiresAt(expiresAt);
        grant.setNote(note);
        grantRepository.save(grant);
        evictAfterCommit(target.getId());
        log.info("Feature grant upserted: user={} key={} decision={} scope={}",
                target.getId(), key, decision, scope);
    }

    /**
     * Clear a user's ADMIN and COHORT grants for a feature (their SELF opt-out is preserved), then
     * evict their access cache after commit.
     *
     * @param target the user whose grants are cleared
     * @param key    the feature key
     */
    @Override
    @Transactional
    public void revoke(User target, FeatureKey key) {
        // Clear ADMIN/COHORT grants only — preserve the user's own SELF opt-out.
        grantRepository.deleteByUserAndFeatureKeyAndScopeIn(
                target, key, EnumSet.of(GrantScope.ADMIN, GrantScope.COHORT));
        evictAfterCommit(target.getId());
    }

    /**
     * Evict the user's cached access AFTER the surrounding transaction commits, so a
     * concurrent reader in the evict→commit window can't repopulate the cache with the
     * pre-change (uncommitted-invisible) value. Falls back to an immediate evict when
     * there is no active transaction.
     */
    private void evictAfterCommit(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(userId);
                }
            });
        } else {
            cache.evict(userId);
        }
    }

    // ── resolution internals ───────────────────────────────────────────────

    /**
     * Resolve access for one (user, key) applying the documented precedence: global kill-switch,
     * ad-free hard exemption, parent roll-up, ADMIN DENY, rule/ALLOW entitlement, then SELF DENY.
     *
     * @param user   the user
     * @param key    the feature key
     * @param grants the user's grants (pre-fetched)
     * @param now    evaluation instant (for grant activeness/expiry)
     * @return whether access is granted
     */
    private boolean resolve(User user, FeatureKey key, List<UserFeatureGrant> grants, Instant now) {
        if (!featureFlags.isGloballyEnabled(key)) return false;
        // Per-user ad exemption: an ad-free user (the seam a future Premium tier flips)
        // NEVER sees ads — a HARD gate here (not just a rule) so not even an admin/cohort
        // ALLOW grant can re-enable ads for them. Everyone else (adsFree=false, the default)
        // sees ads whenever advertising is globally on.
        if (key == FeatureKey.ADS && user.isAdsFree()) return false;
        // Sub-category roll-up: a child is only accessible if its parent is.
        if (key.getParent() != null && !resolve(user, key.getParent(), grants, now)) return false;

        boolean adminDeny = anyGrant(grants, key, now,
                g -> g.getScope() == GrantScope.ADMIN && g.getDecision() == GrantDecision.DENY);
        if (adminDeny) return false;

        boolean allowGrant = anyGrant(grants, key, now,
                g -> (g.getScope() == GrantScope.ADMIN || g.getScope() == GrantScope.COHORT)
                        && g.getDecision() == GrantDecision.ALLOW);

        boolean entitled = ruleEntitled(user, key) || allowGrant;
        if (!entitled) return false;

        boolean selfDeny = anyGrant(grants, key, now,
                g -> g.getScope() == GrantScope.SELF && g.getDecision() == GrantDecision.DENY);
        return !selfDeny;
    }

    /**
     * Rule-based entitlement: min-role, email-verified (unless bypassed) and age-verified gates,
     * falling through to the key's default-entitled flag.
     *
     * @param user the user
     * @param key  the feature key
     * @return whether the user is entitled by rules alone (before grants)
     */
    private boolean ruleEntitled(User user, FeatureKey key) {
        if (key.getMinRole() != null && !hasRole(user, key.getMinRole())) return false;
        if (key.isRequiresVerified() && !user.isVerified() && !verifiedGateBypassed(key)) return false;
        if (key.isRequiresAgeVerified() && !ageVerificationService.isAgeVerified(user)) return false;
        return key.isDefaultEntitled();
    }

    /**
     * Config-driven relaxation of the email-verified gate. Currently only FLIRT_MODE, when
     * {@code features.allow-non-verified-flirt-mode} is on, so unverified users can flirt.
     * The 18+ age gate is never bypassed here.
     */
    private boolean verifiedGateBypassed(FeatureKey key) {
        return key == FeatureKey.FLIRT_MODE && featureFlags.isAllowNonVerifiedFlirtMode();
    }

    /**
     * True if the user holds a role with the given name.
     *
     * @param user     the user
     * @param roleName the role name to look for
     * @return whether the role is present
     */
    private static boolean hasRole(User user, String roleName) {
        if (user.getRoles() == null) return false;
        for (Role r : user.getRoles()) {
            if (roleName.equals(r.getName())) return true;
        }
        return false;
    }

    /**
     * True if any active grant for the given key at {@code now} matches the predicate.
     *
     * @param grants the user's grants
     * @param key    the feature key to match
     * @param now    activeness/expiry evaluation instant
     * @param pred   additional scope/decision predicate
     * @return whether a matching active grant exists
     */
    private static boolean anyGrant(List<UserFeatureGrant> grants, FeatureKey key, Instant now,
                                    Predicate<UserFeatureGrant> pred) {
        for (UserFeatureGrant g : grants) {
            if (g.getFeatureKey() == key && g.isActive(now) && pred.test(g)) return true;
        }
        return false;
    }
}
