package com.neo.chat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "blocked_users", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "blocked_id"}),
        indexes = @Index(name = "idx_blocked_users_blocked_id", columnList = "blocked_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockUser extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;
}
