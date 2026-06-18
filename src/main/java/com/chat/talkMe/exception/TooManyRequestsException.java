package com.chat.talkMe.exception;

/** HTTP 429 — raised when an account/IP exceeds the failed-login threshold. */
public class TooManyRequestsException extends ServiceException {
    public TooManyRequestsException(String message, String messageCode) {
        super(429, message, messageCode);
    }
}
