package com.chat.talkMe.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToOne;
import org.hibernate.annotations.Formula;
import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.chat.talkMe.enums.Interest;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", unique = true, length = 100)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "age")
    private Integer age;

    @Column(name = "gender", length = 20)
    private String gender;

    @Column(name = "is_guest", nullable = false)
    @Builder.Default
    private boolean isGuest = false;

    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private boolean isVerified = false;

    /**
     * Highest message id already covered by an "unread messages" digest email. The daily
     * digest job only emails when the user's newest unread message id exceeds this, so the
     * same still-unread messages never trigger a second email. Null ⇒ never sent a digest.
     */
    @Column(name = "last_unread_digest_message_id")
    private Long lastUnreadDigestMessageId;

    @Column(name = "profile_image", length = 512)
    private String profileImage;

    /**
     * Google account subject id ("sub" claim) for users who signed in with Google.
     * Null for password/guest accounts. Unique so a Google identity maps to exactly
     * one local user; login links by this first, then falls back to email.
     */
    @Column(name = "google_id", unique = true, length = 64)
    private String googleId;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "city", length = 100)
    private String city;

    // ── Latest activity location (derived from the client IP on login/refresh) ──
    // The "closest location / area" of the user's activity, shown on the admin
    // dashboard. Best-effort; may be null when geolocation is unavailable.
    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    @Column(name = "last_location", length = 255)
    private String lastLocation;

    @Column(name = "last_location_at")
    private java.time.Instant lastLocationAt;

    @Column(name = "mobile_number", length = 30)
    private String mobileNumber;

    @Column(name = "bio", length = 512)
    private String bio;

    @Column(name = "occupation", length = 100)
    private String occupation;

    @Column(name = "education", length = 100)
    private String education;

    @ElementCollection(targetClass = Interest.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "user_interests", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "interest", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<com.chat.talkMe.enums.Interest> interests = new java.util.HashSet<>();

    // ── Late-Night Social profile attributes ────────────────────────────────────
    // All nullable objects (never primitives) so "not set" is distinguishable — the
    // matching/compatibility engines treat null as "no preference".

    /** Current mood / intent (feature #4), updatable any time. */
    @Enumerated(EnumType.STRING)
    @Column(name = "mood", length = 30)
    private com.chat.talkMe.enums.Mood mood;

    @Column(name = "mood_updated_at")
    private java.time.Instant moodUpdatedAt;

    /** Conversation vibe (feature #5). */
    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_energy", length = 20)
    private com.chat.talkMe.enums.ConversationEnergy conversationEnergy;

    /** Languages spoken (feature #3 filter + compatibility). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_languages", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "language")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<com.chat.talkMe.enums.Language> languages = new java.util.HashSet<>();

    /** What the user is looking for (feature #29). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_looking_for", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "tag")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<com.chat.talkMe.enums.LookingForTag> lookingFor = new java.util.HashSet<>();

    /** Compact personality trait scores (0–100) for cosine compatibility. Lazy — off the hot path. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_personality", joinColumns = @JoinColumn(name = "user_id"))
    @MapKeyColumn(name = "trait")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "score")
    @Builder.Default
    private java.util.Map<com.chat.talkMe.enums.PersonalityTrait, Integer> personality = new java.util.HashMap<>();

    /** Async voice introduction (feature #16) — 15–30s clip stored via MediaStorage. */
    @Column(name = "voice_intro_url", length = 512)
    private String voiceIntroUrl;

    @Column(name = "voice_intro_duration_ms")
    private Integer voiceIntroDurationMs;

    /** Cached profile-completion percentage (0–100), recomputed on profile writes. Feeds gamification. */
    @Column(name = "profile_completion", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private int profileCompletion = 0;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @OneToOne(mappedBy = "user")
    private UserPresence presence;

    @Formula("COALESCE((SELECT CASE WHEN p.status = 'ONLINE' AND p.invisible_mode_enabled = false THEN 1 ELSE 0 END FROM user_presences p WHERE p.user_id = id), 0)")
    private int onlineSortWeight;

    /**
     * Durable last-seen time as a scalar subquery, so listings can ORDER BY it
     * reliably. (Ordering by the inverse {@link #presence} association in a Criteria
     * query is not emitted by Hibernate; a @Formula scalar is — same pattern as
     * {@link #onlineSortWeight}.) NULL when the user has no presence row yet.
     */
    @Formula("(SELECT p.last_seen_at FROM user_presences p WHERE p.user_id = id)")
    private java.time.Instant presenceLastSeenAt;

    /** How the user most recently accessed the app — drives push vs WS-only delivery. */
    @Enumerated(EnumType.STRING)
    @Column(name = "installation_type", length = 20)
    @Builder.Default
    private com.chat.talkMe.enums.InstallationType installationType = com.chat.talkMe.enums.InstallationType.BROWSER;

    /** Server-driven total unread message count, used for the app badge. */
    @Column(name = "total_unread_count", nullable = false, columnDefinition = "integer default 0")
    @Builder.Default
    private int totalUnreadCount = 0;

    /**
     * When the user requested account deletion. Combined with the soft-delete flag
     * ({@code isDeleted} from BaseEntity), the account is recoverable until this
     * timestamp + the configured window, after which it is permanently anonymized.
     * Null when the account is active (or already purged).
     */
    @Column(name = "deletion_requested_at")
    private java.time.Instant deletionRequestedAt;

    /**
     * Admin-imposed suspension (distinct from the user-initiated soft-delete above).
     * A banned account cannot authenticate (enforced in CustomUserDetails + login).
     * Toggled only from the SuperAdmin dashboard.
     */
    @Column(name = "banned", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean banned = false;
}
