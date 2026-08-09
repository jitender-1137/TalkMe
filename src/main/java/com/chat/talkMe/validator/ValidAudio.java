package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a filename/URL {@code String} ends in a supported audio
 * extension (mp3, ogg, oga, wav, m4a, opus, aac, webm, weba, mp4). A {@code null} value is treated
 * as valid. Default message: "Invalid audio format. Supported formats: mp3, ogg, wav, m4a, opus, aac".
 *
 * @see AudioValidator
 */
@Documented
@Constraint(validatedBy = AudioValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAudio {
    String message() default "Invalid audio format. Supported formats: mp3, ogg, wav, m4a, opus, aac";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
