package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class GenderValidator implements ConstraintValidator<ValidGender, String> {
    private static final Set<String> ALLOWED_GENDERS = Set.of("male", "female");

    @Override
    public boolean isValid(String gender, ConstraintValidatorContext context) {
        if (gender == null) return true;
        return ALLOWED_GENDERS.contains(gender.toLowerCase());
    }
}
