package com.chat.talkMe.enums;

/**
 * Lifecycle of a {@link com.chat.talkMe.domain.SecretCrush} (feature #9).
 *
 * <ul>
 *   <li>{@code ACTIVE}    — a one-sided, private crush; MUST never be revealed to the target</li>
 *   <li>{@code MATCHED}   — both users crushed on each other; the match is disclosed to both</li>
 *   <li>{@code WITHDRAWN} — the crusher revoked their crush (soft state, re-crushable later)</li>
 * </ul>
 */
public enum SecretCrushStatus {
    ACTIVE,
    MATCHED,
    WITHDRAWN
}
