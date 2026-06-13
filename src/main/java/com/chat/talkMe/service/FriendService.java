package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.FriendRequestResponse;

import java.util.List;

public interface FriendService {
    FriendRequestResponse sendFriendRequest(String receiverUuid, User currentUser);
    void acceptFriendRequest(String requestUuid, User currentUser);
    void rejectFriendRequest(String requestUuid, User currentUser);
    void cancelFriendRequest(String requestUuid, User currentUser);
    List<AuthUserResponse> getFriends(User currentUser);
    List<FriendRequestResponse> getFriendRequests(User currentUser);
    void removeFriend(String friendUuid, User currentUser);
    void blockUser(String userUuid, User currentUser);
    void unblockUser(String userUuid, User currentUser);
}
