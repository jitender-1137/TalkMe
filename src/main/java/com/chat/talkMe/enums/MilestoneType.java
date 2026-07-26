package com.chat.talkMe.enums;

/**
 * A single kind of relationship milestone between two users (feature #19, RELATIONSHIP_JOURNEY).
 *
 * <p>Milestones are materialized by {@code RelationshipJourneyJob} from PERSISTENT signals and
 * shown as a timeline on the profile. Each type carries a human-readable {@link #label} used
 * directly in the API response so the client needs no parallel copy of the labels.
 *
 * <p>v1 only materializes the friendship-derived types ({@link #BECAME_FRIENDS},
 * {@link #ONE_MONTH_FRIENDS}). The message- and game-derived types are declared here so the
 * schema and response shape are stable, and are populated by a follow-up (see wiringSpec).
 */
public enum MilestoneType {

    BECAME_FRIENDS("You became friends"),
    FIRST_MESSAGE("Sent your first message"),
    MESSAGES_50("Exchanged 50 messages"),
    MESSAGES_500("Exchanged 500 messages"),
    FIRST_PHOTO_SHARED("Shared your first photo"),
    ONE_MONTH_FRIENDS("One month of friendship"),
    GAMES_PLAYED("Played your first game together");

    private final String label;

    MilestoneType(String label) {
        this.label = label;
    }

    /** Human-readable label surfaced on the timeline. */
    public String getLabel() {
        return label;
    }
}
