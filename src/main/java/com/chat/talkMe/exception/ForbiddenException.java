package com.chat.talkMe.exception;

public class ForbiddenException extends ServiceException {
    public ForbiddenException(String message, String messageCode) {
        super(403, message, messageCode);
    }
    
    public ForbiddenException(String message) {
        super(403, message, "TM_103");
    }
}
