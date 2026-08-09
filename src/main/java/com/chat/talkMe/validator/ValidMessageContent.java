package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting message content is a non-{@code null} {@code String} that is
 * non-empty after trimming and at most 4096 characters (measured on the trimmed value). Default
 * message: "Message content must not be empty and cannot exceed 4096 characters".
 *
 * @see MessageContentValidator
 */
@Documented
@Constraint(validatedBy = MessageContentValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMessageContent {
    String message() default "Message content must not be empty and cannot exceed 4096 characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
