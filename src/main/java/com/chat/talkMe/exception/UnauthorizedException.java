package com.chat.talkMe.exception;

public class UnauthorizedException extends ServiceException {
    public UnauthorizedException(String message, String messageCode) {
        super(401, message, messageCode);
    }
    
    public UnauthorizedException(String message) {
        super(401, message, "TM_105");
    }
}
