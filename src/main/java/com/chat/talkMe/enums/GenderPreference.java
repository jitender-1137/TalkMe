package com.chat.talkMe.enums;

/** Who a user wants to be matched with (adds COUPLE beyond the base genders). */
public enum GenderPreference {
    ANY,
    MALE,
    FEMALE,
    NONBINARY,
    COUPLE;

    public static GenderPreference from(String v) {
        if (v == null || v.isBlank()) return ANY;
        try {
            return GenderPreference.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ANY;
        }
    }
}
