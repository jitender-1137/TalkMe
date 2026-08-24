package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.AskQuestionRequest;
import com.neo.chat.dto.request.ReplyRequest;
import com.neo.chat.dto.response.AdviceQuestionPageResponse;
import com.neo.chat.dto.response.AdviceQuestionResponse;
import com.neo.chat.dto.response.AdviceReplyResponse;
import com.neo.chat.dto.response.AdviceThreadResponse;
import com.neo.chat.enums.AdviceCategory;

/**
 * Anonymous Advice Rooms (feature ADVICE_ROOMS).
 *
 * <p>ANONYMITY INVARIANT: the asking/replying user is persisted on every entity for moderation
 * and author-only deletion, but is NEVER mapped into a response DTO. No method exposes who asked
 * or who replied — advice authors are permanently anonymous to all other users.
 */
public interface AdviceRoomService {

    /**
     * Post an anonymous question (moderated, rate-capped).
     */
    AdviceQuestionResponse askQuestion(User author, AskQuestionRequest request);

    /**
     * List questions (optionally filtered by category), newest first, cursor-paginated. The viewer
     * sets each item's viewer-relative {@code mine} flag without exposing any author identity.
     */
    AdviceQuestionPageResponse listQuestions(User viewer, AdviceCategory category, String cursor, int limit);

    /**
     * A single question with its anonymised replies. The viewer sets the viewer-relative
     * {@code mine} flag on the question and each reply without exposing any author identity.
     */
    AdviceThreadResponse getQuestion(User viewer, String questionUuid);

    /**
     * Post an anonymous reply to a question (moderated), optionally threaded under a parent reply.
     */
    AdviceReplyResponse reply(User author, String questionUuid, ReplyRequest request);

    /**
     * Soft-delete the caller's OWN question (author-only). Never reveals the author to anyone else.
     */
    void deleteMyQuestion(User author, String questionUuid);

    /**
     * Soft-delete the caller's OWN reply (author-only). Never reveals the author to anyone else.
     */
    void deleteMyReply(User author, String replyUuid);
}
