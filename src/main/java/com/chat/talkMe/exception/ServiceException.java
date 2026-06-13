package com.chat.talkMe.exception;

import lombok.Getter;

@Getter
public class ServiceException extends RuntimeException {
    private final int status;
    private final String messageCode;
    private final Object errors;

    public ServiceException(int status, String message, String messageCode, Object errors) {
        super(message);
        this.status = status;
        this.messageCode = messageCode;
        this.errors = errors;
    }

    public ServiceException(int status, String message, String messageCode) {
        this(status, message, messageCode, null);
    }

    public ServiceException(int status, String messageCode) {
        this(status, null, messageCode, null);
    }
}
