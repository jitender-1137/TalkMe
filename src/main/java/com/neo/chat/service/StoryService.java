package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.StoryRequest;
import com.neo.chat.dto.response.StoryResponse;
import com.neo.chat.dto.response.StoryViewerResponse;

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
