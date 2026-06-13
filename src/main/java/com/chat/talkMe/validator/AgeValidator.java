package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AgeValidator implements ConstraintValidator<ValidAge, Integer> {
    @Override
    public boolean isValid(Integer age, ConstraintValidatorContext context) {
        if (age == null) return true; // Let NotNull handle null check if required
        return age >= 18 && age <= 99;
    }
}
