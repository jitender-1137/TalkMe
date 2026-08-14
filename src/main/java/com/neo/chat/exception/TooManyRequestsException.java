package com.neo.chat.exception;

import java.io.Serial;

/**
 * HTTP 429 — raised when an account/IP exceeds the failed-login threshold.
 */
public class TooManyRequestsException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public TooManyRequestsException(String message, String messageCode) {
        super(429, message, messageCode);
    }
}
