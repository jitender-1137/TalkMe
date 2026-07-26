package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A shared Bucket List (feature #18, BUCKET_LIST) owned by exactly one chat — a
 * couple/pair keeps a list of things to do together and checks them off.
 *
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

    /** UUID (as String) of the chat this list belongs to. One list per chat. */
    @Column(name = "chat_uuid", nullable = false, unique = true, length = 64)
    private String chatUuid;
}
