package com.chat.talkMe.exception;

/**
 * Thrown when a user tries to use a feature they are not entitled to. Carries a
 * distinct message code ({@code TM_FEATURE_LOCKED}) so the client can show an
 * unlock/upsell CTA instead of treating it as a generic 403. Maps to HTTP 403 via
 * {@code GlobalExceptionHandler.handleServiceException}.
 */
public class FeatureLockedException extends ServiceException {

    public static final String CODE = "TM_FEATURE_LOCKED";

    public FeatureLockedException(String message) {
        super(403, message, CODE);
    }

    public FeatureLockedException() {
        super(403, "This feature is not available for your account yet.", CODE);
    }
}
