package com.chat.talkMe.enums;

/**
 * How the user is currently accessing TalkMe. Drives notification delivery:
 * BROWSER → WebSocket only; PWA / IOS_HOME → Web Push when the app is closed.
 */
public enum InstallationType {
    BROWSER,
    PWA,
    IOS_HOME
}
