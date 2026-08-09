package com.chat.talkMe.exception;

/**
 * Signals a state conflict with an existing resource (e.g. duplicate/already-taken value).
 * Maps to HTTP 409 with the caller-supplied message code.
 */
public class ConflictException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public ConflictException(String message, String messageCode) {
        super(409, message, messageCode);
    }

    public ConflictException(String code) {
        super(409, code);
    }
}
