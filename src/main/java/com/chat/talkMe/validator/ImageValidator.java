package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class ImageValidator implements ConstraintValidator<ValidImage, String> {
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif", "heic");

    @Override
    public boolean isValid(String filename, ConstraintValidatorContext context) {
        if (filename == null) return true;
        int idx = filename.lastIndexOf('.');
        if (idx == -1) return false;
        String ext = filename.substring(idx + 1).toLowerCase();
        return EXTENSIONS.contains(ext);
    }
}
