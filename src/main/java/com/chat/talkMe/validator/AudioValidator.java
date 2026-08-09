package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidAudio}; accepts filenames/URLs
 * ending in a supported audio container extension. Container types produced by browser
 * MediaRecorder (audio/webm, audio/mp4) are intentionally included.
 */
public class AudioValidator implements ConstraintValidator<ValidAudio, String> {
    // Includes the containers browser MediaRecorder produces for a voice note: Chrome/Firefox emit
    // audio/webm (.webm), Safari emits audio/mp4 (.mp4/.m4a). These are legitimate audio here — the
    // upload's magic-byte check (UploadValidator) already confirmed it's a real media container.
    private static final Set<String> EXTENSIONS =
            Set.of("mp3", "ogg", "oga", "wav", "m4a", "opus", "aac", "webm", "weba", "mp4");

    /**
     * Validates that the filename/URL carries a supported audio extension. Treats {@code null} as valid.
     *
     * @param filename the filename or URL to check; {@code null} is considered valid
     * @param context  the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} if {@code filename} is {@code null} or ends in a supported audio extension
     */
    @Override
    public boolean isValid(String filename, ConstraintValidatorContext context) {
        if (filename == null) return true;
        return hasAudioExtension(filename);
    }

    /**
     * Whether a URL/filename ends in a supported audio extension. Reusable outside bean validation.
     *
     * @param filename the filename or URL to inspect
     * @return {@code true} if non-{@code null} and its lowercased extension is a supported audio type;
     * {@code false} if {@code null} or it has no {@code '.'} extension
     */
    public static boolean hasAudioExtension(String filename) {
        if (filename == null) return false;
        int idx = filename.lastIndexOf('.');
        if (idx == -1) return false;
        String ext = filename.substring(idx + 1).toLowerCase();
        return EXTENSIONS.contains(ext);
    }
}
