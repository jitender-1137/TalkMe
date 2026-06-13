package com.chat.talkMe.exception;

import com.chat.talkMe.dto.response.ResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    private String getLocalizedMessage(String code, String defaultMessage) {
        try {
            return messageSource.getMessage(code, null, defaultMessage, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return defaultMessage;
        }
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ResponseDto<Void>> handleServiceException(ServiceException ex) {
        log.error("ServiceException occurred: [Code: {}] {}", ex.getMessageCode(), ex.getMessage());
        String localizedMessage = getLocalizedMessage(ex.getMessageCode(), ex.getMessage());
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, ex.getMessageCode(), ex.getErrors());
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDto<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        log.error("Validation error occurred");
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        String localizedMessage = getLocalizedMessage("VE_101", "Validation Failed");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "VE_101", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseDto<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        log.error("AccessDeniedException: {}", ex.getMessage());
        String localizedMessage = getLocalizedMessage("TM_005", "Access Denied");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "TM_005");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDto<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.error("IllegalArgumentException occurred: {}", ex.getMessage());
        String messageCode = "TM_071";
        String defaultMsg = ex.getMessage();
        if (ex.getMessage() != null && ex.getMessage().contains("Invalid UUID string")) {
            messageCode = "TM_INVALID_UUID";
            defaultMsg = "Invalid ID format provided";
        }
        String localizedMessage = getLocalizedMessage(messageCode, defaultMsg);
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, messageCode);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto<Void>> handleAllExceptions(Exception ex) {
        log.error("Unhandled Exception occurred", ex);
        String localizedMessage = getLocalizedMessage("TM_002", "Internal Server Error");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "TM_002");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
