package com.chat.talkMe.moderation;

import com.chat.talkMe.enums.MessageType;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * Free, self-hosted content moderation. Text is checked against curated multilingual
 * word-lists (no external API); media is delegated to a self-hosted NSFW classifier.
 */
public interface ContentModerationService {

    /** Classify free text (English + Hindi/Hinglish) as vulgar/abusive/sexual. */
    ModerationResult moderateText(String content);

    /**
     * Classify a stored image/video file as NSFW. Implementations may delegate to a
     * self-hosted NSFW microservice. Returns CLEAN for non-media types.
     */
    ModerationResult moderateMedia(Path storedFile, MessageType type);

    /**
     * Classify an in-flight upload (image/video) BEFORE it is persisted, by inspecting
     * its bytes directly — so callers don't depend on resolving a stored URL back to a
     * filesystem path. Returns CLEAN for non-media uploads or when disabled. Fail-open
     * if the classifier is unavailable.
     */
    ModerationResult moderateUpload(MultipartFile file);

    /** Whether moderation is enabled at all (config-gated kill switch). */
    boolean isEnabled();
}
