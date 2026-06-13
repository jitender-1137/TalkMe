package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.NotificationResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.NotificationService;
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

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ResponseDto<Page<NotificationResponse>>> getNotifications(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<NotificationResponse> response = notificationService.getNotifications(pageable, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<ResponseDto<Void>> markAsRead(
            @PathVariable("id") String notificationUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.markAsRead(notificationUuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Notification marked as read", "TM_252"));
    }

    @PutMapping("/read-all")
    public ResponseEntity<ResponseDto<Void>> markAllAsRead(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.markAllAsRead(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "All notifications marked as read", "TM_253"));
    }
}
