package com.neo.chat.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting an {@code Integer} age is within the accepted adult range,
 * i.e. between 18 and 99 inclusive. A {@code null} value is treated as valid (delegated to a
 * separate {@code @NotNull} where required). Default message: "Age must be between 18 and 99".
 *
 * @see AgeValidator
 */
@Documented
@Constraint(validatedBy = AgeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAge {
    String message() default "Age must be between 18 and 99";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
