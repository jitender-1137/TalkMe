package com.chat.talkMe.exception;

/**
 * Signals that the request is unauthenticated or carries invalid credentials.
 * Maps to HTTP 401; defaults to message code {@code TM_105} when none is supplied.
 */
public class UnauthorizedException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public UnauthorizedException(String message, String messageCode) {
        super(401, message, messageCode);
    }

    public UnauthorizedException(String message) {
        super(401, message, "TM_105");
    }
}
