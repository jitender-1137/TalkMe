package com.neo.chat.service;

import com.neo.chat.domain.Feedback;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.FeedbackRequest;
import com.neo.chat.dto.response.FeedbackResponse;
import com.neo.chat.enums.FeedbackType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.FeedbackRepository;
import com.neo.chat.service.impl.FeedbackServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test ({@link MockitoExtension}) for {@link FeedbackServiceImpl#submit} — the
 * single write path for user feedback. Verifies text trimming, rating clamping into 0–5, type
 * resolution ({@link FeedbackType}: blank → MANUAL, unknown → OTHER, valid enum names honoured), the
 * "at least one of rating/reason/comment" guard that rejects empty submissions with
 * {@link BadRequestException}, and the entity → {@link FeedbackResponse} mapping including its null
 * branches. The {@link FeedbackRepository} is mocked; its {@code save} stub (lenient) echoes the
 * entity back with a generated uuid/timestamp to mimic JPA.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackServiceImpl.submit")
class FeedbackServiceImplTest {

    @Mock
    private FeedbackRepository feedbackRepository;

    @InjectMocks
    private FeedbackServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().username("u").email("u@e.com").name("U").build();
        user.setId(1L);
        // Echo the saved entity back with a uuid/timestamp (JPA would set these).
        // Lenient: the empty-submission test rejects before ever saving.
        Mockito.lenient().when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setUuid(UUID.randomUUID());
            f.setCreatedAt(Instant.now());
            return f;
        });
    }

    private static FeedbackRequest req(int rating, String reason, String comment, String type) {
        FeedbackRequest r = new FeedbackRequest();
        r.setRating(rating);
        r.setReason(reason);
        r.setComment(comment);
        r.setType(type);
        return r;
    }

    @Test
    void shouldPersistWithTrimmedTextAndResolvedType() {
        FeedbackResponse res = service.submit(req(5, "  Compliment  ", "  Love it  ", "manual"), user);

        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        Feedback f = saved.getValue();
        assertThat(f.getUser()).isEqualTo(user);
        assertThat(f.getRating()).isEqualTo(5);
        assertThat(f.getReason()).isEqualTo("Compliment");
        assertThat(f.getComment()).isEqualTo("Love it");
        assertThat(f.getType()).isEqualTo(FeedbackType.MANUAL);

        assertThat(res.getId()).isNotBlank();
        assertThat(res.getRating()).isEqualTo(5);
        assertThat(res.getType()).isEqualTo("MANUAL");
    }

    @Test
    void shouldClampOutOfRangeRating() {
        service.submit(req(9, null, "great", "MANUAL"), user);
        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getRating()).isEqualTo(5);
    }

    @Test
    void shouldDefaultBlankTypeToManualAndUnknownToOther() {
        service.submit(req(4, null, "x", "   "), user);
        service.submit(req(4, null, "x", "NONSENSE"), user);
        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getType()).isEqualTo(FeedbackType.MANUAL);
        assertThat(saved.getAllValues().get(1).getType()).isEqualTo(FeedbackType.OTHER);
    }

    @Test
    void shouldAcceptReasonOnlySubmission() {
        service.submit(req(0, "Too many messages", null, "LEAVE_GROUP"), user);
        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(FeedbackType.LEAVE_GROUP);
        assertThat(saved.getValue().getReason()).isEqualTo("Too many messages");
    }

    @Test
    void shouldRejectFullyEmptySubmission() {
        assertThatThrownBy(() -> service.submit(req(0, "   ", "  ", "MANUAL"), user))
                .isInstanceOf(BadRequestException.class);
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void shouldAcceptCommentOnlySubmission() {
        // rating 0 + no reason but a comment → guard's comment==null sub-condition is false, persists.
        service.submit(req(0, null, "Just a note", "MANUAL"), user);
        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getComment()).isEqualTo("Just a note");
        assertThat(saved.getValue().getRating()).isZero();
    }

    @Test
    void shouldMapNullEntityFieldsToNullResponseFields() {
        // Saved entity with no uuid/type/status/createdAt exercises the null sides of toResponse.
        // type/status carry @Builder.Default values, so null them explicitly to hit the null branch.
        Feedback saved = new Feedback();
        saved.setType(null);
        saved.setStatus(null);
        when(feedbackRepository.save(any(Feedback.class))).thenReturn(saved);

        FeedbackResponse res = service.submit(req(3, null, "ok", "MANUAL"), user);

        assertThat(res.getId()).isNull();
        assertThat(res.getType()).isNull();
        assertThat(res.getStatus()).isNull();
        assertThat(res.getCreatedAt()).isNull();
    }
}
