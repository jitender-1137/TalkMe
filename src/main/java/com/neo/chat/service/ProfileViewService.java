package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ProfileViewCountResponse;
import com.neo.chat.dto.response.ProfileViewResponse;
import com.neo.chat.enums.ProfileViewType;

import java.util.List;

/**
 * "Who viewed my profile": records profile/photo views and exposes viewer lists, counts, and seen state.
 */
public interface ProfileViewService {

    /**
     * Record that {@code viewer} opened {@code viewedUuid}'s profile/photo. Self-views are ignored.
     */
    void recordView(User viewer, String viewedUuid, ProfileViewType type);

    /**
     * Most-recent viewers of {@code currentUser}'s profile.
     */
    List<ProfileViewResponse> getViewers(User currentUser);

    /**
     * Total + unseen viewer counts for the badge.
     */
    ProfileViewCountResponse getCounts(User currentUser);

    /**
     * Clear the "new viewers" badge for {@code currentUser}.
     */
    void markAllSeen(User currentUser);
}
