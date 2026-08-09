package com.chat.talkMe.exception;

import java.io.Serial;

/**
 * Signals that the authenticated caller lacks permission for the requested action.
 * Maps to HTTP 403; defaults to message code {@code TM_103} when none is supplied.
 */
public class ForbiddenException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message, String messageCode) {
        super(403, message, messageCode);
    }

    public ForbiddenException(String message) {
        super(403, message, "TM_103");
    }
}
