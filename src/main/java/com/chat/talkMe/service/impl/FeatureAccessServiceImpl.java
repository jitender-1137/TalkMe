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
import com.chat.talkMe.service.FeatureAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumSet;
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

    @Override
    @Transactional(readOnly = true)
    public boolean hasAccess(User user, FeatureKey key) {
        if (user == null || key == null) return false;
        return effectiveWireNames(user).contains(key.wireName());
    }

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

    @Override
    @Transactional(readOnly = true)
    public Set<String> effectiveWireNames(User user) {
        return cache.getOrCompute(user.getId(), () -> effectiveKeys(user).stream()
                .map(FeatureKey::wireName)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
    }

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

    private boolean resolve(User user, FeatureKey key, List<UserFeatureGrant> grants, Instant now) {
        if (!featureFlags.isGloballyEnabled(key)) return false;
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

    private boolean ruleEntitled(User user, FeatureKey key) {
        if (key.getMinRole() != null && !hasRole(user, key.getMinRole())) return false;
        if (key.isRequiresVerified() && !user.isVerified()) return false;
        if (key.isRequiresAgeVerified() && !ageVerificationService.isAgeVerified(user)) return false;
        return key.isDefaultEntitled();
    }

    private static boolean hasRole(User user, String roleName) {
        if (user.getRoles() == null) return false;
        for (Role r : user.getRoles()) {
            if (roleName.equals(r.getName())) return true;
        }
        return false;
    }

    private static boolean anyGrant(List<UserFeatureGrant> grants, FeatureKey key, Instant now,
                                    Predicate<UserFeatureGrant> pred) {
        for (UserFeatureGrant g : grants) {
            if (g.getFeatureKey() == key && g.isActive(now) && pred.test(g)) return true;
        }
        return false;
    }
}
