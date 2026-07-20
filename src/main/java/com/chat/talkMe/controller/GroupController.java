package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.request.UpdateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.GroupMemberResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Group / channel management. A group IS a chat, so messaging still flows through
 * ChatController/MessageController — this handles create, info, membership & roles.
 */
@RestController
@RequestMapping("/chats/group")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.createGroup(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Group created successfully", "TM_280"));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ResponseDto<ChatResponse>> updateGroup(
            @PathVariable("id") String uuid,
            @Valid @RequestBody UpdateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.updateGroup(uuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Group updated", "TM_281"));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<ResponseDto<List<GroupMemberResponse>>> getMembers(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<GroupMemberResponse> members = groupService.getMembers(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(members));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<ResponseDto<ChatResponse>> addMembers(
            @PathVariable("id") String uuid,
            @RequestBody Map<String, List<String>> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<String> memberIds = body.getOrDefault("memberIds", List.of());
        ChatResponse response = groupService.addMembers(uuid, memberIds, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Members added", "TM_282"));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<ResponseDto<Void>> removeMember(
            @PathVariable("id") String uuid,
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.removeMember(uuid, userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Member removed", "TM_283"));
    }

    @PutMapping("/{id}/members/{userId}/role")
    public ResponseEntity<ResponseDto<Void>> setRole(
            @PathVariable("id") String uuid,
            @PathVariable("userId") String userId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MemberRole role = MemberRole.valueOf(body.getOrDefault("role", "MEMBER").toUpperCase());
        groupService.setRole(uuid, userId, role, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Role updated", "TM_284"));
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<ResponseDto<Void>> leave(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.leaveGroup(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Left group", "TM_285"));
    }

    @PostMapping("/{id}/transfer-ownership")
    public ResponseEntity<ResponseDto<Void>> transferOwnership(
            @PathVariable("id") String uuid,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.transferOwnership(uuid, body.get("newOwnerId"), userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Ownership transferred", "TM_286"));
    }

    /** Discover public channels/rooms. type=channel|room (omit for both). */
    @GetMapping("/discover")
    public ResponseEntity<ResponseDto<List<ChatResponse>>> discover(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "tag", required = false) String tag,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ChatResponse> response = groupService.discover(type, query, tag, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /** Join a public, open channel/room. */
    @PostMapping("/{id}/join")
    public ResponseEntity<ResponseDto<ChatResponse>> join(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.joinChat(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Joined", "TM_282"));
    }

    /** Accept a pending group invitation (join the group). */
    @PostMapping("/{id}/invite/accept")
    public ResponseEntity<ResponseDto<ChatResponse>> acceptInvite(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.acceptGroupInvite(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Invite accepted", "TM_283"));
    }

    /** Decline a pending group invitation. */
    @PostMapping("/{id}/invite/decline")
    public ResponseEntity<ResponseDto<Void>> declineInvite(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.declineGroupInvite(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Invite declined", "TM_284"));
    }

    /** Report a group/channel/room. */
    @PostMapping("/{id}/report")
    public ResponseEntity<ResponseDto<Void>> report(
            @PathVariable("id") String uuid,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String reason = body != null ? body.getOrDefault("reason", "other") : "other";
        String details = body != null ? body.get("details") : null;
        groupService.reportChat(uuid, reason, details, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Report submitted", "TM_307"));
    }
}
