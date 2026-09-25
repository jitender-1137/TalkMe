package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.FriendRequestResponse;

import java.util.List;

/**
 * Mutual friendships and friend requests, plus user blocking/unblocking.
 */
public interface FriendService {
    FriendRequestResponse sendFriendRequest(String receiverUuid, User currentUser);

    void acceptFriendRequest(String requestUuid, User currentUser);

    void rejectFriendRequest(String requestUuid, User currentUser);

    /**
     * Accept or reject MANY pending friend requests in one call/transaction. Ids that are
     * missing, already processed, or not addressed to the caller are skipped (not fatal), so
     * one stale id never fails the batch. Replaces the client firing N per-request calls
     * (which tripped the rate limiter).
     *
     * @param requestUuids the pending request UUIDs to act on
     * @param accept       true to accept all, false to reject all
     * @param currentUser  the receiver acting on them
     * @return the number of requests actually processed
     */
    int respondToFriendRequests(List<String> requestUuids, boolean accept, User currentUser);

    void cancelFriendRequest(String requestUuid, User currentUser);

    List<AuthUserResponse> getFriends(User currentUser);

    List<FriendRequestResponse> getFriendRequests(User currentUser);

    void removeFriend(String friendUuid, User currentUser);

    void blockUser(String userUuid, User currentUser);

    void unblockUser(String userUuid, User currentUser);
}
