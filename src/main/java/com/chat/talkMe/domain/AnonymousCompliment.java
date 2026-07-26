package com.chat.talkMe.domain;

import com.chat.talkMe.enums.ComplimentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * An anonymous compliment one user sends to another (feature ANON_COMPLIMENTS).
 *
 * <p>Secrecy invariant (mirrors {@link SecretCrush}): the {@link #recipient} may read the
 * {@link #message} but NEVER the {@link #sender} — until, and only until, the sender
 * personally accepts a reveal request, flipping {@link #status} to
 * {@link ComplimentStatus#REVEALED}. Any mapping to a client DTO MUST omit sender identity
 * for every status other than {@code REVEALED}. The recipient identity is not secret (the
 * sender chose it), so the sender's own "sent" view may show it.
 */
@Entity
@Table(name = "anonymous_compliments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousCompliment extends BaseEntity {

    /** The author of the compliment — hidden from the recipient unless a reveal is accepted. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /** The user the compliment is for. Known to the sender; not secret. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** The compliment text shown to the recipient. */
    @Column(name = "message", length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'SENT'")
    @Builder.Default
    private ComplimentStatus status = ComplimentStatus.SENT;

    /** When the sender accepted the reveal; null unless {@code status == REVEALED}. */
    @Column(name = "revealed_at")
    private Instant revealedAt;
}
