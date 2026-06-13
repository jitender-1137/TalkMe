package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class UsernameValidator implements ConstraintValidator<ValidUsername, String> {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,30}$");

    @Override
    public boolean isValid(String username, ConstraintValidatorContext context) {
        if (username == null) return false;
        return USERNAME_PATTERN.matcher(username).matches();
    }
}
