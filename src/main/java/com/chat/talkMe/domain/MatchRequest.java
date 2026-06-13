package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "match_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "filter_gender", length = 20)
    private String filterGender;

    @Column(name = "filter_age_min")
    private Integer filterAgeMin;

    @Column(name = "filter_age_max")
    private Integer filterAgeMax;

    @Column(name = "filter_region", length = 50)
    private String filterRegion;

    @Column(name = "filter_interests", length = 255)
    private String filterInterests;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "WAITING"; // WAITING, MATCHED, EXPIRED, CANCELLED
}
