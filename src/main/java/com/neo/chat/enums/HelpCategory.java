package com.neo.chat.enums;

/**
 * Broad bucket a {@link com.neo.chat.domain.HelpRequest} belongs to, for the real-time
 * Community Help feed ("my train was cancelled, alternative route?"). Persisted as a STRING so
 * the ordinal order can change freely and new categories can be added without a migration.
 * {@link #OTHER} is the catch-all default.
 */
public enum HelpCategory {
    TRANSPORT,
    DIRECTIONS,
    SHOPPING,
    LOCAL_INFO,
    SAFETY,
    OTHER
}
