package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A live room discovery/metadata card (Connect Wave-2): a "third place" topic room
 * ({@code roomMode = TOPIC}) or a language-practice room ({@code roomMode = LANGUAGE_PRACTICE}).
 *
 * <p>For language rooms the two languages are encoded in {@code category} (e.g. {@code "lang:HI>EN"})
 * because the shared {@code Chat} entity carries no language columns. {@code memberCount} is the
 * cached active-member total; {@code liveCount} is the count of members currently online in the
 * room's Redis presence set.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveRoomResponse {

    /**
     * Chat uuid — open/join/enter the room with this.
     */
    private String id;

    private String name;
    private String description;

    /**
     * Free-form discovery category. For language rooms this encodes the language pair,
     * e.g. {@code "lang:HI>EN"}.
     */
    private String category;

    /**
     * TOPIC | LANGUAGE_PRACTICE.
     */
    private String roomMode;

    /**
     * Cached active-member count (0 on any lookup failure).
     */
    private int memberCount;

    /**
     * Members currently online in the room (Redis presence set intersected with global online).
     */
    private int liveCount;

    private Instant createdAt;
}
