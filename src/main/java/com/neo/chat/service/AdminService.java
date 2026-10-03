package com.neo.chat.service;

import com.neo.chat.dto.request.AdminCreateUserRequest;
import com.neo.chat.dto.request.AdminUpdateUserRequest;
import com.neo.chat.dto.request.AdminUserFilter;
import com.neo.chat.dto.response.AdminAnalyticsResponse;
import com.neo.chat.dto.response.AdminAttachmentView;
import com.neo.chat.dto.response.AdminAuditView;
import com.neo.chat.dto.response.AdminChatView;
import com.neo.chat.dto.response.UsernameHistoryView;
import com.neo.chat.dto.response.AdminConnectorView;
import com.neo.chat.dto.response.AdminFeedbackView;
import com.neo.chat.dto.response.AdminMediaListResponse;
import com.neo.chat.dto.response.AdminMediaOwnershipResponse;
import com.neo.chat.dto.response.AdminMessageView;
import com.neo.chat.dto.response.AdminPostCommentView;
import com.neo.chat.dto.response.AdminPostLikeView;
import com.neo.chat.dto.response.AdminPostView;
import com.neo.chat.dto.response.AdminReportView;
import com.neo.chat.dto.response.AdminStatsResponse;
import com.neo.chat.dto.response.AdminStorageListResponse;
import com.neo.chat.dto.response.AdminTimeseriesPoint;
import com.neo.chat.dto.response.AdminTimeseriesResult;
import com.neo.chat.dto.response.AdminUserFullView;
import com.neo.chat.dto.response.AdminUserView;
import com.neo.chat.dto.response.PaginatedResponse;

import java.util.List;

/**
 * SuperAdmin read/analytics surface. All methods assume the caller is ROLE_SUPER_ADMIN.
 */
public interface AdminService {
    AdminStatsResponse getStats();

    PaginatedResponse<AdminUserView> listUsers(AdminUserFilter filter, int page, int size);

    AdminUserView getUser(String uuid);

    /**
     * The COMPLETE persisted record (all columns + settings + presence) for a user.
     */
    AdminUserFullView getUserFull(String uuid);

    List<AdminChatView> getUserChats(String uuid);

    /** Every username change recorded for a user, newest first (admin-only audit trail). */
    List<UsernameHistoryView> getUsernameHistory(String uuid);

    /**
     * Decrypted messages for a chat. adminUsername is logged for the access trail.
     */
    PaginatedResponse<AdminMessageView> getChatMessages(String chatUuid, int page, int size, String adminUsername);

    /**
     * Admin: page ALL chats (optional type filter + search over name/members). Audited.
     */
    PaginatedResponse<AdminChatView> listChats(
            String query, String type, boolean includeDeleted, int page, int size, String adminUsername);

    // ── Phase 2: moderation mutations (all audited) ───────────────────────────
    AdminUserView setBanned(String uuid, boolean banned, String adminUsername);

    AdminUserView setVerified(String uuid, boolean verified, String adminUsername);

    AdminUserView setSoftDeleted(String uuid, boolean deleted, String adminUsername);

    AdminUserView grantRole(String uuid, String roleName, String adminUsername);

    AdminUserView revokeRole(String uuid, String roleName, String adminUsername);

    PaginatedResponse<AdminAuditView>
    listAudit(String action, String targetType, String admin, String from, String to, int page, int size);

    // ── Phase 3: create / edit / delete + charts ──────────────────────────────
    AdminUserView createUser(AdminCreateUserRequest req, String adminUsername);

    AdminUserView updateUser(String uuid, AdminUpdateUserRequest req, String adminUsername);

    void deleteMessage(String messageUuid, String adminUsername);

    void deleteChat(String chatUuid, String adminUsername);

    List<AdminTimeseriesPoint> getSignupTimeseries(int days);

    /**
     * Everything-in-one analytics: totals, breakdowns and time series over a range
     * key (1h / 6h / 12h / 24h / 7d / 30d / 90d / 1y). Sub-day ranges bucket hourly.
     */
    AdminAnalyticsResponse getAnalytics(String range);

    /**
     * Direct friends of a user (each with their own friend count) — powers the hierarchy tree.
     */
    List<AdminConnectorView> getUserFriends(String userUuid);

    /**
     * Attachments across the platform (decrypted URLs), newest first — with sender
     * and "shared with" recipients. Optionally filtered by sender uuid and/or type.
     * adminUsername is logged for the access trail.
     */
    PaginatedResponse<AdminAttachmentView> getAttachments(
            String userUuid, String type, boolean includeDeleted, int page, int size, String adminUsername);

    /**
     * Storage-truth listing for the Attachments gallery: lists physical objects in the
     * media store and reconciles them against DB attachments, tagging orphans (in
     * storage, no DB row). Counts are storage-accurate. {@code kind} = image/video/
     * audio/file; {@code category} filters by top-level folder; {@code onlyOrphans}
     * restricts to unreferenced chat-media.
     */
    AdminStorageListResponse getStorageObjects(
            String prefix, String category, String kind, boolean onlyOrphans,
            String search, String sort, int page, int size, String adminUsername);

    /**
     * Delete a single physical object (by key) from the media store. Audited.
     */
    void deleteStorageObject(String key, String adminUsername);

    // ── Media-ownership analytics (media_assets ledger) ───────────────────────

    /**
     * Dashboard analytics over the upload-ownership ledger; range keys as elsewhere.
     */
    AdminMediaOwnershipResponse getMediaOwnership(String range, String adminUsername);

    /**
     * A single user's uploads (incl. now-attributable stranger/lobby media), paged.
     */
    AdminMediaListResponse getUserMedia(
            String userUuid, int page, int size, String adminUsername);

    /**
     * Media uploaded in one conversation (by chat uuid), paged, owners included.
     */
    AdminMediaListResponse getChatMedia(
            String chatUuid, int page, int size, String adminUsername);

    // ── News / feed ───────────────────────────────────────────────────────────
    PaginatedResponse<AdminPostView> listPosts(int page, int size);

    PaginatedResponse<AdminPostLikeView> getPostLikes(String postUuid, int page, int size);

    PaginatedResponse<AdminPostCommentView> getPostComments(String postUuid, int page, int size);

    // ── Moderation report review portal ───────────────────────────────────────
    PaginatedResponse<AdminReportView> listReports(String status, int page, int size);

    AdminReportView getReport(String reportUuid);

    /**
     * action = DISMISS | RESOLVE | BAN_REPORTED. Records reviewer + note; audited.
     */
    AdminReportView reviewReport(String reportUuid, String action, String note, String adminUsername);

    // ── User feedback ─────────────────────────────────────────────────────────

    /**
     * Paginated feedback list; type/status are optional filters ("ALL"/blank = no filter).
     */
    PaginatedResponse<AdminFeedbackView> listFeedback(String type, String status, int page, int size);

    /**
     * Move a feedback entry to NEW | REVIEWED | ARCHIVED; audited.
     */
    AdminFeedbackView updateFeedbackStatus(String feedbackUuid, String status, String adminUsername);

    /**
     * One metric's time series with a caller-chosen window and interval.
     * metric = messages | signups | attachments; range is a key (1h..1y) OR use
     * from/to ISO instants for a custom window; interval overrides the bucket size.
     */
    AdminTimeseriesResult getTimeseries(
            String metric, String range, String interval, String fromIso, String toIso);
}
