package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.RelationshipJourneyResponse;

/**
 * Relationship Journey (feature #19, RELATIONSHIP_JOURNEY): a materialized timeline of
 * milestones between two users, derived from persistent signals by a nightly job and read
 * back on the profile.
 */
public interface RelationshipJourneyService {

    /**
     * The timeline between {@code viewer} and the user identified by {@code otherUserUuid}.
     * Authorized only when the viewer is that user or an active friend of theirs; otherwise
     * throws {@code ForbiddenException}. Lazily materializes friendship milestones so the
     * timeline is fresh even before the nightly job runs.
     */
    RelationshipJourneyResponse getJourney(User viewer, String otherUserUuid);

    /**
     * Idempotently upsert every derivable milestone for the given pair (order-independent).
     * Safe to call repeatedly — existing milestones are never duplicated.
     */
    void materializeFor(User userA, User userB);
}
