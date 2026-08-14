package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.FeedbackRequest;
import com.neo.chat.dto.response.FeedbackResponse;

public interface FeedbackService {

    /**
     * Persist a piece of feedback authored by {@code currentUser}.
     */
    FeedbackResponse submit(FeedbackRequest request, User currentUser);
}
