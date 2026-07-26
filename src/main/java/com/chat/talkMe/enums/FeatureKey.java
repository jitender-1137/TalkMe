package com.chat.talkMe.enums;

/**
 * The registry of every gate-able feature in the Late-Night Social ecosystem.
 *
 * Each key carries its own default entitlement rules, so adding a feature is a
 * one-line enum addition — no edits to {@code FeatureAccessService}. Effective
 * access for a (user, key) is resolved as:
 * <pre>
 *   global kill-switch (config) → admin DENY grant → entitlement (rules OR grant)
 *   → user self-toggle → ON
 * </pre>
 *
 * A {@link #parent} lets a sub-category roll up to its parent: if the parent is
 * off (globally or for the user), the child is off regardless of its own state
 * (e.g. VOICE_STATUS rolls up under VOICE_INTRO).
 *
 * The wire name ({@link #wireName()} = lowercase enum name) is what crosses the
 * API/JSON boundary and what the client's {@code useFeature("flirt_lobby")} reads.
 */
public enum FeatureKey {

    // key                    parent            requiresVerified  requiresAgeVerified  minRole                 defaultEntitled
    // ── Phase 1: Night Owl experience ──
    NIGHT_OWL                (null,            false,            false,               null,                   true),
    NIGHT_OWL_LOBBY          (NIGHT_OWL,       false,            false,               null,                   true),
    MOOD_ENERGY              (null,            false,            false,               null,                   true),
    LOOKING_FOR              (null,            false,            false,               null,                   true),
    SMART_PROFILE_CARD       (null,            false,            false,               null,                   true),

    // ── Phase 2: Matching core (the flirting heart) ──
    // defaultEntitled=true: the requiresVerified + requiresAgeVerified gates ARE the restriction
    // (age-verification itself requires the 18+ consent → adults-only, consent-first). If this were
    // false the feature would be UNREACHABLE by rule — only an admin ALLOW grant could enable it,
    // so no normal user could ever enter the Flirt Lobby.
    FLIRT_LOBBY              (null,            true,             true,                null,                   true),
    PREFERENCE_MATCH         (null,            false,            false,               null,                   true),
    MASK_CHAT                (null,            false,            false,               null,                   true),
    COFFEE_MATCH             (null,            false,            false,               null,                   true),
    CHEMISTRY_TIMER          (COFFEE_MATCH,    false,            false,               null,                   true),
    VOICE_INTRO              (null,            false,            false,               null,                   true),
    VOICE_BEFORE_PHOTO       (VOICE_INTRO,     false,            false,               null,                   true),
    COMPATIBILITY_METER      (null,            false,            false,               null,                   true),

    // ── Phase 3: Connections & retention ──
    SECRET_CRUSH             (null,            false,            false,               null,                   true),
    DAILY_COMPANION          (null,            false,            false,               null,                   true),
    WEEKLY_PICKS             (null,            false,            false,               null,                   true),
    AI_WINGMAN               (null,            false,            false,               null,                   true),
    CONVERSATION_GAMES       (null,            false,            false,               null,                   true),

    // ── Phase 4: Gamification ──
    GAMIFICATION             (null,            false,            false,               null,                   true),
    REPUTATION               (GAMIFICATION,    false,            false,               null,                   true),
    BADGES                   (GAMIFICATION,    false,            false,               null,                   true),
    COSMETICS                (GAMIFICATION,    false,            false,               null,                   true),
    STREAKS                  (GAMIFICATION,    false,            false,               null,                   true),
    PRESTIGE                 (GAMIFICATION,    false,            false,               null,                   true),

    // ── Phase 5: Immersive spaces & content ──
    INTEREST_ROOMS           (null,            false,            false,               null,                   true),
    MIDNIGHT_EVENTS          (NIGHT_OWL,       false,            true,                null,                   true),
    VIRTUAL_CITY             (null,            false,            false,               null,                   true),
    SLEEP_ROOMS              (null,            false,            false,               null,                   true),
    LISTENER                 (null,            false,            false,               null,                   true),
    VOICE_STATUS             (VOICE_INTRO,     false,            false,               null,                   true),
    TEMPORARY_POSTS          (null,            false,            false,               null,                   true),
    MUSIC_SESSION            (null,            false,            false,               null,                   true),
    BUCKET_LIST              (null,            false,            false,               null,                   true),
    RELATIONSHIP_JOURNEY     (null,            false,            false,               null,                   true),

    // ── Phase 7: Engagement & conversation utilities ──
    INSTANT_TRANSLATE        (null,            false,            false,               null,                   true),
    ANON_COMPLIMENTS         (null,            false,            false,               null,                   true),
    CONVERSATION_SUMMARY     (null,            false,            false,               null,                   true),
    SHARED_WHITEBOARD        (null,            false,            false,               null,                   true),
    FLIRT_MODE               (null,            true,             true,                null,                   true),
    SPEED_DATING             (null,            true,             true,                null,                   true),

    // ── Phase 6: Live A/V (deferred; default global-off via config) ──
    LIVE_AUDIO               (null,            true,             false,               null,                   true),

    // ── Admin ──
    ADMIN_FEATURE_MGMT       (null,            false,            false,               "ROLE_SUPER_ADMIN",     false);

    private final FeatureKey parent;
    private final boolean requiresVerified;
    private final boolean requiresAgeVerified;
    private final String minRole;
    private final boolean defaultEntitled;

    FeatureKey(FeatureKey parent, boolean requiresVerified, boolean requiresAgeVerified,
               String minRole, boolean defaultEntitled) {
        this.parent = parent;
        this.requiresVerified = requiresVerified;
        this.requiresAgeVerified = requiresAgeVerified;
        this.minRole = minRole;
        this.defaultEntitled = defaultEntitled;
    }

    public FeatureKey getParent() {
        return parent;
    }

    public boolean isRequiresVerified() {
        return requiresVerified;
    }

    public boolean isRequiresAgeVerified() {
        return requiresAgeVerified;
    }

    public String getMinRole() {
        return minRole;
    }

    public boolean isDefaultEntitled() {
        return defaultEntitled;
    }

    /** Lowercase name used across the API/JSON boundary and in the client's useFeature(). */
    public String wireName() {
        return name().toLowerCase();
    }

    /** Case-insensitive lookup by wire name; returns null when unknown. */
    public static FeatureKey fromWire(String wire) {
        if (wire == null) return null;
        try {
            return FeatureKey.valueOf(wire.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
