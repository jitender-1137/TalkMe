package com.chat.talkMe.domain;

import com.chat.talkMe.enums.MediaContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * An admin-only ownership record written for EVERY upload, keyed by its storage key.
 *
 * <p>Stranger and lobby chats are real-time STOMP relays — they never create a
 * {@code Message}/{@code MessageAttachment} row — so their uploaded files are "born
 * orphan" and the admin storage gallery has no way to attribute them to a user.
 * (Stranger media is stored under a flat {@code strangers/&lt;random&gt;} path that
 * deliberately carries no owner id.) This table is the durable server-side link from a
 * physical object back to the authenticated uploader and the context it was sent in.
 *
 * <p>Privacy note: the peer in a stranger chat still never learns the uploader's
 * identity — this record is readable only through the SUPER_ADMIN storage view. It does
 * mean the anonymous-upload → real-user link now EXISTS at rest; that is an intentional,
 * admin-scoped trade-off for moderation traceability.
 */
@Entity
@Table(
        name = "media_assets",
        indexes = {
                @Index(name = "idx_media_assets_storage_key", columnList = "storage_key", unique = true),
                @Index(name = "idx_media_assets_owner", columnList = "owner_id"),
                @Index(name = "idx_media_assets_context", columnList = "context")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAsset extends BaseEntity {

    /**
     * Object key under the media root, e.g. {@code strangers/<uuid>.jpg}. Uniqueness is
     * enforced by the named unique index on the {@code @Table} (not a second @Column
     * constraint — that would create a redundant duplicate index under ddl-auto).
     */
    @Column(name = "storage_key", nullable = false, columnDefinition = "TEXT")
    private String storageKey;

    /**
     * Full stored reference ({@code <mediaRoot>/<key>}) as returned by the backend.
     */
    @Column(name = "reference", nullable = false, columnDefinition = "TEXT")
    private String reference;

    /**
     * The authenticated uploader. Nullable only defensively (should always be set).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    /**
     * Where the file was uploaded — mirrors the storage folder.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "context", length = 20, nullable = false)
    private MediaContext context;

    /**
     * For CONVERSATION uploads, the (validated) chat UUID; null otherwise.
     */
    @Column(name = "context_id", length = 64)
    private String contextId;

    /**
     * The upload {@code type} param (image / video / audio / file).
     */
    @Column(name = "upload_type", length = 32)
    private String uploadType;

    /**
     * Client-reported original filename, for admin display.
     */
    @Column(name = "original_file_name", length = 512)
    private String originalFileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;
}
