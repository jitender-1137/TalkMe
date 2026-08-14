package com.neo.chat.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a password {@code String} is non-{@code null}, 6 to 128
 * characters long, and contains at least one letter and at least one digit. Default message:
 * "Password must be 6-128 characters long and contain at least one letter and one digit".
 *
 * @see PasswordValidator
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {
    String message() default "Password must be 6-128 characters long and contain at least one letter and one digit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
