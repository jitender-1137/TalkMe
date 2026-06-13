package com.chat.talkMe.exception;

public class TokenExpiredException extends ServiceException {
    public TokenExpiredException(String message) {
        super(401, message, "TM_104");
    }
}
