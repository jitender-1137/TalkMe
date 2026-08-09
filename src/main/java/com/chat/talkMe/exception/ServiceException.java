package com.chat.talkMe.exception;

import lombok.Getter;

import java.io.Serial;

/**
 * Base runtime exception for all application-level errors. Carries the HTTP {@code status}
 * to return, a {@code messageCode} (TM_/VE_ code resolved to a localized message) and an
 * optional {@code errors} payload (e.g. per-field validation details). Subclasses fix the
 * status/code for a specific condition; {@code GlobalExceptionHandler} translates it into a
 * {@code ResponseDto} error response.
 */
@Getter
public class ServiceException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String messageCode;
    private final Object errors;

    public ServiceException(int status, String message, String messageCode, Object errors) {
        super(message);
        this.status = status;
        this.messageCode = messageCode;
        this.errors = errors;
    }

    public ServiceException(int status, String message, String messageCode) {
        this(status, message, messageCode, null);
    }

    public ServiceException(int status, String messageCode) {
        this(status, null, messageCode, null);
    }
}
