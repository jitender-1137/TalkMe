package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "devices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Device extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_token", nullable = false, unique = true, length = 255)
    private String deviceToken;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "os_version", length = 50)
    private String osVersion;
}
