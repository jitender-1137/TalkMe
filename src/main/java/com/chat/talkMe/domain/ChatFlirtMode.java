package com.chat.talkMe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * Per-chat "Flirt Mode" mutual toggle (FeatureKey {@code FLIRT_MODE}) — a revertible flag on a
 * single 1:1 (PRIVATE) chat. Distinct from the Flirt Lobby: this is scoped to one existing chat
 * between two specific users. It is only {@link #active} when BOTH participants have opted in;
 * either participant disabling immediately reverts {@link #active} to false.
 *
 * <p><b>Deterministic keying.</b> To avoid "who is user1 / who is user2" ambiguity regardless of
 * which participant calls the endpoint, the two per-participant consent booleans are keyed by
 * comparing user ids:
 * <ul>
 *   <li>{@link #enabledByLow}  → the participant with the <em>smaller</em> {@code User.id}
 *       ({@link #lowUserId}).</li>
 *   <li>{@link #enabledByHigh} → the participant with the <em>larger</em>  {@code User.id}
 *       ({@link #highUserId}).</li>
 * </ul>
 * {@link #lowUserId}/{@link #highUserId} are denormalized so a viewer's perspective
 * (myEnabled / otherEnabled) can be computed without re-loading chat membership.
 *
 * <p>One row per chat — enforced by the unique {@code chat_id} join column.
 */
@Entity
@Table(name = "chat_flirt_mode")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatFlirtMode extends BaseEntity {

    /** The owning 1:1 chat. Unique — at most one flirt-mode row per chat. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_id", nullable = false, unique = true)
    private Chat chat;

    /** {@code User.id} of the participant with the smaller id (maps to {@link #enabledByLow}). */
    @Column(name = "low_user_id", nullable = false)
    private Long lowUserId;

    /** {@code User.id} of the participant with the larger id (maps to {@link #enabledByHigh}). */
    @Column(name = "high_user_id", nullable = false)
    private Long highUserId;

    /** Whether the lower-id participant has opted into flirt mode. */
    @Column(name = "enabled_by_low", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean enabledByLow = false;

    /** Whether the higher-id participant has opted into flirt mode. */
    @Column(name = "enabled_by_high", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean enabledByHigh = false;

    /** Derived: true only when BOTH participants have opted in. Kept in sync on every mutation. */
    @Column(name = "active", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean active = false;

    /** Recompute {@link #active} from the two consent flags. Call after any consent change. */
    public void recomputeActive() {
        this.active = this.enabledByLow && this.enabledByHigh;
    }
}
