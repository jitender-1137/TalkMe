package com.chat.talkMe.exception;

import java.io.Serial;

/**
 * Signals that a requested resource does not exist. Maps to HTTP 404; defaults to
 * message code {@code TM_101} when none is supplied.
 */
public class NotFoundException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message, String messageCode) {
        super(404, message, messageCode);
    }

    public NotFoundException(String message) {
        super(404, message, "TM_101");
    }
}
