package com.neo.chat.controller;

import com.neo.chat.dto.request.CreateGroupRequest;
import com.neo.chat.dto.response.ChatResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

/**
 * Dedicated create endpoints for the two multi-party subtypes, so the URLs read
 * cleanly: POST /chats/channel and POST /chats/room. Both reuse the unified
 * group-creation path (a channel/room is a Chat). Management (members, discover,
 * join, …) stays under /chats/group/**.
 */
@RestController
@Tag(name = "Channel Room", description = "Dedicated create endpoints for the two multi-party subtypes, so the URLs read cleanly: POST /chats/channel and POST /chats/room")
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ChannelRoomController {

    private final GroupService groupService;

    /**
     * Creates a broadcast channel by forcing the request subtype to "channel" and delegating to the
     * unified group-creation path. Channels are public/open-to-subscribe with an admins-only send policy.
     *
     * @param request     the group-creation payload (name, description, tags, etc.); subtype is overridden
     *                    to "channel" before delegation
     * @param userDetails the authenticated principal; its user becomes the channel owner
     * @return 200 with the created {@link ChatResponse} wrapped in a success envelope (code TM_280)
     */
    @Operation(summary = "Creates a broadcast channel by forcing the request subtype to \"channel\" and delegating to the unified group-creation path")
    @PostMapping(value = "/channel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createChannel(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        request.setSubtype("channel");
        ChatResponse response = groupService.createGroup(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Channel created successfully", "TM_280"));
    }

    /**
     * Creates a public room by forcing the request subtype to "room" and delegating to the unified
     * group-creation path. Rooms are always public, open-to-join, and allow non-friends.
     *
     * @param request     the group-creation payload; subtype is overridden to "room" before delegation
     * @param userDetails the authenticated principal; its user becomes the room owner
     * @return 200 with the created {@link ChatResponse} wrapped in a success envelope (code TM_280)
     */
    @Operation(summary = "Creates a public room by forcing the request subtype to \"room\" and delegating to the unified group-creation path")
    @PostMapping(value = "/room", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createRoom(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        request.setSubtype("room");
        ChatResponse response = groupService.createGroup(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Room created successfully", "TM_280"));
    }
}
