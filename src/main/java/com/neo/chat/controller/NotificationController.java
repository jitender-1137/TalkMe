package com.neo.chat.controller;

import com.neo.chat.dto.response.NotificationResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app notification inbox for the authenticated user: paged listing and read-state updates.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * Returns the current user's notifications, newest first.
     *
     * @param pageable    pagination (default size 20, sorted by createdAt DESC)
     * @param userDetails authenticated caller
     * @return a page of {@link NotificationResponse}
     */
    @GetMapping
    public ResponseEntity<ResponseDto<Page<NotificationResponse>>> getNotifications(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<NotificationResponse> response = notificationService.getNotifications(pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Marks a single notification (owned by the caller) as read.
     *
     * @param notificationUuid UUID of the notification to mark read
     * @param userDetails      authenticated caller (must own the notification)
     * @return an empty success response
     * @throws com.neo.chat.exception.NotFoundException if no such notification exists for this user
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<ResponseDto<Void>> markAsRead(
            @PathVariable("id") String notificationUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.markAsRead(notificationUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Notification marked as read", "TM_252"));
    }

    /**
     * Marks all the caller's notifications as read.
     *
     * @param userDetails authenticated caller
     * @return an empty success response
     */
    @PutMapping("/read-all")
    public ResponseEntity<ResponseDto<Void>> markAllAsRead(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.markAllAsRead(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "All notifications marked as read", "TM_253"));
    }
}
