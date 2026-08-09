package com.chat.talkMe.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a filename/URL {@code String} ends in a supported document
 * extension (pdf, doc, docx, xls, xlsx, ppt, pptx, txt, zip). A {@code null} value is treated as
 * valid. Default message: "Invalid document format. Supported formats: pdf, doc, docx, xls, xlsx,
 * ppt, pptx, txt, zip".
 *
 * @see DocumentValidator
 */
@Documented
@Constraint(validatedBy = DocumentValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDocument {
    String message() default "Invalid document format. Supported formats: pdf, doc, docx, xls, xlsx, ppt, pptx, txt, zip";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
