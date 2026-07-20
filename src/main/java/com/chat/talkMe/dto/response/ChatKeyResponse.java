package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * The per-chat symmetric key handed to an authorized participant. {@code enabled}
 * is false (and {@code key} null) when chat encryption is turned off server-side —
 * the client then operates in plaintext passthrough for that chat.
 */
@Getter
@Builder
public class ChatKeyResponse {
    private boolean enabled;
    /** Base64 raw AES-256 key. Null when disabled. Held only in client memory. */
    private String key;
    private String algo;
    private int version;
}
