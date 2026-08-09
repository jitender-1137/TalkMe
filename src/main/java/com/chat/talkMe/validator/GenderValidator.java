package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidGender}; accepts "male" or
 * "female" case-insensitively.
 */
public class GenderValidator implements ConstraintValidator<ValidGender, String> {
    private static final Set<String> ALLOWED_GENDERS = Set.of("male", "female");

    /**
     * Validates that the gender is an allowed value, compared case-insensitively. Treats {@code null}
     * as valid.
     *
     * @param gender  the gender string to check; {@code null} is considered valid
     * @param context the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} if {@code gender} is {@code null} or equals "male"/"female" ignoring case
     */
    @Override
    public boolean isValid(String gender, ConstraintValidatorContext context) {
        if (gender == null) return true;
        return ALLOWED_GENDERS.contains(gender.toLowerCase());
    }
}
