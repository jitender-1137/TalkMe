package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidUsername}; accepts usernames
 * matching {@code ^[a-zA-Z0-9_]{3,30}$} (3-30 letters, digits and underscores, no spaces).
 */
public class UsernameValidator implements ConstraintValidator<ValidUsername, String> {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,30}$");

    /**
     * Validates that the username matches the allowed pattern.
     *
     * @param username the username to check; {@code null} is considered invalid
     * @param context  the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} only if {@code username} is non-{@code null} and matches the username pattern
     */
    @Override
    public boolean isValid(String username, ConstraintValidatorContext context) {
        if (username == null) return false;
        return USERNAME_PATTERN.matcher(username).matches();
    }
}
