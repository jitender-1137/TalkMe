package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ProfileViewCountResponse;
import com.chat.talkMe.dto.response.ProfileViewResponse;
import com.chat.talkMe.enums.ProfileViewType;

import java.util.List;

public interface ProfileViewService {

    /** Record that {@code viewer} opened {@code viewedUuid}'s profile/photo. Self-views are ignored. */
    void recordView(User viewer, String viewedUuid, ProfileViewType type);

    /** Most-recent viewers of {@code currentUser}'s profile. */
    List<ProfileViewResponse> getViewers(User currentUser);

    /** Total + unseen viewer counts for the badge. */
    ProfileViewCountResponse getCounts(User currentUser);

    /** Clear the "new viewers" badge for {@code currentUser}. */
    void markAllSeen(User currentUser);
}
