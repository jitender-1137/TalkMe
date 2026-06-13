package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = FileSizeValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFileSize {
    String message() default "File size exceeds the allowed limit";
    long max() default 104857600L; // 100 MB
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
