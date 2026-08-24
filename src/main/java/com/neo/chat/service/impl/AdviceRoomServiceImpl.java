package com.neo.chat.service.impl;

import com.neo.chat.domain.AdviceQuestion;
import com.neo.chat.domain.AdviceReply;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.AskQuestionRequest;
import com.neo.chat.dto.request.ReplyRequest;
import com.neo.chat.dto.response.AdviceQuestionPageResponse;
import com.neo.chat.dto.response.AdviceQuestionResponse;
import com.neo.chat.dto.response.AdviceReplyResponse;
import com.neo.chat.dto.response.AdviceThreadResponse;
import com.neo.chat.enums.AdviceCategory;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.repository.AdviceQuestionRepository;
import com.neo.chat.repository.AdviceReplyRepository;
import com.neo.chat.service.AdviceRoomService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anonymous Advice Rooms (feature ADVICE_ROOMS).
 *
 * <p>ANONYMITY is enforced structurally: every entity stores its author (for moderation / abuse
 * handling and author-only deletion), but {@link #toResponse}/{@link #toReplyResponse} NEVER copy
 * any author field into a response DTO. There is no query or endpoint that surfaces who asked or
 * replied — the mapping layer is the single, narrow choke point, mirroring
 * {@link AnonymousComplimentServiceImpl}.
 *
 * <p>The one author-derived signal the mappers set is the viewer-relative boolean {@code mine}
 * (true only when the requesting viewer is the author), so a client can offer author-only delete
 * without ever learning who any author is.
 *
 * <p>The sensitive categories ({@link #SENSITIVE}) additionally carry a peer-opinion
 * {@code disclaimer} on every response so advice is never mistaken for professional guidance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdviceRoomServiceImpl implements AdviceRoomService {

    /**
     * Max questions one user may post per rolling 24h (anti-spam cap).
     */
    static final int DAILY_QUESTION_CAP = 20;

    /**
     * Default / max page size for the question listing.
     */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /**
     * Peer-opinion disclaimer shown on sensitive-category threads.
     */
    static final String DISCLAIMER = "Peer opinions, not professional advice";

    /**
     * Categories where advice could be mistaken for professional guidance — they carry the
     * {@link #DISCLAIMER}.
     */
    static final Set<AdviceCategory> SENSITIVE = EnumSet.of(
            AdviceCategory.CAREER,
            AdviceCategory.RELATIONSHIPS,
            AdviceCategory.FINANCE,
            AdviceCategory.HEALTH,
            AdviceCategory.BUSINESS);

    private final AdviceQuestionRepository questionRepository;
    private final AdviceReplyRepository replyRepository;
    private final ContentModerationService moderationService;

    // ── Ask ──────────────────────────────────────────────────────────────────────

    /**
     * Posts an anonymous question. Validates non-blank title/body, hard-blocks explicit text, and
     * enforces the rolling 24h cap. The author is persisted (moderation only) but the returned DTO
     * carries no author identity.
     *
     * @throws com.neo.chat.exception.BadRequestException        on a blank title or body (TM_850)
     * @throws com.neo.chat.exception.ContentModerationException if title or body is explicit
     * @throws com.neo.chat.exception.TooManyRequestsException   when the 24h cap is exceeded (TM_851)
     */
    @Override
    public AdviceQuestionResponse askQuestion(User author, AskQuestionRequest request) {
        String title = request == null || request.getTitle() == null ? "" : request.getTitle().trim();
        String body = request == null || request.getBody() == null ? "" : request.getBody().trim();
        if (title.isEmpty() || body.isEmpty()) {
            throw new BadRequestException("A question needs both a title and a body", "TM_850");
        }

        // Hard-block explicit text — advice rooms are a public-guidelines surface.
        if (moderationService.moderateText(title).explicit()
                || moderationService.moderateText(body).explicit()) {
            throw new ContentModerationException(
                    "Your question contains content that violates our community guidelines.");
        }

        long recent = questionRepository.countByAuthorAndCreatedAtAfter(
                author, Instant.now().minus(Duration.ofDays(1)));
        if (recent >= DAILY_QUESTION_CAP) {
            throw new TooManyRequestsException(
                    "You have reached today's question limit. Try again later.", "TM_851");
        }

        AdviceCategory category = request.getCategory() != null ? request.getCategory() : AdviceCategory.OTHER;
        AdviceQuestion question = AdviceQuestion.builder()
                .author(author)
                .title(title)
                .body(body)
                .category(category)
                .replyCount(0)
                .build();
        question = questionRepository.save(question);

        return toResponse(question, author);
    }

    // ── List ─────────────────────────────────────────────────────────────────────

    /**
     * Lists non-deleted questions (optionally filtered by category), newest first, cursor-paged.
     * Every item is fully anonymised.
     */
    @Override
    @Transactional(readOnly = true)
    public AdviceQuestionPageResponse listQuestions(User viewer, AdviceCategory category, String cursor, int limit) {
        int pageSize = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int page = parseCursor(cursor);

        // NOTE: this Specification lambda is exercised only by the integration test — a mocked
        // repository never runs it. Unit tests stub findAll(...) with canned rows.
        Specification<AdviceQuestion> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("isDeleted")));
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdviceQuestion> matches = questionRepository.findAll(spec, pageable);

        List<AdviceQuestionResponse> items = new ArrayList<>();
        for (AdviceQuestion q : matches.getContent()) {
            items.add(toResponse(q, viewer));
        }

        boolean hasMore = matches.hasNext();
        return AdviceQuestionPageResponse.builder()
                .items(items)
                .nextCursor(hasMore ? String.valueOf(page + 1) : null)
                .hasMore(hasMore)
                .build();
    }

    // ── Get one + replies ──────────────────────────────────────────────────────────

    /**
     * A single question with its anonymised replies (oldest first). Threading is expressed by
     * translating each reply's stored parent id back to the parent's uuid.
     *
     * @throws com.neo.chat.exception.BadRequestException if the uuid is malformed (TM_852)
     * @throws com.neo.chat.exception.NotFoundException   if the question is missing/deleted (TM_853)
     */
    @Override
    @Transactional(readOnly = true)
    public AdviceThreadResponse getQuestion(User viewer, String questionUuid) {
        AdviceQuestion question = resolveQuestion(questionUuid);

        List<AdviceReply> rows = replyRepository
                .findByQuestionAndIsDeletedFalseOrderByCreatedAtAsc(question);

        // id -> uuid so a reply's parent can be expressed as the parent's uuid on the wire.
        Map<Long, String> idToUuid = new HashMap<>();
        for (AdviceReply r : rows) {
            if (r.getId() != null && r.getUuid() != null) {
                idToUuid.put(r.getId(), r.getUuid().toString());
            }
        }

        List<AdviceReplyResponse> replies = new ArrayList<>();
        for (AdviceReply r : rows) {
            String parentUuid = r.getParentReplyId() != null ? idToUuid.get(r.getParentReplyId()) : null;
            replies.add(toReplyResponse(r, parentUuid, viewer));
        }

        return AdviceThreadResponse.builder()
                .question(toResponse(question, viewer))
                .replies(replies)
                .build();
    }

    // ── Reply ──────────────────────────────────────────────────────────────────────

    /**
     * Posts an anonymous reply to a question (moderated), optionally threaded under a parent reply
     * of the SAME question. Bumps the question's denormalised reply count.
     *
     * @throws com.neo.chat.exception.BadRequestException        on a malformed uuid (TM_852) or a
     *                                                              blank reply body (TM_854)
     * @throws com.neo.chat.exception.NotFoundException          if the question is missing (TM_853)
     * @throws com.neo.chat.exception.ContentModerationException if the body is explicit
     */
    @Override
    public AdviceReplyResponse reply(User author, String questionUuid, ReplyRequest request) {
        AdviceQuestion question = resolveQuestion(questionUuid);

        String body = request == null || request.getBody() == null ? "" : request.getBody().trim();
        if (body.isEmpty()) {
            throw new BadRequestException("A reply cannot be empty", "TM_854");
        }
        if (moderationService.moderateText(body).explicit()) {
            throw new ContentModerationException(
                    "Your reply contains content that violates our community guidelines.");
        }

        // Optional threading: the parent must be a live reply of THIS question.
        Long parentId = null;
        String parentUuid = null;
        String requestedParent = request.getParentReplyUuid();
        if (requestedParent != null && !requestedParent.isBlank()) {
            AdviceReply parent = replyRepository.findByUuid(parseUuid(requestedParent))
                    .filter(p -> !p.isDeleted())
                    .filter(p -> p.getQuestion() != null
                            && p.getQuestion().getId() != null
                            && p.getQuestion().getId().equals(question.getId()))
                    .orElseThrow(() -> new BadRequestException(
                            "The reply you are responding to is not part of this question", "TM_856"));
            parentId = parent.getId();
            parentUuid = parent.getUuid() != null ? parent.getUuid().toString() : null;
        }

        AdviceReply saved = replyRepository.save(AdviceReply.builder()
                .question(question)
                .author(author)
                .body(body)
                .parentReplyId(parentId)
                .build());

        question.setReplyCount(question.getReplyCount() + 1);
        questionRepository.save(question);

        return toReplyResponse(saved, parentUuid, author);
    }

    // ── Delete (author-only) ─────────────────────────────────────────────────────────

    /**
     * Soft-deletes the caller's OWN question. Author-only; the author is never revealed to anyone.
     *
     * @throws com.neo.chat.exception.BadRequestException if the uuid is malformed (TM_852)
     * @throws com.neo.chat.exception.NotFoundException   if the question is missing/deleted (TM_853)
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not the author (TM_855)
     */
    @Override
    public void deleteMyQuestion(User author, String questionUuid) {
        AdviceQuestion question = resolveQuestion(questionUuid);
        if (!question.getAuthor().getId().equals(author.getId())) {
            throw new ForbiddenException("You can only delete your own question", "TM_855");
        }
        question.setDeleted(true);
        questionRepository.save(question);
    }

    /**
     * Soft-deletes the caller's OWN reply and decrements the question's reply count. Author-only;
     * the author is never revealed to anyone.
     *
     * @throws com.neo.chat.exception.BadRequestException if the uuid is malformed (TM_852)
     * @throws com.neo.chat.exception.NotFoundException   if the reply is missing/deleted (TM_853)
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not the author (TM_855)
     */
    @Override
    public void deleteMyReply(User author, String replyUuid) {
        AdviceReply reply = replyRepository.findByUuid(parseUuid(replyUuid))
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new NotFoundException("Reply not found", "TM_853"));
        if (!reply.getAuthor().getId().equals(author.getId())) {
            throw new ForbiddenException("You can only delete your own reply", "TM_855");
        }
        reply.setDeleted(true);
        replyRepository.save(reply);

        AdviceQuestion question = reply.getQuestion();
        if (question != null) {
            question.setReplyCount(Math.max(0, question.getReplyCount() - 1));
            questionRepository.save(question);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Resolve a live (non-deleted) question by uuid.
     *
     * @throws com.neo.chat.exception.BadRequestException if the uuid is malformed (TM_852)
     * @throws com.neo.chat.exception.NotFoundException   if missing/deleted (TM_853)
     */
    private AdviceQuestion resolveQuestion(String questionUuid) {
        return questionRepository.findByUuid(parseUuid(questionUuid))
                .filter(q -> !q.isDeleted())
                .orElseThrow(() -> new NotFoundException("Question not found", "TM_853"));
    }

    /**
     * Parse a uuid string, mapping malformed/null input to a clean 400.
     *
     * @throws com.neo.chat.exception.BadRequestException on invalid/null input (TM_852)
     */
    private UUID parseUuid(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid id", "TM_852");
        }
    }

    /**
     * Map a question to its anonymous client view — NO author fields. The disclaimer is populated
     * for sensitive categories only. The viewer-relative {@code mine} flag is the sole author-
     * derived signal: true only when {@code viewer} is the author, and it discloses nothing about
     * any other author.
     */
    private AdviceQuestionResponse toResponse(AdviceQuestion q, User viewer) {
        return AdviceQuestionResponse.builder()
                .uuid(q.getUuid() != null ? q.getUuid().toString() : null)
                .title(q.getTitle())
                .body(q.getBody())
                .category(q.getCategory() != null ? q.getCategory().name() : null)
                .disclaimer(q.getCategory() != null && SENSITIVE.contains(q.getCategory()) ? DISCLAIMER : null)
                .replyCount(q.getReplyCount())
                .createdAt(q.getCreatedAt() != null ? q.getCreatedAt().toString() : null)
                .mine(isMine(q.getAuthor(), viewer))
                .build();
    }

    /**
     * Map a reply to its anonymous client view — NO author fields. Only the viewer-relative
     * {@code mine} flag is derived from the author, and only for the requesting viewer.
     */
    private AdviceReplyResponse toReplyResponse(AdviceReply r, String parentUuid, User viewer) {
        return AdviceReplyResponse.builder()
                .uuid(r.getUuid() != null ? r.getUuid().toString() : null)
                .body(r.getBody())
                .parentReplyUuid(parentUuid)
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
                .mine(isMine(r.getAuthor(), viewer))
                .build();
    }

    /**
     * Viewer-relative ownership: true only when the (stored, never-exposed) author is the current
     * viewer. Null-safe on both sides.
     */
    private boolean isMine(User author, User viewer) {
        return author != null && viewer != null
                && author.getId() != null && author.getId().equals(viewer.getId());
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(cursor.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
