package com.neo.chat.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidVideo}; accepts filenames/URLs
 * ending in a supported video extension.
 */
public class VideoValidator implements ConstraintValidator<ValidVideo, String> {
    private static final Set<String> EXTENSIONS = Set.of("mp4", "mov", "avi", "webm");

    /**
     * Validates that the filename/URL carries a supported video extension. Treats {@code null} as
     * valid; a value with no {@code '.'} extension is rejected.
     *
     * @param filename the filename or URL to check; {@code null} is considered valid
     * @param context  the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} if {@code filename} is {@code null} or its lowercased extension is supported
     */
    @Override
    public boolean isValid(String filename, ConstraintValidatorContext context) {
        if (filename == null) return true;
        int idx = filename.lastIndexOf('.');
        if (idx == -1) return false;
        String ext = filename.substring(idx + 1).toLowerCase();
        return EXTENSIONS.contains(ext);
    }
}
