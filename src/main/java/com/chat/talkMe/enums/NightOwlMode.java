package com.chat.talkMe.enums;

/**
 * How Night Owl Mode (feature #1) activates for a user.
 * <ul>
 *   <li>{@code AUTO} — on during the user's night window (default 22:00–05:00 local)</li>
 *   <li>{@code ON} — always on</li>
 *   <li>{@code OFF} — never</li>
 * </ul>
 * The active/inactive decision is made client-side from local time; the server only
 * stores the preference (it can't reliably know the user's wall-clock).
 */
public enum NightOwlMode {
    AUTO,
    ON,
    OFF
}
