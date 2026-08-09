package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.AdminCreateUserRequest;
import com.chat.talkMe.dto.request.AdminUpdateUserRequest;
import com.chat.talkMe.dto.request.AdminUserFilter;
import com.chat.talkMe.dto.response.AdminAnalyticsResponse;
import com.chat.talkMe.dto.response.AdminAttachmentView;
import com.chat.talkMe.dto.response.AdminAuditView;
import com.chat.talkMe.dto.response.AdminChatView;
import com.chat.talkMe.dto.response.AdminConnectorView;
import com.chat.talkMe.dto.response.AdminFeedbackView;
import com.chat.talkMe.dto.response.AdminMediaListResponse;
import com.chat.talkMe.dto.response.AdminMediaOwnershipResponse;
import com.chat.talkMe.dto.response.AdminMessageView;
import com.chat.talkMe.dto.response.AdminPostCommentView;
import com.chat.talkMe.dto.response.AdminPostLikeView;
import com.chat.talkMe.dto.response.AdminPostView;
import com.chat.talkMe.dto.response.AdminReportView;
import com.chat.talkMe.dto.response.AdminStatsResponse;
import com.chat.talkMe.dto.response.AdminStorageListResponse;
import com.chat.talkMe.dto.response.AdminTimeseriesPoint;
import com.chat.talkMe.dto.response.AdminTimeseriesResult;
import com.chat.talkMe.dto.response.AdminUserFullView;
import com.chat.talkMe.dto.response.AdminUserView;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SuperAdmin API. Every route requires ROLE_SUPER_ADMIN (class-level @PreAuthorize;
 * SecurityConfig also gates /api/v1/admin/** as defense-in-depth). Read/analytics
 * only in this phase — mutating actions (ban/delete/role) come next.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {

    private final AdminService adminService;

    /**
     * Platform-wide counters snapshot (totals for users, chats, messages, etc.).
     *
     * @return {@link AdminStatsResponse} wrapped in the standard success envelope
     */
    @GetMapping("/stats")
    public ResponseEntity<ResponseDto<AdminStatsResponse>> stats() {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getStats()));
    }

    /**
     * Paged, filtered list of users for the admin console.
     *
     * @param filter query/role/status filter bound from request params
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 25)
     * @return a page of {@link AdminUserView} rows
     */
    @GetMapping("/users")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminUserView>>> users(
            @ModelAttribute AdminUserFilter filter,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listUsers(filter, page, size)));
    }

    /**
     * Summary view of a single user.
     *
     * @param uuid the target user's UUID
     * @return the {@link AdminUserView} for that user
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @GetMapping("/users/{uuid}")
    public ResponseEntity<ResponseDto<AdminUserView>> user(@PathVariable("uuid") String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUser(uuid)));
    }

    /**
     * Full profile/activity detail view for a single user.
     *
     * @param uuid the target user's UUID
     * @return the {@link AdminUserFullView} for that user
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @GetMapping("/users/{uuid}/full")
    public ResponseEntity<ResponseDto<AdminUserFullView>> userFull(@PathVariable("uuid") String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUserFull(uuid)));
    }

    /**
     * All chats a given user participates in.
     *
     * @param uuid the target user's UUID
     * @return the list of {@link AdminChatView} the user belongs to
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @GetMapping("/users/{uuid}/chats")
    public ResponseEntity<ResponseDto<List<AdminChatView>>> userChats(@PathVariable("uuid") String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUserChats(uuid)));
    }

    /**
     * Paged search across all chats; soft-deleted chats are included by default (flagged).
     *
     * @param admin          the authenticated admin principal (recorded for audit)
     * @param query          optional free-text search term
     * @param type           optional chat-type filter
     * @param includeDeleted whether to include soft-deleted chats (default true)
     * @param page           zero-based page index (default 0)
     * @param size           page size (default 25)
     * @return a page of {@link AdminChatView} rows
     */
    @GetMapping("/chats")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminChatView>>> chats(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "type", required = false) String type,
            // Admin sees the FULL picture by default — soft-deleted chats included (flagged).
            @RequestParam(value = "includeDeleted", defaultValue = "true") boolean includeDeleted,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.listChats(query, type, includeDeleted, page, size, name(admin))));
    }

    /**
     * Paged, decrypted messages of one chat (admin read; access is audited).
     *
     * @param uuid  the chat's UUID
     * @param page  zero-based page index (default 0)
     * @param size  page size (default 50)
     * @param admin the authenticated admin principal (recorded for audit)
     * @return a page of {@link AdminMessageView} rows
     */
    @GetMapping("/chats/{uuid}/messages")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminMessageView>>> chatMessages(
            @PathVariable("uuid") String uuid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getChatMessages(uuid, page, size, name(admin))));
    }

    // ── Phase 2: moderation actions (audited) ────────────────────────────────

    /**
     * Ban or un-ban a user (audited moderation action).
     *
     * @param uuid   the target user's UUID
     * @param banned true to ban, false to lift the ban
     * @param admin  the authenticated admin principal (recorded for audit)
     * @return the updated {@link AdminUserView}
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @PostMapping("/users/{uuid}/ban")
    public ResponseEntity<ResponseDto<AdminUserView>> ban(
            @PathVariable("uuid") String uuid,
            @RequestParam("banned") boolean banned,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.setBanned(uuid, banned, name(admin))));
    }

    /**
     * Set or clear a user's verified flag (audited).
     *
     * @param uuid     the target user's UUID
     * @param verified true to mark verified, false to clear
     * @param admin    the authenticated admin principal (recorded for audit)
     * @return the updated {@link AdminUserView}
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @PostMapping("/users/{uuid}/verify")
    public ResponseEntity<ResponseDto<AdminUserView>> verify(
            @PathVariable("uuid") String uuid,
            @RequestParam("verified") boolean verified,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.setVerified(uuid, verified, name(admin))));
    }

    /**
     * Soft-delete or restore a user account (audited).
     *
     * @param uuid    the target user's UUID
     * @param deleted true to soft-delete, false to restore
     * @param admin   the authenticated admin principal (recorded for audit)
     * @return the updated {@link AdminUserView}
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @PostMapping("/users/{uuid}/soft-delete")
    public ResponseEntity<ResponseDto<AdminUserView>> softDelete(
            @PathVariable("uuid") String uuid,
            @RequestParam("deleted") boolean deleted,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.setSoftDeleted(uuid, deleted, name(admin))));
    }

    /**
     * Grant a role to a user (audited).
     *
     * @param uuid  the target user's UUID
     * @param role  the role name to grant
     * @param admin the authenticated admin principal (recorded for audit)
     * @return the updated {@link AdminUserView}
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if the role is not assignable
     */
    @PostMapping("/users/{uuid}/roles/grant")
    public ResponseEntity<ResponseDto<AdminUserView>> grantRole(
            @PathVariable("uuid") String uuid,
            @RequestParam("role") String role,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.grantRole(uuid, role, name(admin))));
    }

    /**
     * Revoke a role from a user (audited).
     *
     * @param uuid  the target user's UUID
     * @param role  the role name to revoke
     * @param admin the authenticated admin principal (recorded for audit)
     * @return the updated {@link AdminUserView}
     * @throws com.chat.talkMe.exception.NotFoundException   if no user matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if the role is not assignable
     */
    @PostMapping("/users/{uuid}/roles/revoke")
    public ResponseEntity<ResponseDto<AdminUserView>> revokeRole(
            @PathVariable("uuid") String uuid,
            @RequestParam("role") String role,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.revokeRole(uuid, role, name(admin))));
    }

    /**
     * Paged, filtered admin audit log.
     *
     * @param action     optional action-type filter
     * @param targetType optional target-type filter
     * @param admin      optional acting-admin username filter
     * @param from       optional inclusive start timestamp (string)
     * @param to         optional inclusive end timestamp (string)
     * @param page       zero-based page index (default 0)
     * @param size       page size (default 50)
     * @return a page of {@link AdminAuditView} entries
     */
    @GetMapping("/audit")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminAuditView>>> audit(
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "admin", required = false) String admin,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.listAudit(action, targetType, admin, from, to, page, size)));
    }

    // ── Phase 3: create / edit / delete + charts ─────────────────────────────

    /**
     * Create a new user account from the admin console (audited).
     *
     * @param req   the validated create-user request (credentials/profile)
     * @param admin the authenticated admin principal (recorded for audit)
     * @return the created {@link AdminUserView}
     * @throws com.chat.talkMe.exception.ConflictException if the username or email already exists
     */
    @PostMapping("/users")
    public ResponseEntity<ResponseDto<AdminUserView>> createUser(
            @Valid @RequestBody AdminCreateUserRequest req,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.createUser(req, name(admin))));
    }

    /**
     * Partially update an existing user's fields (audited).
     *
     * @param uuid  the target user's UUID
     * @param req   the fields to change
     * @param admin the authenticated admin principal (recorded for audit)
     * @return the updated {@link AdminUserView}
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     * @throws com.chat.talkMe.exception.ConflictException if a changed username or email
     *                                                     collides with another account
     */
    @PatchMapping("/users/{uuid}")
    public ResponseEntity<ResponseDto<AdminUserView>> updateUser(
            @PathVariable("uuid") String uuid,
            @RequestBody AdminUpdateUserRequest req,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.updateUser(uuid, req, name(admin))));
    }

    /**
     * Delete a single message (audited).
     *
     * @param uuid  the message's UUID
     * @param admin the authenticated admin principal (recorded for audit)
     * @return an empty success envelope confirming deletion
     */
    @DeleteMapping("/messages/{uuid}")
    public ResponseEntity<ResponseDto<Void>> deleteMessage(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails admin) {
        adminService.deleteMessage(uuid, name(admin));
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Message deleted", "TM_000"));
    }

    /**
     * Delete a chat (audited).
     *
     * @param uuid  the chat's UUID
     * @param admin the authenticated admin principal (recorded for audit)
     * @return an empty success envelope confirming deletion
     */
    @DeleteMapping("/chats/{uuid}")
    public ResponseEntity<ResponseDto<Void>> deleteChat(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails admin) {
        adminService.deleteChat(uuid, name(admin));
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat deleted", "TM_000"));
    }

    /**
     * Daily signup counts over the last N days.
     *
     * @param days number of trailing days to include (default 30)
     * @return the ordered list of {@link AdminTimeseriesPoint} signup buckets
     */
    @GetMapping("/stats/timeseries")
    public ResponseEntity<ResponseDto<List<AdminTimeseriesPoint>>> timeseries(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getSignupTimeseries(days)));
    }

    /**
     * Aggregate analytics dashboard payload for the given range.
     *
     * @param range range token such as {@code 30d} (default {@code 30d})
     * @return the {@link AdminAnalyticsResponse} aggregate metrics
     */
    @GetMapping("/analytics")
    public ResponseEntity<ResponseDto<AdminAnalyticsResponse>> analytics(
            @RequestParam(value = "range", defaultValue = "30d") String range) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getAnalytics(range)));
    }

    /**
     * Generic time-series for a chosen metric over a range/interval.
     *
     * @param metric   metric name to plot (default {@code messages})
     * @param range    range token such as {@code 30d} (default {@code 30d})
     * @param interval optional bucket interval override
     * @param from     optional explicit start timestamp (string)
     * @param to       optional explicit end timestamp (string)
     * @return the {@link AdminTimeseriesResult} for the requested metric
     */
    @GetMapping("/timeseries")
    public ResponseEntity<ResponseDto<AdminTimeseriesResult>> timeseriesMetric(
            @RequestParam(value = "metric", defaultValue = "messages") String metric,
            @RequestParam(value = "range", defaultValue = "30d") String range,
            @RequestParam(value = "interval", required = false) String interval,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getTimeseries(metric, range, interval, from, to)));
    }

    /**
     * Paged, filtered listing of message attachments.
     *
     * @param admin          the authenticated admin principal (recorded for audit)
     * @param userId         optional uploader-user filter
     * @param type           optional attachment-type filter
     * @param includeDeleted whether to include soft-deleted attachments (default false)
     * @param page           zero-based page index (default 0)
     * @param size           page size (default 30)
     * @return a page of {@link AdminAttachmentView} rows
     */
    @GetMapping("/attachments")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminAttachmentView>>> attachments(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getAttachments(userId, type, includeDeleted, page, size, name(admin))));
    }

    /**
     * Browse raw storage-backend objects with filtering, orphan detection and sorting.
     *
     * @param admin       the authenticated admin principal (recorded for audit)
     * @param prefix      optional key prefix to scope the listing
     * @param category    optional media category filter
     * @param kind        optional object-kind filter
     * @param onlyOrphans when true, return only objects with no owning DB record (default false)
     * @param search      optional free-text search on keys
     * @param sort        optional sort token
     * @param page        zero-based page index (default 0)
     * @param size        page size (default 40)
     * @return the {@link AdminStorageListResponse} page of storage objects
     */
    @GetMapping("/storage/objects")
    public ResponseEntity<ResponseDto<AdminStorageListResponse>> storageObjects(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "onlyOrphans", defaultValue = "false") boolean onlyOrphans,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "40") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getStorageObjects(
                prefix, category, kind, onlyOrphans, search, sort, page, size, name(admin))));
    }

    /**
     * Delete a single raw storage object by key (audited).
     *
     * @param admin the authenticated admin principal (recorded for audit)
     * @param key   the storage object key to delete
     * @return an empty success envelope confirming deletion
     * @throws com.chat.talkMe.exception.BadRequestException if the key is invalid
     */
    @DeleteMapping("/storage/object")
    public ResponseEntity<ResponseDto<Void>> deleteStorageObject(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam("key") String key) {
        adminService.deleteStorageObject(key, name(admin));
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Object deleted", "TM_281"));
    }

    // ── Media-ownership analytics (media_assets ledger) ─────────────────────────

    /**
     * Media-ownership analytics derived from the media_assets ledger.
     *
     * @param admin the authenticated admin principal (recorded for audit)
     * @param range range token such as {@code 30d} (default {@code 30d})
     * @return the {@link AdminMediaOwnershipResponse} ownership aggregates
     */
    @GetMapping("/media/stats")
    public ResponseEntity<ResponseDto<AdminMediaOwnershipResponse>> mediaStats(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam(value = "range", defaultValue = "30d") String range) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getMediaOwnership(range, name(admin))));
    }

    /**
     * Paged media assets attributed to a specific uploader.
     *
     * @param admin  the authenticated admin principal (recorded for audit)
     * @param userId the uploader user's id
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 24)
     * @return the {@link AdminMediaListResponse} page of that user's media
     */
    @GetMapping("/media/user")
    public ResponseEntity<ResponseDto<AdminMediaListResponse>> userMedia(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam("userId") String userId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getUserMedia(userId, page, size, name(admin))));
    }

    /**
     * Paged media assets belonging to a specific chat.
     *
     * @param admin  the authenticated admin principal (recorded for audit)
     * @param chatId the chat's id
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 24)
     * @return the {@link AdminMediaListResponse} page of that chat's media
     */
    @GetMapping("/media/chat")
    public ResponseEntity<ResponseDto<AdminMediaListResponse>> chatMedia(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getChatMedia(chatId, page, size, name(admin))));
    }

    /**
     * Paged listing of feed posts.
     *
     * @param page zero-based page index (default 0)
     * @param size page size (default 20)
     * @return a page of {@link AdminPostView} rows
     */
    @GetMapping("/posts")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminPostView>>> posts(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listPosts(page, size)));
    }

    /**
     * Paged list of users who liked a post.
     *
     * @param uuid the post's UUID
     * @param page zero-based page index (default 0)
     * @param size page size (default 50)
     * @return a page of {@link AdminPostLikeView} rows
     */
    @GetMapping("/posts/{uuid}/likes")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminPostLikeView>>> postLikes(
            @PathVariable String uuid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getPostLikes(uuid, page, size)));
    }

    /**
     * Paged list of comments on a post.
     *
     * @param uuid the post's UUID
     * @param page zero-based page index (default 0)
     * @param size page size (default 50)
     * @return a page of {@link AdminPostCommentView} rows
     */
    @GetMapping("/posts/{uuid}/comments")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminPostCommentView>>> postComments(
            @PathVariable String uuid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getPostComments(uuid, page, size)));
    }

    /**
     * Paged moderation-report queue, optionally filtered by status.
     *
     * @param status optional report-status filter
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20)
     * @return a page of {@link AdminReportView} rows
     */
    @GetMapping("/moderation/reports")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminReportView>>> reports(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listReports(status, page, size)));
    }

    /**
     * Detail view of a single moderation report.
     *
     * @param uuid the report's UUID
     * @return the {@link AdminReportView} for that report
     * @throws com.chat.talkMe.exception.NotFoundException if no report matches the UUID
     */
    @GetMapping("/moderation/reports/{uuid}")
    public ResponseEntity<ResponseDto<AdminReportView>> report(@PathVariable String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getReport(uuid)));
    }

    /**
     * Resolve a moderation report with a review action (audited).
     *
     * @param admin  the authenticated admin principal (recorded for audit)
     * @param uuid   the report's UUID
     * @param action the review action to apply
     * @param note   optional reviewer note
     * @return the updated {@link AdminReportView}
     * @throws com.chat.talkMe.exception.NotFoundException   if no report matches the UUID
     * @throws com.chat.talkMe.exception.BadRequestException if the review action is unknown
     */
    @PostMapping("/moderation/reports/{uuid}/review")
    public ResponseEntity<ResponseDto<AdminReportView>> reviewReport(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String uuid,
            @RequestParam("action") String action,
            @RequestParam(value = "note", required = false) String note) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.reviewReport(uuid, action, note, name(admin))));
    }

    // ── User feedback ─────────────────────────────────────────────────────────

    /**
     * Paged user-feedback queue, optionally filtered by type and status.
     *
     * @param type   optional feedback-type filter
     * @param status optional feedback-status filter
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20)
     * @return a page of {@link AdminFeedbackView} rows
     */
    @GetMapping("/feedback")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminFeedbackView>>> feedback(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listFeedback(type, status, page, size)));
    }

    /**
     * Update the workflow status of a feedback entry (audited).
     *
     * @param admin  the authenticated admin principal (recorded for audit)
     * @param uuid   the feedback entry's UUID
     * @param status the new status value
     * @return the updated {@link AdminFeedbackView}
     * @throws com.chat.talkMe.exception.BadRequestException if the status value is unknown
     */
    @PostMapping("/feedback/{uuid}/status")
    public ResponseEntity<ResponseDto<AdminFeedbackView>> updateFeedbackStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String uuid,
            @RequestParam("status") String status) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.updateFeedbackStatus(uuid, status, name(admin)), "Feedback updated", "TM_311"));
    }

    /**
     * The friend/connector list for a given user.
     *
     * @param userId the user's id whose friends to list
     * @return the list of {@link AdminConnectorView} connections
     */
    @GetMapping("/social/friends")
    public ResponseEntity<ResponseDto<List<AdminConnectorView>>> userFriends(
            @RequestParam("userId") String userId) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUserFriends(userId)));
    }

    private static String name(CustomUserDetails admin) {
        return admin != null ? admin.getUsername() : "unknown";
    }
}
