package com.chat.talkMe.exception;

import java.io.Serial;

/**
 * Signals that an authentication (access) token has expired. Maps to HTTP 401 with
 * message code {@code TM_104}, prompting the client to refresh.
 */
public class TokenExpiredException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public TokenExpiredException(String message) {
        super(401, message, "TM_104");
    }
}
