package com.chat.talkMe.exception;

public class BadRequestException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public BadRequestException(String message, String messageCode) {
        super(400, message, messageCode);
    }
}
