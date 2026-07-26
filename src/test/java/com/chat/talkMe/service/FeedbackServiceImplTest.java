package com.chat.talkMe.service;

import com.chat.talkMe.domain.Feedback;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.FeedbackRequest;
import com.chat.talkMe.dto.response.FeedbackResponse;
import com.chat.talkMe.enums.FeedbackType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.repository.FeedbackRepository;
import com.chat.talkMe.service.impl.FeedbackServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackServiceImpl.submit")
class FeedbackServiceImplTest {

    @Mock private FeedbackRepository feedbackRepository;

    @InjectMocks private FeedbackServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().username("u").email("u@e.com").name("U").build();
        user.setId(1L);
        // Echo the saved entity back with a uuid/timestamp (JPA would set these).
        // Lenient: the empty-submission test rejects before ever saving.
        org.mockito.Mockito.lenient().when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> {
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
        verify(feedbackRepository, org.mockito.Mockito.times(2)).save(saved.capture());
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
}
