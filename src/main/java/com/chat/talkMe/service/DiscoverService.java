package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.DiscoverProfileResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;

public interface DiscoverService {
    PaginatedResponse<DiscoverProfileResponse> getDiscover(
            String query,
            String interests,
            Double distance,
            Boolean verified,
            Boolean isOnline,
            String cursor,
            int limit,
            User currentUser
    );
    void likeProfile(String userId, User currentUser);
    void unlikeProfile(String userId, User currentUser);
}
