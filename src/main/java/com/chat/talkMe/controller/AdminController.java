package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.AdminChatView;
import com.chat.talkMe.dto.response.AdminMessageView;
import com.chat.talkMe.dto.response.AdminStatsResponse;
import com.chat.talkMe.dto.response.AdminUserView;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/stats")
    public ResponseEntity<ResponseDto<AdminStatsResponse>> stats() {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getStats()));
    }

    @GetMapping("/users")
    public ResponseEntity<ResponseDto<PaginatedResponse<AdminUserView>>> users(
            @org.springframework.web.bind.annotation.ModelAttribute com.chat.talkMe.dto.request.AdminUserFilter filter,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listUsers(filter, page, size)));
    }

    @GetMapping("/users/{uuid}")
    public ResponseEntity<ResponseDto<AdminUserView>> user(@PathVariable("uuid") String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUser(uuid)));
    }

    @GetMapping("/users/{uuid}/full")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.AdminUserFullView>> userFull(@PathVariable("uuid") String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUserFull(uuid)));
    }

    @GetMapping("/users/{uuid}/chats")
    public ResponseEntity<ResponseDto<List<AdminChatView>>> userChats(@PathVariable("uuid") String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUserChats(uuid)));
    }

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

    @PostMapping("/users/{uuid}/ban")
    public ResponseEntity<ResponseDto<AdminUserView>> ban(
            @PathVariable("uuid") String uuid,
            @RequestParam("banned") boolean banned,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.setBanned(uuid, banned, name(admin))));
    }

    @PostMapping("/users/{uuid}/verify")
    public ResponseEntity<ResponseDto<AdminUserView>> verify(
            @PathVariable("uuid") String uuid,
            @RequestParam("verified") boolean verified,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.setVerified(uuid, verified, name(admin))));
    }

    @PostMapping("/users/{uuid}/soft-delete")
    public ResponseEntity<ResponseDto<AdminUserView>> softDelete(
            @PathVariable("uuid") String uuid,
            @RequestParam("deleted") boolean deleted,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.setSoftDeleted(uuid, deleted, name(admin))));
    }

    @PostMapping("/users/{uuid}/roles/grant")
    public ResponseEntity<ResponseDto<AdminUserView>> grantRole(
            @PathVariable("uuid") String uuid,
            @RequestParam("role") String role,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.grantRole(uuid, role, name(admin))));
    }

    @PostMapping("/users/{uuid}/roles/revoke")
    public ResponseEntity<ResponseDto<AdminUserView>> revokeRole(
            @PathVariable("uuid") String uuid,
            @RequestParam("role") String role,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.revokeRole(uuid, role, name(admin))));
    }

    @GetMapping("/audit")
    public ResponseEntity<ResponseDto<PaginatedResponse<com.chat.talkMe.dto.response.AdminAuditView>>> audit(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listAudit(page, size)));
    }

    // ── Phase 3: create / edit / delete + charts ─────────────────────────────

    @PostMapping("/users")
    public ResponseEntity<ResponseDto<AdminUserView>> createUser(
            @jakarta.validation.Valid @RequestBody com.chat.talkMe.dto.request.AdminCreateUserRequest req,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.createUser(req, name(admin))));
    }

    @PatchMapping("/users/{uuid}")
    public ResponseEntity<ResponseDto<AdminUserView>> updateUser(
            @PathVariable("uuid") String uuid,
            @RequestBody com.chat.talkMe.dto.request.AdminUpdateUserRequest req,
            @AuthenticationPrincipal CustomUserDetails admin) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.updateUser(uuid, req, name(admin))));
    }

    @DeleteMapping("/messages/{uuid}")
    public ResponseEntity<ResponseDto<Void>> deleteMessage(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails admin) {
        adminService.deleteMessage(uuid, name(admin));
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Message deleted", "TM_000"));
    }

    @DeleteMapping("/chats/{uuid}")
    public ResponseEntity<ResponseDto<Void>> deleteChat(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails admin) {
        adminService.deleteChat(uuid, name(admin));
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Chat deleted", "TM_000"));
    }

    @GetMapping("/stats/timeseries")
    public ResponseEntity<ResponseDto<java.util.List<com.chat.talkMe.dto.response.AdminTimeseriesPoint>>> timeseries(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getSignupTimeseries(days)));
    }

    @GetMapping("/analytics")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.AdminAnalyticsResponse>> analytics(
            @RequestParam(value = "range", defaultValue = "30d") String range) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getAnalytics(range)));
    }

    @GetMapping("/timeseries")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.AdminTimeseriesResult>> timeseriesMetric(
            @RequestParam(value = "metric", defaultValue = "messages") String metric,
            @RequestParam(value = "range", defaultValue = "30d") String range,
            @RequestParam(value = "interval", required = false) String interval,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getTimeseries(metric, range, interval, from, to)));
    }

    @GetMapping("/attachments")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.PaginatedResponse<com.chat.talkMe.dto.response.AdminAttachmentView>>> attachments(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.getAttachments(userId, type, includeDeleted, page, size, name(admin))));
    }

    @GetMapping("/storage/objects")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.AdminStorageListResponse>> storageObjects(
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

    @DeleteMapping("/storage/object")
    public ResponseEntity<ResponseDto<Void>> deleteStorageObject(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam("key") String key) {
        adminService.deleteStorageObject(key, name(admin));
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Object deleted", "TM_281"));
    }

    @GetMapping("/posts")
    public ResponseEntity<ResponseDto<PaginatedResponse<com.chat.talkMe.dto.response.AdminPostView>>> posts(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listPosts(page, size)));
    }

    @GetMapping("/posts/{uuid}/likes")
    public ResponseEntity<ResponseDto<PaginatedResponse<com.chat.talkMe.dto.response.AdminPostLikeView>>> postLikes(
            @PathVariable String uuid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getPostLikes(uuid, page, size)));
    }

    @GetMapping("/posts/{uuid}/comments")
    public ResponseEntity<ResponseDto<PaginatedResponse<com.chat.talkMe.dto.response.AdminPostCommentView>>> postComments(
            @PathVariable String uuid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getPostComments(uuid, page, size)));
    }

    @GetMapping("/moderation/reports")
    public ResponseEntity<ResponseDto<PaginatedResponse<com.chat.talkMe.dto.response.AdminReportView>>> reports(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.listReports(status, page, size)));
    }

    @GetMapping("/moderation/reports/{uuid}")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.AdminReportView>> report(@PathVariable String uuid) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getReport(uuid)));
    }

    @PostMapping("/moderation/reports/{uuid}/review")
    public ResponseEntity<ResponseDto<com.chat.talkMe.dto.response.AdminReportView>> reviewReport(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String uuid,
            @RequestParam("action") String action,
            @RequestParam(value = "note", required = false) String note) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                adminService.reviewReport(uuid, action, note, name(admin))));
    }

    @GetMapping("/social/friends")
    public ResponseEntity<ResponseDto<java.util.List<com.chat.talkMe.dto.response.AdminConnectorView>>> userFriends(
            @RequestParam("userId") String userId) {
        return ResponseEntity.ok(SuccessResponseDto.success(adminService.getUserFriends(userId)));
    }

    private static String name(CustomUserDetails admin) {
        return admin != null ? admin.getUsername() : "unknown";
    }
}
