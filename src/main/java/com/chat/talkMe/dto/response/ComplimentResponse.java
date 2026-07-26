package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single anonymous compliment as seen by a client (feature ANON_COMPLIMENTS).
 *
 * <p>SECRECY INVARIANT: {@code senderName}/{@code senderUsername}/{@code senderAvatar} are
 * populated ONLY when {@code status == REVEALED}. For every other status they MUST be null —
 * the recipient must never learn who sent an un-revealed compliment.
 *
 * <p>The {@code recipient*} fields are the mirror image, used only by the caller's own
 * "sent" view ({@code fromMe == true}); the recipient is not secret to its sender. They are
 * null in the recipient's inbox view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplimentResponse {

    private String uuid;
    private String message;
    /** {@link com.chat.talkMe.enums.ComplimentStatus} name. */
    private String status;
    private String createdAt;
    /** True in the sender's own "sent" listing; false in the recipient's inbox. */
    private boolean fromMe;

    // ── Sender identity — ONLY non-null when status == REVEALED ──────────────────
    private String senderName;
    private String senderUsername;
    private String senderAvatar;

    // ── Recipient card — populated only in the sender's own "sent" view ──────────
    private String recipientName;
    private String recipientUsername;
    private String recipientAvatar;
}
