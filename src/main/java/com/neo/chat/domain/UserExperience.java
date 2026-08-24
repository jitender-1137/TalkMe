package com.neo.chat.domain;

import com.neo.chat.enums.ExperienceCategory;
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
 * A real-life experience a user tags on themselves for the Human Knowledge Network —
 * "ask someone who has done it". Examples: "Moved to Canada", "Java developer",
 * "Studied abroad", "Visited Japan".
 *
 * <p>Others search these tags (case-insensitive) to find and message people who have
 * actually done the thing they are curious about. Each row is owned by exactly one
 * {@link #user}; a user may not tag the same {@code tag} twice (enforced by the
 * {@code (user_id, tag)} unique constraint). {@link #openToQuestions} lets an owner opt a
 * tag out of the searchable pool without deleting it.
 */
@Entity
@Table(
        name = "user_experiences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_experience_user_tag",
                columnNames = {"user_id", "tag"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserExperience extends BaseEntity {

    /**
     * The user who has this experience — the only party allowed to edit it.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Free-text experience label, e.g. "Moved to Canada". Unique per user.
     */
    @Column(name = "tag", nullable = false, length = 80)
    private String tag;

    /**
     * Broad bucket the tag belongs to (drives category filtering in search).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    @ColumnDefault("'OTHER'")
    @Builder.Default
    private ExperienceCategory category = ExperienceCategory.OTHER;

    /**
     * Optional short context the owner adds, e.g. "Did it in 2021 on express entry".
     */
    @Column(name = "note", length = 280)
    private String note;

    /**
     * Whether the owner is currently open to being asked about this experience. When
     * {@code false} the tag is hidden from the searchable pool (but kept on the profile).
     */
    @Column(name = "open_to_questions", nullable = false)
    @ColumnDefault("true")
    @Builder.Default
    private boolean openToQuestions = true;
}
