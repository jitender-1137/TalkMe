package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.StoryRequest;
import com.chat.talkMe.dto.response.StoryResponse;
import com.chat.talkMe.dto.response.StoryViewerResponse;

import java.util.List;

public interface StoryService {
    StoryResponse createStory(StoryRequest request, User currentUser);
    List<StoryResponse> getActiveStories(User currentUser);
    /** The current user's OWN stories, newest first, INCLUDING expired ones (archive). */
    List<StoryResponse> getMyStories(User currentUser);
    void deleteStory(String storyUuid, User currentUser);
    void viewStory(String storyUuid, User currentUser);
    /** Owner-only: who viewed this story, with per-viewer timestamps (most recent first). */
    List<StoryViewerResponse> getStoryViewers(String storyUuid, User currentUser);
}
