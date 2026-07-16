package com.chat.talkMe.service;

import com.chat.talkMe.dto.response.AdminChatView;
import com.chat.talkMe.dto.response.AdminMessageView;
import com.chat.talkMe.dto.response.AdminStatsResponse;
import com.chat.talkMe.dto.response.AdminUserView;
import com.chat.talkMe.dto.response.PaginatedResponse;

import java.util.List;

/** SuperAdmin read/analytics surface. All methods assume the caller is ROLE_SUPER_ADMIN. */
public interface AdminService {
    AdminStatsResponse getStats();
    PaginatedResponse<AdminUserView> listUsers(com.chat.talkMe.dto.request.AdminUserFilter filter, int page, int size);
    AdminUserView getUser(String uuid);
    /** The COMPLETE persisted record (all columns + settings + presence) for a user. */
    com.chat.talkMe.dto.response.AdminUserFullView getUserFull(String uuid);
    List<AdminChatView> getUserChats(String uuid);
    /** Decrypted messages for a chat. adminUsername is logged for the access trail. */
    PaginatedResponse<AdminMessageView> getChatMessages(String chatUuid, int page, int size, String adminUsername);

    // ── Phase 2: moderation mutations (all audited) ───────────────────────────
    AdminUserView setBanned(String uuid, boolean banned, String adminUsername);
    AdminUserView setVerified(String uuid, boolean verified, String adminUsername);
    AdminUserView setSoftDeleted(String uuid, boolean deleted, String adminUsername);
    AdminUserView grantRole(String uuid, String roleName, String adminUsername);
    AdminUserView revokeRole(String uuid, String roleName, String adminUsername);

    com.chat.talkMe.dto.response.PaginatedResponse<com.chat.talkMe.dto.response.AdminAuditView>
        listAudit(int page, int size);

    // ── Phase 3: create / edit / delete + charts ──────────────────────────────
    AdminUserView createUser(com.chat.talkMe.dto.request.AdminCreateUserRequest req, String adminUsername);
    AdminUserView updateUser(String uuid, com.chat.talkMe.dto.request.AdminUpdateUserRequest req, String adminUsername);
    void deleteMessage(String messageUuid, String adminUsername);
    void deleteChat(String chatUuid, String adminUsername);
    java.util.List<com.chat.talkMe.dto.response.AdminTimeseriesPoint> getSignupTimeseries(int days);

    /**
     * Everything-in-one analytics: totals, breakdowns and time series over a range
     * key (1h / 6h / 12h / 24h / 7d / 30d / 90d / 1y). Sub-day ranges bucket hourly.
     */
    com.chat.talkMe.dto.response.AdminAnalyticsResponse getAnalytics(String range);

    /** Direct friends of a user (each with their own friend count) — powers the hierarchy tree. */
    java.util.List<com.chat.talkMe.dto.response.AdminConnectorView> getUserFriends(String userUuid);

    /**
     * Attachments across the platform (decrypted URLs), newest first — with sender
     * and "shared with" recipients. Optionally filtered by sender uuid and/or type.
     * adminUsername is logged for the access trail.
     */
    PaginatedResponse<com.chat.talkMe.dto.response.AdminAttachmentView> getAttachments(
        String userUuid, String type, int page, int size, String adminUsername);

    // ── News / feed ───────────────────────────────────────────────────────────
    PaginatedResponse<com.chat.talkMe.dto.response.AdminPostView> listPosts(int page, int size);
    PaginatedResponse<com.chat.talkMe.dto.response.AdminPostLikeView> getPostLikes(String postUuid, int page, int size);
    PaginatedResponse<com.chat.talkMe.dto.response.AdminPostCommentView> getPostComments(String postUuid, int page, int size);

    /**
     * One metric's time series with a caller-chosen window and interval.
     * metric = messages | signups | attachments; range is a key (1h..1y) OR use
     * from/to ISO instants for a custom window; interval overrides the bucket size.
     */
    com.chat.talkMe.dto.response.AdminTimeseriesResult getTimeseries(
        String metric, String range, String interval, String fromIso, String toIso);
}
