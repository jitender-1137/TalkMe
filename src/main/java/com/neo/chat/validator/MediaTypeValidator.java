package com.neo.chat.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidMediaType}; accepts one of the
 * media categories "image", "video", "audio" or "document" case-insensitively.
 */
public class MediaTypeValidator implements ConstraintValidator<ValidMediaType, String> {
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("image", "video", "audio", "document");

    /**
     * Validates that the media type is an allowed category, compared case-insensitively. Unlike the
     * other validators, {@code null} is treated as invalid.
     *
     * @param type    the media category to check; {@code null} is considered invalid
     * @param context the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} only if {@code type} is non-{@code null} and matches an allowed category
     */
    @Override
    public boolean isValid(String type, ConstraintValidatorContext context) {
        if (type == null) return false;
        return ALLOWED_CATEGORIES.contains(type.toLowerCase());
    }
}
