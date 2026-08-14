package com.neo.chat.controller;

import com.neo.chat.dto.request.BucketItemRequest;
import com.neo.chat.dto.response.BucketListResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.BucketListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * Fetch the shared bucket list for a chat (created on first access if absent).
     *
     * @param chatId      the chat's id
     * @param userDetails the authenticated principal (must be a chat member)
     * @return the {@link BucketListResponse} for the chat
     * @throws com.neo.chat.exception.BadRequestException if the chat id is invalid
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a chat member
     */
    @GetMapping
    @PreAuthorize("@featureGuard.check('BUCKET_LIST')")
    public ResponseEntity<ResponseDto<BucketListResponse>> getList(
            @PathVariable String chatId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BucketListResponse response = bucketListService.getList(userDetails.getUser(), chatId);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Add an item to the chat's bucket list; broadcasts the refreshed list over WS.
     *
     * @param chatId      the chat's id
     * @param request     the validated item request (item text)
     * @param userDetails the authenticated principal (must be a chat member)
     * @return the updated {@link BucketListResponse}
     * @throws com.neo.chat.exception.BadRequestException if the chat id is invalid or text is empty
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a chat member
     */
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

    /**
     * Toggle an item's done/undone state; broadcasts the refreshed list over WS.
     *
     * @param chatId      the chat's id
     * @param itemUuid    the bucket-list item's id
     * @param userDetails the authenticated principal (must be a chat member)
     * @return the updated {@link BucketListResponse}
     * @throws com.neo.chat.exception.BadRequestException if the chat id or item id is invalid
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a chat member
     */
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

    /**
     * Remove an item from the chat's bucket list; broadcasts the refreshed list over WS.
     *
     * @param chatId      the chat's id
     * @param itemUuid    the bucket-list item's id
     * @param userDetails the authenticated principal (must be a chat member)
     * @return the updated {@link BucketListResponse}
     * @throws com.neo.chat.exception.BadRequestException if the chat id or item id is invalid
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not a chat member
     */
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
