package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single advice reply as seen by any client (feature ADVICE_ROOMS).
 *
 * <p>ANONYMITY INVARIANT: this DTO carries NO author fields of any kind. The replier is stored on
 * the entity for moderation only and is never disclosed to any viewer. The only author-derived
 * signal is the viewer-relative boolean {@code mine} — true when the requesting viewer is the
 * author — which lets a client offer "delete" on the viewer's own replies WITHOUT revealing who
 * any author is.
 *
 * <p>{@code parentReplyUuid} is the uuid of the reply this one answers (single-level threading),
 * or null for a top-level reply — the client threads by matching it against each reply's uuid.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdviceReplyResponse {

    private String uuid;
    private String body;

    /**
     * Uuid of the parent reply (threading), or null for a top-level reply.
     */
    private String parentReplyUuid;

    private String createdAt;

    /**
     * Viewer-relative: true when the requesting viewer authored this reply (enables author-only
     * delete in the client). Never discloses any other author's identity.
     */
    private boolean mine;
}
