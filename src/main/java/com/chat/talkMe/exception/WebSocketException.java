package com.chat.talkMe.exception;

/**
 * Signals an error during WebSocket/STOMP message handling. Maps to HTTP 400 with
 * message code {@code TM_010}.
 */
public class WebSocketException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public WebSocketException(String message) {
        super(400, message, "TM_010");
    }
}
