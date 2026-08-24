package com.neo.chat.enums;

/**
 * Lifecycle of a {@link com.neo.chat.domain.HelpRequest} in the Community Help feed. A request is
 * {@link #OPEN} while it can still receive answers; it becomes {@link #RESOLVED} either when the
 * asker marks it resolved or when the reaper flips it after its TTL elapses. Persisted as a STRING.
 */
public enum HelpStatus {
    OPEN,
    RESOLVED
}
