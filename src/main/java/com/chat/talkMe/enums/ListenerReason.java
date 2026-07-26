package com.chat.talkMe.enums;

/**
 * Optional context a person can attach when asking to talk to a volunteer listener
 * ("Someone Is Listening", features #26/#27). Purely a hint for the listener — it shapes
 * the support room's title so the volunteer knows the general shape of the conversation
 * before they join. Never exposed to anyone but the two people in the room.
 */
public enum ListenerReason {

    NEED_TO_TALK("Just need to talk"),
    BAD_DAY("Had a bad day"),
    CANT_SLEEP("Can't sleep"),
    LONELY("Feeling lonely"),
    ANXIOUS("Feeling anxious"),
    RELATIONSHIP("Relationship stuff"),
    JUST_VENT("Just need to vent"),
    OTHER("Something else");

    private final String label;

    ListenerReason(String label) {
        this.label = label;
    }

    /** Human-friendly label used in the support room's title. */
    public String getLabel() {
        return label;
    }

    /** Case-insensitive lookup; returns {@link #NEED_TO_TALK} for null/unknown input. */
    public static ListenerReason fromWireOrDefault(String wire) {
        if (wire == null || wire.isBlank()) return NEED_TO_TALK;
        try {
            return ListenerReason.valueOf(wire.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NEED_TO_TALK;
        }
    }
}
