package com.chat.talkMe.domain;

import com.chat.talkMe.enums.InstallationType;
import jakarta.persistence.*;
import lombok.*;

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

    /** Push service endpoint URL (unique per subscription). */
    @Column(name = "endpoint", nullable = false, unique = true, length = 1024)
    private String endpoint;

    /** Client public key (base64url). */
    @Column(name = "p256dh", nullable = false, length = 255)
    private String p256dh;

    /** Auth secret (base64url). */
    @Column(name = "auth_key", nullable = false, length = 255)
    private String auth;

    @Enumerated(EnumType.STRING)
    @Column(name = "installation_type", length = 20)
    private InstallationType installationType;
}
