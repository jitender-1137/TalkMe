package com.neo.chat.enums;

/**
 * Why a user wants to talk right now (Talk Now — intent + availability matching).
 *
 * <p>Declared when a user goes "available now"; drives who they are matched with. Persisted
 * as a string (in Redis availability entries — there is no DB column for this feature).
 */
public enum TalkNowIntent {
    JUST_TALK,
    NEED_ADVICE,
    FEELING_LONELY,
    WANT_TO_PLAY,
    PRACTICE_LANGUAGE,
    MEET_ANOTHER_COUNTRY,
    CAREER_CHAT,
    RELATIONSHIP_ADVICE,
    STUDY_TOGETHER,
    BRAINSTORM
}
