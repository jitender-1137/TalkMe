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
@Table(name = "match_requests",
        indexes = @Index(name = "idx_match_requests_user_id", columnList = "user_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
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

    @Column(name = "filter_interests")
    private String filterInterests;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "WAITING"; // WAITING, MATCHED, EXPIRED, CANCELLED
}
