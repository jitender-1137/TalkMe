package com.neo.chat.exception;

import java.io.Serial;

/**
 * Signals that the request is unauthenticated or carries invalid credentials.
 * Maps to HTTP 401; defaults to message code {@code TM_105} when none is supplied.
 */
public class UnauthorizedException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String message, String messageCode) {
        super(401, message, messageCode);
    }

}
