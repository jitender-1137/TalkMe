package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.BucketItemRequest;
import com.chat.talkMe.dto.response.BucketListResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.BucketListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Shared Bucket List surface (feature #18). Every route is gated by the BUCKET_LIST
 * feature and membership-checked inside the service. Mutations broadcast the refreshed
 * list live over WS to {@code /topic/chat/{chatId}/bucket-list}.
 */
@RestController
@RequestMapping("/chats/{chatId}/bucket-list")
@RequiredArgsConstructor
public class BucketListController {

    private final BucketListService bucketListService;

    @GetMapping
    @PreAuthorize("@featureGuard.check('BUCKET_LIST')")
    public ResponseEntity<ResponseDto<BucketListResponse>> getList(
            @PathVariable String chatId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BucketListResponse response = bucketListService.getList(userDetails.getUser(), chatId);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/items")
    @PreAuthorize("@featureGuard.check('BUCKET_LIST')")
    public ResponseEntity<ResponseDto<BucketListResponse>> addItem(
            @PathVariable String chatId,
            @Valid @RequestBody BucketItemRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BucketListResponse response =
                bucketListService.addItem(userDetails.getUser(), chatId, request.getText());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Item added", "TM_813"));
    }

    @PostMapping("/items/{itemUuid}/toggle")
    @PreAuthorize("@featureGuard.check('BUCKET_LIST')")
    public ResponseEntity<ResponseDto<BucketListResponse>> toggleItem(
            @PathVariable String chatId,
            @PathVariable String itemUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BucketListResponse response =
                bucketListService.toggleItem(userDetails.getUser(), chatId, itemUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Item updated", "TM_814"));
    }

    @DeleteMapping("/items/{itemUuid}")
    @PreAuthorize("@featureGuard.check('BUCKET_LIST')")
    public ResponseEntity<ResponseDto<BucketListResponse>> removeItem(
            @PathVariable String chatId,
            @PathVariable String itemUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BucketListResponse response =
                bucketListService.removeItem(userDetails.getUser(), chatId, itemUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Item removed", "TM_815"));
    }
}
