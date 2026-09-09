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
@Table(name = "message_attachments",
        indexes = @Index(name = "idx_message_attachments_message_id", columnList = "message_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageAttachment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    // TEXT (not length-capped): these hold AES-GCM ciphertext (base64 iv‖ct‖tag)
    // when per-chat encryption is on, which overflows the old 255/512 caps.
    @Column(name = "file_name", nullable = false, columnDefinition = "TEXT")
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "duration")
    private Double duration;
}
