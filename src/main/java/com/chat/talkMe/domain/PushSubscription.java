package com.chat.talkMe.domain;

import com.chat.talkMe.enums.InstallationType;
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

/**
 * A W3C Push API subscription belonging to a user's installed PWA instance.
 * One user may have several (multiple devices). Pruned when the push service
 * reports the endpoint is gone (404/410).
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscription extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Push service endpoint URL (unique per subscription).
     */
    @Column(name = "endpoint", nullable = false, unique = true, length = 1024)
    private String endpoint;

    /**
     * Client public key (base64url).
     */
    @Column(name = "p256dh", nullable = false)
    private String p256dh;

    /**
     * Auth secret (base64url).
     */
    @Column(name = "auth_key", nullable = false)
    private String auth;

    @Enumerated(EnumType.STRING)
    @Column(name = "installation_type", length = 20)
    private InstallationType installationType;
}
