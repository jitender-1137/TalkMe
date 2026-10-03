package com.neo.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An immutable record of a single username change for a user — captured every time a username is
 * actually replaced (by the user themselves or by an admin). Append-only history surfaced ONLY in
 * the SuperAdmin dashboard (there is no user-facing endpoint). Mirrors {@link AdminAuditLog}: the
 * user reference is stored as loose {@code userId}/{@code userUuid} columns (no FK) so history
 * survives independently of the account row.
 */
@Entity
@Table(name = "username_history", indexes = {
        @Index(name = "idx_username_history_user", columnList = "user_id"),
        @Index(name = "idx_username_history_created", columnList = "created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsernameHistory extends BaseEntity {

    /** DB id of the user whose username changed. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** UUID of the user whose username changed (for admin cross-linking). */
    @Column(name = "user_uuid", length = 64)
    private String userUuid;

    /** The username in effect before this change (null only if it was never set). */
    @Column(name = "old_username", length = 100)
    private String oldUsername;

    /** The username set by this change. */
    @Column(name = "new_username", nullable = false, length = 100)
    private String newUsername;

    /** Who performed the change: the user's own username, or the acting admin's username. */
    @Column(name = "changed_by", length = 100)
    private String changedBy;

    /** Who performed the change, coarse-grained: {@code SELF} or {@code ADMIN}. */
    @Column(name = "changed_by_type", length = 16)
    private String changedByType;
}
