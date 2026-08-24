package com.neo.chat.enums;

/**
 * Broad topic bucket an {@link com.neo.chat.domain.AdviceQuestion} belongs to, for the
 * Anonymous Advice Rooms (feature ADVICE_ROOMS). Persisted as a STRING so the ordinal order
 * can change freely. {@link #OTHER} is the catch-all default.
 *
 * <p>The "sensitive" categories ({@link #CAREER}, {@link #RELATIONSHIPS}, {@link #FINANCE},
 * {@link #HEALTH}, {@link #BUSINESS}) carry a peer-opinion disclaimer on every response — advice
 * here is community opinion, never professional guidance.
 */
public enum AdviceCategory {
    CAREER,
    RELATIONSHIPS,
    EDUCATION,
    BUSINESS,
    TECHNOLOGY,
    LIFE,
    FINANCE,
    TRAVEL,
    HEALTH,
    OTHER
}
