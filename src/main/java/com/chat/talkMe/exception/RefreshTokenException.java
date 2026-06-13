package com.chat.talkMe.exception;

public class RefreshTokenException extends ServiceException {
    public RefreshTokenException(String message) {
        super(400, message, "TM_106");
    }
}
