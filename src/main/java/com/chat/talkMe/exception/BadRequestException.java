package com.chat.talkMe.exception;

/**
 * Signals a malformed or invalid client request. Maps to HTTP 400 and carries the
 * caller-supplied message code, rendered by {@code GlobalExceptionHandler.handleServiceException}.
 */
public class BadRequestException extends ServiceException {
    private static final long serialVersionUID = 1L;

    public BadRequestException(String message, String messageCode) {
        super(400, message, messageCode);
    }
}
