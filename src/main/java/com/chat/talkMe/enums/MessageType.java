package com.chat.talkMe.enums;

public enum MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    /** Group event message ("X added Y", "Z left"…). Payload JSON lives in content. */
    SYSTEM
}
