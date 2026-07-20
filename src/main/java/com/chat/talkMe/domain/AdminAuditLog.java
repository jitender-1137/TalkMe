package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * An immutable record of a privileged SuperAdmin action (viewing decrypted messages,
 * banning, role changes, …). Append-only accountability trail for the dashboard.
 */
@Entity
@Table(name = "admin_audit_logs", indexes = {
        @Index(name = "idx_admin_audit_created", columnList = "created_at"),
        @Index(name = "idx_admin_audit_admin", columnList = "admin_username")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLog extends BaseEntity {

    /** UUID of the acting admin (nullable if resolved only by username). */
    @Column(name = "admin_id", length = 64)
    private String adminId;

    @Column(name = "admin_username", nullable = false, length = 100)
    private String adminUsername;

    /** Machine action code, e.g. VIEW_MESSAGES, BAN_USER, GRANT_ROLE. */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** What kind of thing was acted on: USER / CHAT. */
    @Column(name = "target_type", length = 32)
    private String targetType;

    /** UUID of the target (user/chat). */
    @Column(name = "target_id", length = 64)
    private String targetId;

    /** Free-text detail (old→new value, reason, count, …). */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;
}
