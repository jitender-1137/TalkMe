package com.chat.talkMe.exception;

public class ForbiddenException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public ForbiddenException(String message, String messageCode) {
        super(403, message, messageCode);
    }

    public ForbiddenException(String message) {
        super(403, message, "TM_103");
    }
}
