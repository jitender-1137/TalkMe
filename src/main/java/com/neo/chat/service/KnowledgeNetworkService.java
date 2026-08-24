package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.UpdateExperiencesRequest;
import com.neo.chat.dto.response.ExperienceResponse;
import com.neo.chat.dto.response.KnowledgePersonResponse;
import com.neo.chat.dto.response.KnowledgeSearchPageResponse;
import com.neo.chat.enums.ExperienceCategory;

import java.util.List;

/**
 * Human Knowledge Network — "ask someone who has done it". Users tag real experiences
 * ("Moved to Canada", "Java developer", "Visited Japan"); others search for and open a chat
 * with people who have that experience. Gated by the {@code KNOWLEDGE_NETWORK} entitlement.
 */
public interface KnowledgeNetworkService {

    /**
     * The caller's own experience tags (full, editable set).
     */
    List<ExperienceResponse> getMine(User user);

    /**
     * Replace the caller's whole set of experience tags with {@code request}. Tags are
     * trimmed, de-duplicated (case-insensitive) and validated (non-blank, count cap); the
     * previous set is discarded.
     *
     * @throws com.neo.chat.exception.BadRequestException if a tag is blank/too long
     *                                                     ({@code TM_942}) or the cap is
     *                                                     exceeded ({@code TM_943})
     */
    List<ExperienceResponse> updateExperiences(User user, UpdateExperiencesRequest request);

    /**
     * Find people (never the caller, never anyone blocked in either direction) who have an
     * experience matching {@code query} (case-insensitive) and/or {@code category} and are
     * open to questions. Results are ranked available/online first, then away, then offline.
     *
     * @param viewer   the searching caller
     * @param query    free-text tag fragment; may be null/blank to browse by category only
     * @param category optional category filter; null = any category
     * @param cursor   opaque page cursor from a prior response; null = first page
     * @param limit    max cards per page (clamped to a sane range)
     */
    KnowledgeSearchPageResponse search(User viewer, String query, ExperienceCategory category,
                                       String cursor, int limit);

    /**
     * Resolve a target user to open a 1:1 chat with (the "ask" action). Returns the target's
     * public info; does NOT create the chat (the client opens the draft conversation).
     *
     * @throws com.neo.chat.exception.BadRequestException if {@code targetUuid} is not a valid
     *                                                     UUID ({@code TM_944})
     * @throws com.neo.chat.exception.NotFoundException   if no user matches ({@code TM_946})
     * @throws com.neo.chat.exception.ForbiddenException  if a block exists in either
     *                                                     direction ({@code TM_945})
     */
    KnowledgePersonResponse askPerson(User asker, String targetUuid);
}
