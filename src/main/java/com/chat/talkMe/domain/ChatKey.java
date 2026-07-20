package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * The per-conversation symmetric data key. One row per chat. The AES-256 key is
 * stored WRAPPED (encrypted) with the application master key — never in the clear —
 * so a leak of this table alone does not expose message plaintext. Unwrapped only
 * in memory at use time (see ChatKeyService).
 *
 * NOTE: this is server-mediated encryption, NOT end-to-end — the server can unwrap
 * and read messages (needed for push/email previews). It protects data at rest.
 */
@Entity
@Table(name = "chat_keys", uniqueConstraints = {
        @UniqueConstraint(name = "uk_chat_keys_chat", columnNames = "chat_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatKey extends BaseEntity {

    @Column(name = "chat_id", nullable = false, unique = true)
    private Long chatId;

    /** AES-256 data key, wrapped by the master key: base64(iv‖ciphertext‖tag). */
    @Column(name = "wrapped_key", nullable = false, columnDefinition = "TEXT")
    private String wrappedKey;

    @Column(name = "algo", nullable = false, length = 32)
    @Builder.Default
    private String algo = "AES-256-GCM";

    @Column(name = "key_version", nullable = false)
    @Builder.Default
    private int keyVersion = 1;
}
