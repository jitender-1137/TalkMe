package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = UsernameValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidUsername {
    String message() default "Username must be 3-30 characters long, alphanumeric and underscores only, and contain no spaces";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
