package com.chat.talkMe.enums;

/**
 * Contributing actions in the reputation ledger (features #30/#31). Each type carries
 * its base weight and anti-abuse caps. The recorder applies diminishing returns +
 * per-type daily caps + a global daily cap at insert time, so spam converges to near-zero
 * marginal value and high levels require sustained activity over months.
 *
 * <ul>
 *   <li>{@code rawWeight} — base points for one occurrence</li>
 *   <li>{@code dailyCap} — max points/day from this type (before the global cap)</li>
 *   <li>{@code perSourceCap} — max points a single source (one post/friend/event) can yield</li>
 * </ul>
 */
public enum ReputationEventType {
    ACCOUNT_AGE_DAY(1, 1, 1),
    PROFILE_COMPLETED(40, 40, 40),
    CONVERSATION_STARTED(3, 15, 1),
    CONVERSATION_SUSTAINED(6, 24, 1),
    REPLY_RECEIVED(1, 10, 1),
    HEALTHY_RESPONSE_RATE_DAY(5, 5, 5),
    FRIEND_LASTING(25, 50, 25),
    POST_QUALITY(4, 12, 1),
    POST_REACTION_RECEIVED(1, 8, 1),
    COMMENT_RECEIVED(1, 8, 1),
    STORY_PUBLISHED(2, 6, 1),
    STORY_ENGAGEMENT(1, 6, 1),
    VOICE_STATUS_PUBLISHED(2, 4, 1),
    VOICE_CONVO(5, 15, 1),
    ROOM_JOINED(2, 6, 1),
    EVENT_ATTENDED(10, 30, 10),
    DAILY_ACTIVE(5, 5, 5),
    WEEKLY_ACTIVE(15, 15, 15),
    MONTHLY_ACTIVE(40, 40, 40),
    NIGHT_OWL_PARTICIPATION(3, 6, 1),
    BADGE_EARNED(15, 45, 15),
    ENDORSEMENT_RECEIVED(2, 10, 1),
    STREAK_MILESTONE(10, 20, 10);

    private final int rawWeight;
    private final int dailyCap;
    private final int perSourceCap;

    ReputationEventType(int rawWeight, int dailyCap, int perSourceCap) {
        this.rawWeight = rawWeight;
        this.dailyCap = dailyCap;
        this.perSourceCap = perSourceCap;
    }

    public int getRawWeight() { return rawWeight; }
    public int getDailyCap() { return dailyCap; }
    public int getPerSourceCap() { return perSourceCap; }
}
