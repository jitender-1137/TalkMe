package com.neo.chat.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a gender {@code String} is one of the allowed values
 * ("male" or "female"), compared case-insensitively. A {@code null} value is treated as valid.
 * Default message: "Gender must be male or female".
 *
 * @see GenderValidator
 */
@Documented
@Constraint(validatedBy = GenderValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidGender {
    String message() default "Gender must be male or female";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
