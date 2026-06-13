package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class MediaTypeValidator implements ConstraintValidator<ValidMediaType, String> {
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("image", "video", "audio", "document");

    @Override
    public boolean isValid(String type, ConstraintValidatorContext context) {
        if (type == null) return false;
        return ALLOWED_CATEGORIES.contains(type.toLowerCase());
    }
}
