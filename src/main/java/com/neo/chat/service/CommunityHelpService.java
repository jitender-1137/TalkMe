package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.HelpAnswerResponse;
import com.neo.chat.dto.response.HelpFeedResponse;
import com.neo.chat.dto.response.HelpRequestResponse;
import com.neo.chat.enums.HelpCategory;

import java.time.Instant;
import java.util.List;

/**
 * Community Help feed (feature #9, COMMUNITY_HELP) — real-time, city-scoped, ephemeral practical
 * Q&amp;A. Not anonymous: askers and answerers surface their public info so neighbours can follow up.
 */
public interface CommunityHelpService {

    /**
     * Posts a new help request. {@code city} defaults to the caller's own city when blank; body is
     * moderated and length-checked; a per-user cap on concurrent OPEN requests guards against spam.
     * {@code expiresAt} is set to a fixed TTL from now.
     */
    HelpRequestResponse postHelp(User user, String city, HelpCategory category, String body);

    /**
     * City-scoped feed of OPEN, not-yet-expired requests, newest first, excluding blocked askers.
     * {@code city} defaults to the viewer's own city when blank. Ranks the returned page by the
     * asker's live presence (ONLINE → AWAY → offline order is applied to labels only; DB order is
     * recency).
     */
    HelpFeedResponse feed(User viewer, String city, HelpCategory category, String cursor, int limit);

    /**
     * Adds an answer to an OPEN, non-expired request; moderates the body, increments the request's
     * answer count and pushes a WS event to the asker. Blocked participants (either direction) are
     * refused.
     */
    HelpAnswerResponse answer(User user, String requestUuid, String body);

    /**
     * Read-only fetch of a request's answers, oldest first. Validates the uuid and that the request
     * exists (TM_988). Not anonymous: each answer carries the answerer's public info.
     */
    List<HelpAnswerResponse> getAnswers(User viewer, String requestUuid);

    /**
     * Marks a request RESOLVED. Asker-only; idempotent if already resolved.
     */
    HelpRequestResponse markResolved(User user, String requestUuid);

    /**
     * Flips OPEN requests whose TTL has elapsed to RESOLVED. Driven by the scheduled reaper.
     *
     * @param now the reap deadline (requests with {@code expiresAt < now} are reaped)
     * @return the number of requests flipped
     */
    int reapExpired(Instant now);
}
