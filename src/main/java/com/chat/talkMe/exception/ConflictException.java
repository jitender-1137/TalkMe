package com.chat.talkMe.exception;

public class ConflictException extends ServiceException {
    public ConflictException(String message, String messageCode) {
        super(409, message, messageCode);
    }

    public ConflictException(String code) {
        super(409, code);
    }
}
