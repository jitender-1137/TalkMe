package com.neo.chat.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a filename/URL {@code String} ends in a supported video
 * extension (mp4, mov, avi, webm). A {@code null} value is treated as valid. Default message:
 * "Invalid video format. Supported formats: mp4, mov, avi, webm".
 *
 * @see VideoValidator
 */
@Documented
@Constraint(validatedBy = VideoValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidVideo {
    String message() default "Invalid video format. Supported formats: mp4, mov, avi, webm";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
