package com.chat.talkMe.exception;

public class WebSocketException extends ServiceException {
    public WebSocketException(String message) {
        super(400, message, "TM_010");
    }
}
