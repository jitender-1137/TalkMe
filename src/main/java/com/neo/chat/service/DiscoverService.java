package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.DiscoverProfileResponse;
import com.neo.chat.dto.response.PaginatedResponse;

/**
 * People-discovery search: filtered, presence-ranked, paginated user browsing plus like/unlike.
 */
public interface DiscoverService {
    PaginatedResponse<DiscoverProfileResponse> getDiscover(
            String query,
            String interests,
            Double distance,
            Boolean verified,
            Boolean isOnline,
            String cursor,
            int limit,
            Integer minAge,
            Integer maxAge,
            String gender,
            String country,
            User currentUser
    );

    void likeProfile(String userId, User currentUser);

    void unlikeProfile(String userId, User currentUser);
}
