package com.chat.talkMe.exception;

import java.io.Serial;

/**
 * Signals a missing, invalid, or unusable refresh token during token renewal.
 * Maps to HTTP 400 with message code {@code TM_106}.
 */
public class RefreshTokenException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RefreshTokenException(String message) {
        super(400, message, "TM_106");
    }
}
