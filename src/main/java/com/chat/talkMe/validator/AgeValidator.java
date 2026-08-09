package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidAge}; accepts ages in the
 * inclusive range 18 to 99.
 */
public class AgeValidator implements ConstraintValidator<ValidAge, Integer> {
    /**
     * Validates that the age is within the allowed adult range. Treats {@code null} as valid so a
     * separate {@code @NotNull} can own presence checking.
     *
     * @param age     the age to check; {@code null} is considered valid
     * @param context the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} if {@code age} is {@code null} or between 18 and 99 inclusive
     */
    @Override
    public boolean isValid(Integer age, ConstraintValidatorContext context) {
        if (age == null) return true; // Let NotNull handle null check if required
        return age >= 18 && age <= 99;
    }
}
