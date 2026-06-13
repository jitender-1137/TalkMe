package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class FileSizeValidator implements ConstraintValidator<ValidFileSize, Long> {
    private long max;

    @Override
    public void initialize(ValidFileSize constraintAnnotation) {
        this.max = constraintAnnotation.max();
    }

    @Override
    public boolean isValid(Long size, ConstraintValidatorContext context) {
        if (size == null) return true;
        return size <= max;
    }
}
