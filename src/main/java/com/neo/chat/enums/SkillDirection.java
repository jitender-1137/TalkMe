package com.neo.chat.enums;

/**
 * Direction of a {@link com.neo.chat.domain.UserSkill} row (feature SKILL_EXCHANGE).
 *
 * <ul>
 *   <li>{@code OFFER} — a skill the user can teach others.</li>
 *   <li>{@code WANT}  — a skill the user wants to learn.</li>
 * </ul>
 *
 * A peer-to-peer match exists when one user {@code OFFER}s what another {@code WANT}s.
 */
public enum SkillDirection {
    OFFER,
    WANT
}
