package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidMessageContent}; accepts
 * non-blank content of at most 4096 characters (measured after trimming).
 */
public class MessageContentValidator implements ConstraintValidator<ValidMessageContent, String> {
    /**
     * Validates that the content is non-{@code null}, non-empty after trimming, and no longer than
     * 4096 characters (length measured on the trimmed value).
     *
     * @param content the message content to check; {@code null} is considered invalid
     * @param context the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} only if the trimmed content is non-empty and at most 4096 characters long
     */
    @Override
    public boolean isValid(String content, ConstraintValidatorContext context) {
        if (content == null) return false;
        String trimmed = content.trim();
        return !trimmed.isEmpty() && trimmed.length() <= 4096;
    }
}
