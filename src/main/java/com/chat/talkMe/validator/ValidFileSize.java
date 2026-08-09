package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a {@code Long} byte size does not exceed the configured
 * {@link #max()} limit (default 104857600, i.e. 100 MB). A {@code null} value is treated as valid.
 * Default message: "File size exceeds the allowed limit".
 *
 * @see FileSizeValidator
 */
@Documented
@Constraint(validatedBy = FileSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFileSize {
    String message() default "File size exceeds the allowed limit";

    long max() default 104857600L; // 100 MB

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
