package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = MessageContentValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMessageContent {
    String message() default "Message content must not be empty and cannot exceed 4096 characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
