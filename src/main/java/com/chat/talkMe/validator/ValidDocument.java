package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = DocumentValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDocument {
    String message() default "Invalid document format. Supported formats: pdf, doc, docx, xls, xlsx, ppt, pptx, txt, zip";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
