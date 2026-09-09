package com.neo.chat.domain;

import com.neo.chat.enums.ConsentType;
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

import java.time.Instant;

/**
 * A record that a user accepted a given consent at a specific version. One row per
 * (user, consentType) — re-accepting a bumped version updates the same row. When the
 * required version in config moves ahead of the stored {@code version}, the user is
 * re-prompted (their old acceptance no longer satisfies the gate).
 */
@Entity
@Table(name = "consent_acceptances",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_consent_user_type",
                columnNames = {"user_id", "consent_type"}))
// user_id lookups are covered by the leading column of uk_consent_user_type; the former
// idx_consent_user single-column index was redundant (BootUI DB-SCHEMA-003).
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsentAcceptance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 30)
    private ConsentType consentType;

    /**
     * Named consentVersion to avoid clashing with BaseEntity's optimistic-lock {@code version}.
     */
    @Column(name = "consent_version", nullable = false, length = 20)
    private String consentVersion;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    /**
     * IP at acceptance time, for audit. Best-effort.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
