package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Feedback;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.FeedbackRequest;
import com.chat.talkMe.dto.response.FeedbackResponse;
import com.chat.talkMe.enums.FeedbackType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.repository.FeedbackRepository;
import com.chat.talkMe.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackRepository feedbackRepository;

    @Override
    @Transactional
    public FeedbackResponse submit(FeedbackRequest request, User currentUser) {
        int rating = Math.max(0, Math.min(5, request.getRating()));
        String reason = trimToNull(request.getReason());
        String comment = trimToNull(request.getComment());

        // Reject empty submissions — at least one signal must be present.
        if (rating == 0 && reason == null && comment == null) {
            throw new BadRequestException("Please share a rating or a comment.", "TM_312");
        }

        Feedback feedback = Feedback.builder()
                .user(currentUser)
                .rating(rating)
                .reason(reason)
                .comment(comment)
                .type(parseType(request.getType()))
                .contextRef(trimToNull(request.getContextRef()))
                .platform(trimToNull(request.getPlatform()))
                .build();

        feedback = feedbackRepository.save(feedback);
        log.info("Feedback submitted: type={} rating={} by userId={}", feedback.getType(), rating, currentUser.getId());
        return toResponse(feedback);
    }

    private static FeedbackType parseType(String raw) {
        if (!StringUtils.hasText(raw)) return FeedbackType.MANUAL;
        try {
            return FeedbackType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FeedbackType.OTHER;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static FeedbackResponse toResponse(Feedback f) {
        return FeedbackResponse.builder()
                .id(f.getUuid() != null ? f.getUuid().toString() : null)
                .rating(f.getRating())
                .reason(f.getReason())
                .comment(f.getComment())
                .type(f.getType() != null ? f.getType().name() : null)
                .contextRef(f.getContextRef())
                .status(f.getStatus() != null ? f.getStatus().name() : null)
                .createdAt(f.getCreatedAt() != null ? f.getCreatedAt().toString() : null)
                .build();
    }
}
