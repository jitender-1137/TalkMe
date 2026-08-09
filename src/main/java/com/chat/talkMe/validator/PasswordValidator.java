package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidPassword}; accepts passwords
 * 6 to 128 characters long that contain at least one letter and at least one digit.
 */
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {
    /**
     * Validates the password length (6 to 128 inclusive) and that it contains at least one letter
     * and one digit; returns as soon as both character classes are seen.
     *
     * @param password the password to check; {@code null} is considered invalid
     * @param context  the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} only if length is 6-128, and it holds at least one letter and one digit
     */
    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) return false;
        if (password.length() < 6 || password.length() > 128) return false;

        boolean hasLetter = false;
        boolean hasDigit = false;

        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
            if (hasLetter && hasDigit) return true;
        }
        return false;
    }
}
