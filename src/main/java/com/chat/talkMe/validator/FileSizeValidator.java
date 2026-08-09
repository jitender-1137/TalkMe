package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link jakarta.validation.ConstraintValidator} backing {@link ValidFileSize}; accepts byte sizes
 * not exceeding the {@code max} configured on the annotation.
 */
public class FileSizeValidator implements ConstraintValidator<ValidFileSize, Long> {
    private long max;

    /**
     * Captures the maximum allowed byte size from the annotation instance.
     *
     * @param constraintAnnotation the {@link ValidFileSize} instance whose {@code max()} is stored
     */
    @Override
    public void initialize(ValidFileSize constraintAnnotation) {
        this.max = constraintAnnotation.max();
    }

    /**
     * Validates that the byte size does not exceed the configured maximum. Treats {@code null} as valid.
     *
     * @param size    the byte size to check; {@code null} is considered valid
     * @param context the {@code jakarta.validation.ConstraintValidatorContext} (unused)
     * @return {@code true} if {@code size} is {@code null} or less than or equal to the configured max
     */
    @Override
    public boolean isValid(Long size, ConstraintValidatorContext context) {
        if (size == null) return true;
        return size <= max;
    }
}
