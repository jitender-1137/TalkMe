package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = ImageValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidImage {
    String message() default "Invalid image format. Supported formats: jpg, jpeg, png, webp, gif, heic";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
