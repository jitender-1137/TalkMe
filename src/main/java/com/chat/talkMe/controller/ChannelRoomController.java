package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Dedicated create endpoints for the two multi-party subtypes, so the URLs read
 * cleanly: POST /chats/channel and POST /chats/room. Both reuse the unified
 * group-creation path (a channel/room is a Chat). Management (members, discover,
 * join, …) stays under /chats/group/**.
 */
@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ChannelRoomController {

    private final GroupService groupService;

    @PostMapping("/channel")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createChannel(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        request.setSubtype("channel");
        ChatResponse response = groupService.createGroup(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Channel created successfully", "TM_280"));
    }

    @PostMapping("/room")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createRoom(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        request.setSubtype("room");
        ChatResponse response = groupService.createGroup(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Room created successfully", "TM_280"));
    }
}
