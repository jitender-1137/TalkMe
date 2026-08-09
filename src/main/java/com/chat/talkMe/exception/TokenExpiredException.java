package com.chat.talkMe.exception;

public class TokenExpiredException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public TokenExpiredException(String message) {
        super(401, message, "TM_104");
    }
}
