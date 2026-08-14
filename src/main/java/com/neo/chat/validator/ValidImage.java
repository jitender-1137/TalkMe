package com.neo.chat.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a filename/URL {@code String} ends in a supported image
 * extension (jpg, jpeg, png, webp, GIF, heic). A {@code null} value is treated as valid. Default
 * message: "Invalid image format. Supported formats: jpg, jpeg, png, webp, GIF, heic".
 *
 * @see ImageValidator
 */
@Documented
@Constraint(validatedBy = ImageValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidImage {
    String message() default "Invalid image format. Supported formats: jpg, jpeg, png, webp, GIF, heic";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
