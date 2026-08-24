package com.neo.chat.domain;

import com.neo.chat.enums.SkillDirection;
import com.neo.chat.enums.SkillLevel;
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
import org.hibernate.annotations.ColumnDefault;

/**
 * A single free-text skill a user either offers to teach or wants to learn
 * (feature SKILL_EXCHANGE). Owned join entity keyed by user id — the User aggregate
 * is intentionally NOT modified.
 *
 * <p>The {@code (user_id, name, direction)} unique constraint keeps each skill idempotent
 * per direction: a user may both {@code OFFER} and {@code WANT} the same skill name, but not
 * list it twice in the same direction.
 */
@Entity
@Table(
        name = "user_skills",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_skill_user_name_direction",
                columnNames = {"user_id", "name", "direction"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSkill extends BaseEntity {

    /**
     * The owner of this skill row.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Free-text skill name (e.g. "JavaScript", "Hindi"). Case-insensitive for matching.
     */
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /**
     * Whether the user offers to teach this skill or wants to learn it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    @ColumnDefault("'OFFER'")
    @Builder.Default
    private SkillDirection direction = SkillDirection.OFFER;

    /**
     * Optional self-declared proficiency; advisory only, never gates a match.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "level", length = 20)
    private SkillLevel level;
}
