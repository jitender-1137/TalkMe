package com.chat.talkMe.exception;

public class NotFoundException extends ServiceException {
    public NotFoundException(String message, String messageCode) {
        super(404, message, messageCode);
    }
    
    public NotFoundException(String message) {
        super(404, message, "TM_101");
    }
}
