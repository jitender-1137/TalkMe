package com.chat.talkMe.exception;

public class WebSocketException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public WebSocketException(String message) {
        super(400, message, "TM_010");
    }
}
