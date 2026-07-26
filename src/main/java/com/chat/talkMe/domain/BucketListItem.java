package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A single entry in a shared {@link BucketList} (feature #18, BUCKET_LIST).
 * Either member of the pair can add, toggle (check off / re-open) or remove an
 * entry; every mutation is broadcast live over WS to the owning chat.
 */
@Entity
@Table(name = "bucket_list_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BucketListItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bucket_list_id", nullable = false)
    private BucketList bucketList;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    @Column(name = "completed", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean completed = false;

    /** User who last checked the item off; null while the item is open. */
    @Column(name = "completed_by_user_id")
    private Long completedByUserId;

    /** When the item was last checked off; null while the item is open. */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "order_index", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int orderIndex = 0;
}
