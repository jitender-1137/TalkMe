package com.neo.chat.dto.request;

import com.neo.chat.enums.TalkNowIntent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /talk-now/available} (declare availability) and {@code POST /talk-now/match}
 * (Talk Now — intent + availability matching).
 *
 * <p>{@link #intent} carries WHY the caller wants to talk. It is intentionally <b>not</b>
 * bean-validated here: a missing/null intent is rejected inside {@code TalkNowService} with a
 * domain error (TM_936) so the failure is uniform whether it arrives via the DTO or a direct
 * service call. {@link #language} and {@link #country} are optional refinements (free-form
 * ISO-ish strings) used to bias matching and to render availability cards.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeclareAvailableRequest {

    /**
     * Why the caller wants to talk. Required (validated in the service, TM_936 when null).
     */
    private TalkNowIntent intent;

    /**
     * Optional preferred conversation language (e.g. "en", "hi").
     */
    private String language;

    /**
     * Optional country hint (e.g. "IN", "US") — used for "meet another country" style intents.
     */
    private String country;
}
