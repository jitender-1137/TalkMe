package com.neo.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A shared Bucket List (feature #18, BUCKET_LIST) owned by exactly one chat — a
 * couple/pair keeps a list of things to do together and checks them off.
 * <p>
 * The list row is a lightweight container; the actual entries live in
 * {@link BucketListItem}. Exactly one list exists per chat (enforced by the
 * UNIQUE constraint on {@code chat_uuid}) and it is created lazily on first use.
 */
@Entity
@Table(name = "bucket_lists")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BucketList extends BaseEntity {

    /**
     * UUID (as String) of the chat this list belongs to. One list per chat.
     */
    @Column(name = "chat_uuid", nullable = false, unique = true, length = 64)
    private String chatUuid;
}
