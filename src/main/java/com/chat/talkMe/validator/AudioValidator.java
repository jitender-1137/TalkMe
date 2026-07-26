package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class AudioValidator implements ConstraintValidator<ValidAudio, String> {
    // Includes the containers browser MediaRecorder produces for a voice note: Chrome/Firefox emit
    // audio/webm (.webm), Safari emits audio/mp4 (.mp4/.m4a). These are legitimate audio here — the
    // upload's magic-byte check (UploadValidator) already confirmed it's a real media container.
    private static final Set<String> EXTENSIONS =
            Set.of("mp3", "ogg", "oga", "wav", "m4a", "opus", "aac", "webm", "weba", "mp4");

    @Override
    public boolean isValid(String filename, ConstraintValidatorContext context) {
        if (filename == null) return true;
        return hasAudioExtension(filename);
    }

    /** Whether a URL/filename ends in a supported audio extension. Reusable outside bean validation. */
    public static boolean hasAudioExtension(String filename) {
        if (filename == null) return false;
        int idx = filename.lastIndexOf('.');
        if (idx == -1) return false;
        String ext = filename.substring(idx + 1).toLowerCase();
        return EXTENSIONS.contains(ext);
    }
}
