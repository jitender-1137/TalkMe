package com.neo.chat.dto.response;

import com.neo.chat.enums.TalkNowIntent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code POST /talk-now/match} (Talk Now).
 *
 * <p>Two shapes:
 * <ul>
 *   <li><b>Matched</b> ({@code matched=true}, {@code waiting=false}): a compatible available user
 *       was found. The {@code partner*} fields carry their public info so the client can open a
 *       normal 1:1 chat, plus the viewer-relative {@link #compatibility}.</li>
 *   <li><b>Waiting</b> ({@code matched=false}, {@code waiting=true}): nobody compatible was
 *       available, so the caller was themselves marked available with {@link #intent} and should
 *       wait to be matched. All {@code partner*} fields are null.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TalkNowMatchResponse {

    /**
     * True when a partner was found and their identity is disclosed.
     */
    private boolean matched;

    /**
     * True when no partner was available and the caller was enqueued as available.
     */
    private boolean waiting;

    /**
     * The intent this match/wait is for.
     */
    private TalkNowIntent intent;

    // ── Partner card (populated only when matched) ──
    private String partnerUuid;
    private String partnerUsername;
    private String partnerName;
    private String partnerAvatar;
    private String partnerCountry;
    private String partnerLanguage;

    /**
     * The partner's declared intent (may differ from {@link #intent} when a compatible — not
     * identical — intent was matched).
     */
    private TalkNowIntent partnerIntent;

    /**
     * Viewer-relative compatibility with the partner; null when waiting.
     */
    private CompatibilityScore compatibility;

    /**
     * Human-readable status copy.
     */
    private String message;
}
