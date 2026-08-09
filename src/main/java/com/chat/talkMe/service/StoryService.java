package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.StoryResponse;
import com.chat.talkMe.dto.response.StoryViewerResponse;

import java.util.List;

/**
 * Business operations for 24-hour stories. See {@code StoryServiceImpl} for behavior details.
 */
public interface StoryService {

    StoryResponse createStory(StoryRequest request, User currentUser);

    List<StoryResponse> getActiveStories(User currentUser);

    List<StoryResponse> getMyStories(User currentUser);

    void deleteStory(String storyUuid, User currentUser);

    void viewStory(String storyUuid, User currentUser);

    List<StoryViewerResponse> getStoryViewers(String storyUuid, User currentUser);
}
