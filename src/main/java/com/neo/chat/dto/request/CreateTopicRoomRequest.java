package com.neo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for POST /rooms/live/topic (feature TOPIC_ROOMS). Creates a public "third place" topic
 * room ({@code roomMode = TOPIC}). {@code category} is a free-form label — curated buckets like
 * coffee/gaming/study/music/tech/travel/late-night are suggested conceptually but any string is
 * accepted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTopicRoomRequest {

    /**
     * Room name.
     */
    @NotBlank
    @Size(max = 100)
    private String name;

    /**
     * Free-form "third place" category (coffee / gaming / study / music / tech / travel / late-night / …).
     */
    @NotBlank
    @Size(max = 100)
    private String category;

    /**
     * Optional interest tags (enum names of {@code com.neo.chat.enums.Interest}); forwarded to the
     * group-create flow verbatim.
     */
    private List<String> tags;
}
