package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single advice question as seen by any client (feature ADVICE_ROOMS).
 *
 * <p>ANONYMITY INVARIANT: this DTO carries NO author fields of any kind. The asker is stored on
 * the entity for moderation only and is never disclosed to any viewer. The only author-derived
 * signal is the viewer-relative boolean {@code mine} — true when the requesting viewer is the
 * author — which lets a client offer "delete" on the viewer's own posts WITHOUT revealing who any
 * author is (it says nothing about anyone but the viewer themselves).
 *
 * <p>{@code disclaimer} is non-null for the sensitive categories (CAREER, RELATIONSHIPS, FINANCE,
 * HEALTH, BUSINESS): "Peer opinions, not professional advice".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdviceQuestionResponse {

    private String uuid;
    private String title;
    private String body;
    private String category;

    /**
     * Peer-opinion disclaimer for sensitive categories; null otherwise.
     */
    private String disclaimer;

    private int replyCount;
    private String createdAt;

    /**
     * Viewer-relative: true when the requesting viewer authored this question (enables author-only
     * delete in the client). Never discloses any other author's identity.
     */
    private boolean mine;
}
