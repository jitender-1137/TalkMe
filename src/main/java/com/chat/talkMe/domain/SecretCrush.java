package com.chat.talkMe.domain;

import com.chat.talkMe.enums.SecretCrushStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * A private, one-directional "crush" one user places on another (feature #9).
 *
 * <p>Secrecy invariant: a row is only ever readable by its {@link #crusher}. No query or
 * endpoint may enumerate the rows pointing AT a target — a one-sided crush must stay
 * invisible to the person crushed on. Disclosure happens only when both directions exist,
 * at which point BOTH rows flip to {@link SecretCrushStatus#MATCHED} and the match (and
 * partner identity) is revealed symmetrically to the two participants.
 *
 * <p>The {@code (crusher_id, target_id)} unique constraint makes each crush idempotent.
 */
@Entity
@Table(
        name = "secret_crushes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_secret_crush_crusher_target",
                columnNames = {"crusher_id", "target_id"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretCrush extends BaseEntity {

    /** The user placing the crush — the only party allowed to read this row. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crusher_id", nullable = false)
    private User crusher;

    /** The user being crushed on. Never told about this row unless a match occurs. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private User target;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'ACTIVE'")
    @Builder.Default
    private SecretCrushStatus status = SecretCrushStatus.ACTIVE;
}
