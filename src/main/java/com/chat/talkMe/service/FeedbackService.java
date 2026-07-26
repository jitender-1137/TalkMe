package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.FeedbackRequest;
import com.chat.talkMe.dto.response.FeedbackResponse;

public interface FeedbackService {

    /** Persist a piece of feedback authored by {@code currentUser}. */
    FeedbackResponse submit(FeedbackRequest request, User currentUser);
}
