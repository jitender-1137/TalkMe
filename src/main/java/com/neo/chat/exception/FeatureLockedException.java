package com.neo.chat.exception;

import java.io.Serial;

/**
 * Thrown when a user tries to use a feature they are not entitled to. Carries a
 * distinct message code ({@code TM_FEATURE_LOCKED}) so the client can show an
 * unlock/upsell CTA instead of treating it as a generic 403. Maps to HTTP 403 via
 * {@code GlobalExceptionHandler.handleServiceException}.
 */
public class FeatureLockedException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String CODE = "TM_FEATURE_LOCKED";

    public FeatureLockedException() {
        super(403, "This feature is not available for your account yet.", CODE);
    }
}
