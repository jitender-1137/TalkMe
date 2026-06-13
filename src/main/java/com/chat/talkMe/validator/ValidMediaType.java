package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = MediaTypeValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMediaType {
    String message() default "Invalid file media type";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
