package com.neo.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.moderation.ModerationResult;
import com.neo.chat.repository.AdviceQuestionRepository;
import com.neo.chat.repository.AdviceReplyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link AdviceRoomServiceImpl} (feature ADVICE_ROOMS).
 *
 * <p>No Spring context, no DB — every collaborator is mocked. The focus is the <b>anonymity
 * invariant</b>: the asking/replying user is persisted for moderation but is NEVER present in any
 * response DTO. These tests pin that plus moderation, the rate cap, sensitive-category disclaimers,
 * threading, and author-only deletion. The JPA {@link Specification} lambda is integration-only —
 * {@code findAll(spec, pageable)} is stubbed with canned rows here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdviceRoomServiceImpl (unit)")
class AdviceRoomServiceImplTest {

    @Mock
    private AdviceQuestionRepository questionRepository;
    @Mock
    private AdviceReplyRepository replyRepository;
    @Mock
    private ContentModerationService moderationService;

    private AdviceRoomServiceImpl service;

    private User author;

    @BeforeEach
    void setUp() {
        service = new AdviceRoomServiceImpl(questionRepository, replyRepository, moderationService);
        author = user(1L, "secret-author-xyz", "Secret Author");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static User user(long id, String username, String name) {
        User u = User.builder().username(username).name(name).email(username + "@e.com")
                .isGuest(false).banned(false).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private static AskQuestionRequest ask(String title, String body, AdviceCategory cat) {
        return AskQuestionRequest.builder().title(title).body(body).category(cat).build();
    }

    private static AdviceQuestion question(long id, User author, AdviceCategory cat) {
        AdviceQuestion q = AdviceQuestion.builder()
                .author(author).title("How do I switch careers?").body("Looking for advice")
                .category(cat).replyCount(0).build();
        q.setId(id);
        q.setUuid(UUID.randomUUID());
        q.setCreatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        return q;
    }

    private static AdviceReply reply(long id, AdviceQuestion q, User author, Long parentId) {
        AdviceReply r = AdviceReply.builder()
                .question(q).author(author).body("Here is my take").parentReplyId(parentId).build();
        r.setId(id);
        r.setUuid(UUID.randomUUID());
        r.setCreatedAt(Instant.parse("2026-08-01T11:00:00Z"));
        return r;
    }

    private void moderationClean() {
        when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  askQuestion
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("askQuestion")
    class AskQuestion {

        @Test
        @DisplayName("clean + under cap → persists (author stored) and returns an anonymised response")
        void happyPathPersistsAndAnonymises() {
            moderationClean();
            when(questionRepository.countByAuthorAndCreatedAtAfter(any(), any())).thenReturn(0L);
            when(questionRepository.save(any())).thenAnswer(i -> {
                AdviceQuestion q = i.getArgument(0);
                q.setUuid(UUID.randomUUID());
                q.setCreatedAt(Instant.parse("2026-08-01T10:00:00Z"));
                return q;
            });

            AdviceQuestionResponse res = service.askQuestion(author,
                    ask("  Career switch  ", "  Should I move into tech?  ", AdviceCategory.CAREER));

            // Trimmed + persisted with the real author (moderation only).
            ArgumentCaptor<AdviceQuestion> cap = ArgumentCaptor.forClass(AdviceQuestion.class);
            verify(questionRepository).save(cap.capture());
            assertThat(cap.getValue().getAuthor()).isSameAs(author);
            assertThat(cap.getValue().getTitle()).isEqualTo("Career switch");
            assertThat(cap.getValue().getBody()).isEqualTo("Should I move into tech?");

            assertThat(res.getTitle()).isEqualTo("Career switch");
            assertThat(res.getCategory()).isEqualTo("CAREER");
            // Sensitive category → disclaimer present.
            assertThat(res.getDisclaimer()).isEqualTo(AdviceRoomServiceImpl.DISCLAIMER);
            assertThat(res.getReplyCount()).isZero();
            // The asker is the viewer here, so mine is true (author identity still never mapped).
            assertThat(res.isMine()).isTrue();
        }

        @Test
        @DisplayName("null category defaults to OTHER with no disclaimer")
        void defaultsCategoryToOtherNoDisclaimer() {
            moderationClean();
            when(questionRepository.countByAuthorAndCreatedAtAfter(any(), any())).thenReturn(0L);
            when(questionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            AdviceQuestionResponse res = service.askQuestion(author, ask("Title", "Body", null));

            assertThat(res.getCategory()).isEqualTo("OTHER");
            assertThat(res.getDisclaimer()).isNull();
        }

        @Test
        @DisplayName("blank title → BadRequestException TM_850, nothing saved")
        void blankTitle() {
            assertThatThrownBy(() -> service.askQuestion(author, ask("   ", "Body", AdviceCategory.LIFE)))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_850"));
            verify(questionRepository, never()).save(any());
        }

        @Test
        @DisplayName("blank body → BadRequestException TM_850, nothing saved")
        void blankBody() {
            assertThatThrownBy(() -> service.askQuestion(author, ask("Title", "  ", AdviceCategory.LIFE)))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_850"));
            verify(questionRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit text → ContentModerationException, nothing saved")
        void explicitBlocked() {
            when(moderationService.moderateText(anyString()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.SEXUAL, 1.0, List.of("x")));

            assertThatThrownBy(() -> service.askQuestion(author, ask("Title", "Body", AdviceCategory.LIFE)))
                    .isInstanceOf(ContentModerationException.class);
            verify(questionRepository, never()).save(any());
        }

        @Test
        @DisplayName("over the rolling 24h cap → TooManyRequestsException TM_851")
        void rateCapped() {
            moderationClean();
            when(questionRepository.countByAuthorAndCreatedAtAfter(any(), any()))
                    .thenReturn((long) AdviceRoomServiceImpl.DAILY_QUESTION_CAP);

            assertThatThrownBy(() -> service.askQuestion(author, ask("Title", "Body", AdviceCategory.LIFE)))
                    .isInstanceOfSatisfying(com.neo.chat.exception.TooManyRequestsException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_851"));
            verify(questionRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  listQuestions
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listQuestions")
    class ListQuestions {

        @SuppressWarnings("unchecked")
        private void stubFindAll(Page<AdviceQuestion> page) {
            when(questionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);
        }

        @Test
        @DisplayName("maps rows to anonymised responses and sets nextCursor when more remain")
        void mapsAndPaginates() {
            AdviceQuestion q = question(1L, author, AdviceCategory.FINANCE);
            stubFindAll(new PageImpl<>(List.of(q), PageRequest.of(0, 20), 50));

            AdviceQuestionPageResponse page = service.listQuestions(author, AdviceCategory.FINANCE, null, 20);

            assertThat(page.getItems()).hasSize(1);
            assertThat(page.getItems().get(0).getCategory()).isEqualTo("FINANCE");
            assertThat(page.getItems().get(0).getDisclaimer())
                    .isEqualTo(AdviceRoomServiceImpl.DISCLAIMER);
            assertThat(page.isHasMore()).isTrue();
            assertThat(page.getNextCursor()).isEqualTo("1");
        }

        @Test
        @DisplayName("mine=true for the author's own question, false for a different viewer")
        void setsViewerRelativeMineFlag() {
            AdviceQuestion q = question(1L, author, AdviceCategory.FINANCE);
            stubFindAll(new PageImpl<>(List.of(q), PageRequest.of(0, 20), 1));

            AdviceQuestionResponse asAuthor =
                    service.listQuestions(author, null, null, 20).getItems().get(0);
            assertThat(asAuthor.isMine()).isTrue();

            stubFindAll(new PageImpl<>(List.of(q), PageRequest.of(0, 20), 1));
            AdviceQuestionResponse asOther =
                    service.listQuestions(user(99L, "someone-else", "Else"), null, null, 20)
                            .getItems().get(0);
            assertThat(asOther.isMine()).isFalse();
        }

        @Test
        @DisplayName("parses cursor into page number and clamps limit to MAX_LIMIT")
        void parsesCursorAndClampsLimit() {
            stubFindAll(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

            service.listQuestions(author, null, "2", 999);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(questionRepository).findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(AdviceRoomServiceImpl.MAX_LIMIT);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  getQuestion
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getQuestion")
    class GetQuestion {

        @Test
        @DisplayName("returns thread with replies, threading parent id back to parent uuid")
        void returnsThreadWithThreading() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            AdviceReply parent = reply(10L, q, user(2L, "otherA", "A"), null);
            AdviceReply child = reply(11L, q, user(3L, "otherB", "B"), 10L); // parent id -> parent uuid
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));
            when(replyRepository.findByQuestionAndIsDeletedFalseOrderByCreatedAtAsc(q))
                    .thenReturn(List.of(parent, child));

            AdviceThreadResponse thread = service.getQuestion(author, q.getUuid().toString());

            assertThat(thread.getQuestion().getUuid()).isEqualTo(q.getUuid().toString());
            assertThat(thread.getReplies()).hasSize(2);
            assertThat(thread.getReplies().get(0).getParentReplyUuid()).isNull();
            assertThat(thread.getReplies().get(1).getParentReplyUuid())
                    .isEqualTo(parent.getUuid().toString());
        }

        @Test
        @DisplayName("mine flag is viewer-relative on the question and each reply")
        void setsViewerRelativeMineFlags() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            User replierA = user(2L, "otherA", "A");
            User replierB = user(3L, "otherB", "B");
            AdviceReply parent = reply(10L, q, replierA, null);
            AdviceReply child = reply(11L, q, replierB, 10L);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));
            when(replyRepository.findByQuestionAndIsDeletedFalseOrderByCreatedAtAsc(q))
                    .thenReturn(List.of(parent, child));

            // Viewer = the question author → owns the question, neither reply.
            AdviceThreadResponse asAsker = service.getQuestion(author, q.getUuid().toString());
            assertThat(asAsker.getQuestion().isMine()).isTrue();
            assertThat(asAsker.getReplies().get(0).isMine()).isFalse();
            assertThat(asAsker.getReplies().get(1).isMine()).isFalse();

            // Viewer = replier A → owns only the first reply.
            AdviceThreadResponse asReplierA = service.getQuestion(replierA, q.getUuid().toString());
            assertThat(asReplierA.getQuestion().isMine()).isFalse();
            assertThat(asReplierA.getReplies().get(0).isMine()).isTrue();
            assertThat(asReplierA.getReplies().get(1).isMine()).isFalse();
        }

        @Test
        @DisplayName("malformed uuid → BadRequestException TM_852")
        void invalidUuid() {
            assertThatThrownBy(() -> service.getQuestion(author, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_852"));
        }

        @Test
        @DisplayName("missing/deleted question → NotFoundException TM_853")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(questionRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getQuestion(author, id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_853"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  reply
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("reply")
    class Reply {

        private ReplyRequest req(String body, String parentUuid) {
            return ReplyRequest.builder().body(body).parentReplyUuid(parentUuid).build();
        }

        @Test
        @DisplayName("top-level reply → persists (author stored), bumps replyCount, anonymised")
        void topLevelReply() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            q.setReplyCount(2);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            User replier = user(2L, "replier-secret", "Replier");
            when(replyRepository.save(any())).thenAnswer(i -> {
                AdviceReply r = i.getArgument(0);
                r.setUuid(UUID.randomUUID());
                return r;
            });

            AdviceReplyResponse res = service.reply(replier, q.getUuid().toString(), req("My advice", null));

            ArgumentCaptor<AdviceReply> cap = ArgumentCaptor.forClass(AdviceReply.class);
            verify(replyRepository).save(cap.capture());
            assertThat(cap.getValue().getAuthor()).isSameAs(replier);
            assertThat(cap.getValue().getParentReplyId()).isNull();

            // replyCount bumped and persisted.
            ArgumentCaptor<AdviceQuestion> qcap = ArgumentCaptor.forClass(AdviceQuestion.class);
            verify(questionRepository).save(qcap.capture());
            assertThat(qcap.getValue().getReplyCount()).isEqualTo(3);

            assertThat(res.getBody()).isEqualTo("My advice");
            assertThat(res.getParentReplyUuid()).isNull();
            // The replier is the viewer here, so mine is true (author identity still never mapped).
            assertThat(res.isMine()).isTrue();
        }

        @Test
        @DisplayName("threaded reply → resolves parent of same question, echoes parent uuid")
        void threadedReply() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            AdviceReply parent = reply(10L, q, user(2L, "pa", "PA"), null);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(replyRepository.findByUuid(parent.getUuid())).thenReturn(Optional.of(parent));
            when(replyRepository.save(any())).thenAnswer(i -> {
                AdviceReply r = i.getArgument(0);
                r.setUuid(UUID.randomUUID());
                return r;
            });
            when(questionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            AdviceReplyResponse res = service.reply(
                    user(3L, "child", "Child"), q.getUuid().toString(),
                    req("A threaded answer", parent.getUuid().toString()));

            ArgumentCaptor<AdviceReply> cap = ArgumentCaptor.forClass(AdviceReply.class);
            verify(replyRepository).save(cap.capture());
            assertThat(cap.getValue().getParentReplyId()).isEqualTo(10L);
            assertThat(res.getParentReplyUuid()).isEqualTo(parent.getUuid().toString());
        }

        @Test
        @DisplayName("parent reply from a different question → BadRequestException TM_856")
        void parentFromDifferentQuestion() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            AdviceQuestion other = question(2L, author, AdviceCategory.LIFE);
            AdviceReply parent = reply(10L, other, user(2L, "pa", "PA"), null);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));
            when(moderationService.moderateText(anyString())).thenReturn(ModerationResult.clean());
            when(replyRepository.findByUuid(parent.getUuid())).thenReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.reply(
                    user(3L, "child", "Child"), q.getUuid().toString(),
                    req("Answer", parent.getUuid().toString())))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_856"));
            verify(replyRepository, never()).save(any());
        }

        @Test
        @DisplayName("blank body → BadRequestException TM_854, nothing saved")
        void blankBody() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));

            assertThatThrownBy(() -> service.reply(author, q.getUuid().toString(), req("   ", null)))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_854"));
            verify(replyRepository, never()).save(any());
        }

        @Test
        @DisplayName("explicit body → ContentModerationException, nothing saved")
        void explicitBody() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));
            when(moderationService.moderateText(anyString()))
                    .thenReturn(ModerationResult.explicit(ModerationResult.Category.ABUSE, 1.0, List.of("x")));

            assertThatThrownBy(() -> service.reply(author, q.getUuid().toString(), req("bad", null)))
                    .isInstanceOf(ContentModerationException.class);
            verify(replyRepository, never()).save(any());
        }

        @Test
        @DisplayName("question missing → NotFoundException TM_853")
        void questionMissing() {
            UUID id = UUID.randomUUID();
            when(questionRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reply(author, id.toString(), req("Answer", null)))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_853"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  delete (author-only)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("deleteMyQuestion / deleteMyReply")
    class Delete {

        @Test
        @DisplayName("author soft-deletes own question")
        void deletesOwnQuestion() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));

            service.deleteMyQuestion(author, q.getUuid().toString());

            ArgumentCaptor<AdviceQuestion> cap = ArgumentCaptor.forClass(AdviceQuestion.class);
            verify(questionRepository).save(cap.capture());
            assertThat(cap.getValue().isDeleted()).isTrue();
        }

        @Test
        @DisplayName("non-author cannot delete question → ForbiddenException TM_855")
        void nonAuthorCannotDeleteQuestion() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            when(questionRepository.findByUuid(q.getUuid())).thenReturn(Optional.of(q));

            assertThatThrownBy(() -> service.deleteMyQuestion(
                    user(99L, "intruder", "Intruder"), q.getUuid().toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_855"));
            verify(questionRepository, never()).save(any());
        }

        @Test
        @DisplayName("author soft-deletes own reply and decrements replyCount")
        void deletesOwnReply() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            q.setReplyCount(3);
            User replier = user(2L, "replier", "Replier");
            AdviceReply r = reply(10L, q, replier, null);
            when(replyRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            service.deleteMyReply(replier, r.getUuid().toString());

            ArgumentCaptor<AdviceReply> rcap = ArgumentCaptor.forClass(AdviceReply.class);
            verify(replyRepository).save(rcap.capture());
            assertThat(rcap.getValue().isDeleted()).isTrue();

            ArgumentCaptor<AdviceQuestion> qcap = ArgumentCaptor.forClass(AdviceQuestion.class);
            verify(questionRepository).save(qcap.capture());
            assertThat(qcap.getValue().getReplyCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("non-author cannot delete reply → ForbiddenException TM_855")
        void nonAuthorCannotDeleteReply() {
            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            AdviceReply r = reply(10L, q, user(2L, "replier", "Replier"), null);
            when(replyRepository.findByUuid(r.getUuid())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.deleteMyReply(
                    user(99L, "intruder", "Intruder"), r.getUuid().toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_855"));
            verify(replyRepository, never()).save(any());
        }

        @Test
        @DisplayName("missing reply → NotFoundException TM_853")
        void replyMissing() {
            UUID id = UUID.randomUUID();
            when(replyRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteMyReply(author, id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_853"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ANONYMITY INVARIANT — responses never carry author identity
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("anonymity invariant")
    class Anonymity {

        /**
         * The response DTOs must have NO field whose name references an author/user (compile-time
         * shape), and a serialised response must not leak the persisted author's username/name.
         */
        @Test
        @DisplayName("question + reply responses contain no author field and leak no author identity")
        void responsesNeverExposeAuthor() throws Exception {
            // (1) DTO shape: no author-ish fields at all.
            assertNoAuthorField(AdviceQuestionResponse.class);
            assertNoAuthorField(AdviceReplyResponse.class);

            // (2) End-to-end: a question + reply authored by a distinctively-named user serialise
            //     without any trace of that identity.
            moderationClean();
            when(questionRepository.countByAuthorAndCreatedAtAfter(any(), any())).thenReturn(0L);
            when(questionRepository.save(any())).thenAnswer(i -> {
                AdviceQuestion q = i.getArgument(0);
                q.setUuid(UUID.randomUUID());
                return q;
            });
            AdviceQuestionResponse qRes = service.askQuestion(
                    author, ask("Title", "Body", AdviceCategory.LIFE));

            AdviceQuestion q = question(1L, author, AdviceCategory.LIFE);
            when(questionRepository.findByUuid(any(UUID.class))).thenReturn(Optional.of(q));
            when(replyRepository.save(any())).thenAnswer(i -> {
                AdviceReply r = i.getArgument(0);
                r.setUuid(UUID.randomUUID());
                return r;
            });
            when(questionRepository.save(q)).thenReturn(q);
            AdviceReplyResponse rRes = service.reply(
                    author, q.getUuid().toString(),
                    ReplyRequest.builder().body("Reply body").build());

            ObjectMapper mapper = new ObjectMapper();
            String qJson = mapper.writeValueAsString(qRes);
            String rJson = mapper.writeValueAsString(rRes);
            assertThat(qJson).doesNotContain("secret-author-xyz").doesNotContain("Secret Author");
            assertThat(rJson).doesNotContain("secret-author-xyz").doesNotContain("Secret Author");
        }

        private void assertNoAuthorField(Class<?> dto) {
            for (Field f : dto.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                assertThat(n)
                        .as("DTO %s must not expose author identity via field '%s'", dto.getSimpleName(), f.getName())
                        .doesNotContain("author")
                        .doesNotContain("username")
                        .doesNotContain("useruuid")
                        .doesNotContain("avatar");
            }
        }
    }
}
