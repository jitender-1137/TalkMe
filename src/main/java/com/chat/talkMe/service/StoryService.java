package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.StoryResponse;

import java.util.List;

public interface StoryService {
    StoryResponse createStory(StoryRequest request, User currentUser);
    List<StoryResponse> getActiveStories(User currentUser);
    void deleteStory(String storyUuid, User currentUser);
    void viewStory(String storyUuid, User currentUser);
    List<AuthUserResponse> getStoryViewers(String storyUuid, User currentUser);
}
