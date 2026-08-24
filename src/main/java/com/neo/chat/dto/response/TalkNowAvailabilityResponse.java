package com.neo.chat.dto.response;

import com.neo.chat.enums.TalkNowIntent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of who is available to talk right now (Talk Now), from the viewer's perspective.
 *
 * <p>{@link #countsByIntent} tallies the available users grouped by intent (keyed by the enum
 * name); {@link #available} is a capped, compatibility-ranked list of cards. {@link #myIntent}
 * echoes the viewer's own currently-declared intent (null when the viewer is not available), and
 * {@link #declared} is {@code true} while the viewer themselves is marked available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TalkNowAvailabilityResponse {

    /**
     * Available-user count per intent (key = {@link TalkNowIntent#name()}), viewer excluded.
     */
    private Map<String, Integer> countsByIntent;

    /**
     * Total available users (viewer excluded).
     */
    private int total;

    /**
     * Capped, compatibility-ranked list of available users (viewer excluded).
     */
    private List<TalkNowCardResponse> available;

    /**
     * The viewer's own currently-declared intent, or null if they are not available.
     */
    private TalkNowIntent myIntent;

    /**
     * True while the viewer is themselves marked available.
     */
    private boolean declared;
}
