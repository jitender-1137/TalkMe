package com.chat.talkMe.exception;

import java.io.Serial;

/**
 * Thrown when user content (text or media) violates community guidelines and is
 * hard-blocked (groups, feed posts, comments). Maps to HTTP 422 via the existing
 * {@code GlobalExceptionHandler.handleServiceException}.
 */
public class ContentModerationException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ContentModerationException(String message) {
        super(422, message, "TM_490");
    }

    public ContentModerationException(String message, String messageCode) {
        super(422, message, messageCode);
    }
}
