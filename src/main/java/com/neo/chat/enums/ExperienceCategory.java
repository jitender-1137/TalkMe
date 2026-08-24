package com.neo.chat.enums;

/**
 * Broad bucket a {@link com.neo.chat.domain.UserExperience} tag belongs to, for the
 * Human Knowledge Network ("ask someone who has done it"). Persisted as a STRING so the
 * ordinal order can change freely. {@link #OTHER} is the catch-all default.
 */
public enum ExperienceCategory {
    CAREER,
    EDUCATION,
    RELOCATION,
    TRAVEL,
    BUSINESS,
    TECHNOLOGY,
    LIFE,
    FINANCE,
    HEALTH,
    OTHER
}
