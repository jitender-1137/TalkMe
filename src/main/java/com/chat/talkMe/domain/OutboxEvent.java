package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Transactional outbox row. Written in the SAME database transaction as the
 * message it describes, so it is impossible to commit a message without also
 * committing its "needs delivery" record (atomic — no crash window).
 *
 * <p>Lifecycle: {@code PENDING} → (delivered) → {@code PUBLISHED}. The fast path
 * (publish → consumer) flips it to PUBLISHED within milliseconds. Anything still
 * PENDING after a grace window is re-driven by {@code OutboxPublisherJob}, which
 * is what guarantees delivery even across an app crash or broker outage.
 *
 * <p>Deliberately a lean standalone entity (not {@code BaseEntity}) — no version,
 * audit, or uuid overhead — because it is inserted on every message send and
 * deleted shortly after; insert cost must stay minimal.
 */
@Entity
@Table(name = "outbox_event", indexes = {
        // Poller scan path: find PENDING rows oldest-first.
        @Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
}, uniqueConstraints = {
        // One row per business key; makes the producing insert idempotent on retry.
        @UniqueConstraint(name = "uk_outbox_event_key", columnNames = {"event_key"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique business key for this event — a message UUID for a message.send event,
     * or a generated UUID for a status event. Used for dedup and for the unique
     * constraint that makes the producing insert idempotent.
     */
    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;

    /** Event type, routes to the matching delivery handler (e.g. {@code message.send}). */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** JSON-serialized {@code MessageSentEvent} — the full payload to re-deliver. */
    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
