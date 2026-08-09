package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a media-category {@code String} is one of the allowed
 * categories ("image", "video", "audio", "document"), compared case-insensitively. A {@code null}
 * value is treated as invalid. Default message: "Invalid file media type".
 *
 * @see MediaTypeValidator
 */
@Documented
@Constraint(validatedBy = MediaTypeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMediaType {
    String message() default "Invalid file media type";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
