package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class MessageContentValidator implements ConstraintValidator<ValidMessageContent, String> {
    @Override
    public boolean isValid(String content, ConstraintValidatorContext context) {
        if (content == null) return false;
        String trimmed = content.trim();
        return !trimmed.isEmpty() && trimmed.length() <= 4096;
    }
}
