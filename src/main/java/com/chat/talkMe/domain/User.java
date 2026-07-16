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
