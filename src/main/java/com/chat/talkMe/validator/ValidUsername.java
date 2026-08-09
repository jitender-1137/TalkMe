package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a username {@code String} is non-{@code null} and matches
 * {@code ^[a-zA-Z0-9_]{3,30}$}, i.e. 3 to 30 characters of letters, digits and underscores only
 * (no spaces). Default message: "Username must be 3-30 characters long, alphanumeric and
 * underscores only, and contain no spaces".
 *
 * @see UsernameValidator
 */
@Documented
@Constraint(validatedBy = UsernameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidUsername {
    String message() default "Username must be 3-30 characters long, alphanumeric and underscores only, and contain no spaces";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
