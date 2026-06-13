package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class DocumentValidator implements ConstraintValidator<ValidDocument, String> {
    private static final Set<String> EXTENSIONS = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip");

    @Override
    public boolean isValid(String filename, ConstraintValidatorContext context) {
        if (filename == null) return true;
        int idx = filename.lastIndexOf('.');
        if (idx == -1) return false;
        String ext = filename.substring(idx + 1).toLowerCase();
        return EXTENSIONS.contains(ext);
    }
}
