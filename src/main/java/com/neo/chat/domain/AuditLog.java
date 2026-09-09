package com.neo.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "audit_logs",
        indexes = @Index(name = "idx_audit_logs_actor_id", columnList = "actor_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog extends BaseEntity {

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    @Column(name = "entity_name", length = 50)
    private String entityName;

    @Column(name = "entity_id")
    private Long entityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
