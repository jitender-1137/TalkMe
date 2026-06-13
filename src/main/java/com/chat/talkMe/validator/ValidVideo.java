package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = VideoValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidVideo {
    String message() default "Invalid video format. Supported formats: mp4, mov, avi, webm";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
