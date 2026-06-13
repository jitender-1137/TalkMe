package com.chat.talkMe.exception;

public class BadRequestException extends ServiceException {
    public BadRequestException(String message, String messageCode) {
        super(400, message, messageCode);
    }
}
