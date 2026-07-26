package com.chat.talkMe.enums;

/**
 * What medium a {@link com.chat.talkMe.domain.Story} carries (feature #21, Voice Status).
 *
 * <p>{@code VISUAL} is the classic image/video story (the historical default — every existing
 * row backfills to it). {@code VOICE} is an audio-only status: the story's {@code mediaUrl}
 * points at a validated voice clip that the carousel/viewer plays through the shared
 * {@code PostAudioBar}, reusing the same {@code expiresAt} lifecycle.
 */
public enum StoryKind {
    VISUAL,
    VOICE
}
