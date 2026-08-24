package com.neo.chat.exception;

import java.io.Serial;

/**
 * Thrown when a user tries to use a feature that is available to them EXCEPT that the global
 * {@code features.require-verified} gate is on and they haven't verified their email yet. Distinct
 * from {@link FeatureLockedException} (generic "not entitled") so the client can show a
 * "verify your email" CTA — and, crucially, so a caller that bypasses the hidden/locked UI and
 * hits the API directly still gets a clear, secure {@code TM_VERIFY_REQUIRED} 403 rather than a
 * bare Access Denied. Maps to HTTP 403 via {@code GlobalExceptionHandler.handleServiceException}.
 */
public class VerificationRequiredException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String CODE = "TM_VERIFY_REQUIRED";

    public VerificationRequiredException() {
        super(403, "Please verify your email to use this feature.", CODE);
    }
}
