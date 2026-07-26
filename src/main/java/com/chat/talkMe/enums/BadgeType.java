package com.chat.talkMe.enums;

/**
 * Peer-endorseable, COSMETIC-ONLY badges (feature #30). A badge is earned when enough
 * distinct peers endorse a user for that trait; it never gates any feature or limit.
 * Each carries a human-friendly display label served in {@code BadgeResponse}.
 */
public enum BadgeType {
    GREAT_LISTENER("Great Listener"),
    FRIENDLY("Friendly"),
    RESPECTFUL_FLIRT("Respectful Flirt"),
    FUNNY("Funny"),
    CONVERSATION_STARTER("Conversation Starter"),
    HELPFUL("Helpful"),
    ACTIVE_NIGHT_OWL("Active Night Owl"),
    COMMUNITY_FAVOURITE("Community Favourite");

    private final String label;

    BadgeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
