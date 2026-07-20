package com.chat.talkMe.moderation;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Client for the free self-hosted NSFW classifier sidecar. Returns:
 *  - Optional.of(true)  → NSFW
 *  - Optional.of(false) → clean
 *  - Optional.empty()   → could not classify (service down / error) → caller's fail policy
 */
public interface NsfwClient {
    Optional<Boolean> classify(Path storedFile, boolean isVideo);
}
