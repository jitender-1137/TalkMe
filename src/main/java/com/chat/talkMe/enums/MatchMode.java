package com.chat.talkMe.enums;

/** The flavour of a matchmaking session. QUICK is the legacy blind-FIFO path. */
public enum MatchMode {
    QUICK,
    FLIRT,
    MASK,
    COFFEE,
    CHEMISTRY,
    DAILY;

    public static MatchMode from(String v) {
        if (v == null || v.isBlank()) return QUICK;
        try {
            return MatchMode.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return QUICK;
        }
    }
}
