package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = AudioValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAudio {
    String message() default "Invalid audio format. Supported formats: mp3, ogg, wav, m4a, opus, aac";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
