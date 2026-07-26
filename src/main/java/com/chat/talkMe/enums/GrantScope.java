package com.chat.talkMe.enums;

/**
 * Who created a {@code UserFeatureGrant}, which sets its precedence:
 * <ul>
 *   <li>{@code ADMIN} — moderation/manual override. An ADMIN DENY is a hard block
 *       that outranks entitlement rules.</li>
 *   <li>{@code COHORT} — beta/rollout tag. Behaves like an ADMIN ALLOW for entitlement.</li>
 *   <li>{@code SELF} — the user's own on/off preference (Tier-3 toggle).</li>
 * </ul>
 */
public enum GrantScope {
    ADMIN,
    COHORT,
    SELF
}
