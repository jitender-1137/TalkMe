package com.chat.talkMe.exception;

public class RefreshTokenException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public RefreshTokenException(String message) {
        super(400, message, "TM_106");
    }
}
